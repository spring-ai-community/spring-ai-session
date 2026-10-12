# Session JDBC — Overview & Setup

`spring-ai-session-jdbc` provides a JDBC-backed implementation of `SessionRepository`
that stores session data in two relational tables. It supports PostgreSQL, MySQL, MariaDB,
and H2 out of the box.

---

## Tables

```
AI_SESSION          — session metadata (id, TTL, metadata JSON, event_version)
AI_SESSION_INDEX    — indexed metadata attributes (session_id, attr_name, attr_value), FK → AI_SESSION
AI_SESSION_EVENT    — event log (FK → AI_SESSION, ON DELETE CASCADE)
```

`AI_SESSION_EVENT` rows are ordered by a monotonic `seq` column (insertion order). The log
is append-only: compaction appends a `COMPACTION` boundary row that names the active view
by event id, and never updates or deletes a row, so the full history stays searchable.

---

## Dependency

With the [BOM](../getting-started.md#add-the-bom-recommended) imported, no version is needed:

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-session-jdbc</artifactId>
</dependency>
```

---

## Schema

DDL scripts are bundled on the classpath. Each supported database has a dialect (auto-detected
from the database metadata) and a schema script:

| Database | Dialect class | Script |
|---|---|---|
| PostgreSQL | `PostgresJdbcSessionRepositoryDialect` | `org/springframework/ai/session/jdbc/schema-postgresql.sql` |
| MySQL / MariaDB | `MysqlJdbcSessionRepositoryDialect` | `org/springframework/ai/session/jdbc/schema-mysql.sql` |
| H2 | `H2JdbcSessionRepositoryDialect` | `org/springframework/ai/session/jdbc/schema-h2.sql` |

Other databases are rejected at startup with an `IllegalStateException`. To use one,
implement `JdbcSessionRepositoryDialect` and pass it via
`JdbcSessionRepository.builder().dialect(...)`, or open an issue or contribute a dialect.

With the auto-configuration, set
[`initialize-schema`](auto-configuration.md#schema-initialisation) to apply the script on
startup. Otherwise use Spring Boot's SQL initialisation:

```yaml
spring:
  sql:
    init:
      schema-locations: classpath:org/springframework/ai/session/jdbc/schema-postgresql.sql
```

Or copy the script into your migration tool (Flyway, Liquibase).

---

## Manual bean setup

```java
@Bean
SessionRepository sessionRepository(DataSource dataSource) {
    return JdbcSessionRepository.builder()
        .dataSource(dataSource)   // SQL dialect is auto-detected from the database metadata
        .build();
}

@Bean
SessionService sessionService(SessionRepository sessionRepository) {
    return DefaultSessionService.builder().sessionRepository(sessionRepository).build();
}
```

The builder accepts optional overrides:

```java
JdbcSessionRepository.builder()
    .dataSource(dataSource)
    .dialect(new PostgresJdbcSessionRepositoryDialect())  // explicit dialect
    .transactionManager(txManager)                        // custom tx manager
    .jsonMapper(customJsonMapper)                         // custom JSON serialization
    .build();
```

---

## Using the repository

```java
@Autowired SessionRepository sessions;

// Create a session
Session session = sessions.save(Session.builder()
    .id(UUID.randomUUID().toString())
    .metadata(SessionAttributes.PRINCIPAL_NAME, "alice")
    .build());

// Append a message event
sessions.appendEvent(SessionEvent.builder()
    .id(UUID.randomUUID().toString())
    .sessionId(session.id())
    .timestamp(Instant.now())
    .message(new UserMessage("Hello"))
    .build());

// Query events
List<SessionEvent> events = sessions.findEvents(session.id(), EventFilter.builder().build());
```

---

## Design notes

**Event serialisation** — each `SessionEvent` is stored in four columns: `event_type` (the
`EventType` name), `message_type` (the `MessageType` name of a message event, else `NULL`),
`message_content` (the plain text of a message event, kept as its own column so keyword
searches run in SQL) and `payload` (JSON). For a message event the payload holds the
message's ordered Spring AI `MessagePart`s and its metadata, serialised with the parts' own
polymorphic JSON, so media, tool calls, tool results and provider-signed reasoning all
survive a round trip. For any other event type it holds `SessionEvent.getPayload()`. Rows
written before 0.11.0 keep their tool calls or tool responses in `message_data` and are
still read.

**Optimistic concurrency** — the `event_version` column on `AI_SESSION` is incremented on
every `appendEvent` that appends a new event and every successful `appendEvents` call.
`appendEvents` claims the version slot with `UPDATE … WHERE event_version = ?` before
inserting, so two concurrent compactions cannot both land a boundary.

**Compacted view** — a read with `EventFilter.compactedView()` (what `EventFilter.active()`
sets) first selects the session's newest `COMPACTION` row, then fetches in one query the
rows that boundary names plus every row from the boundary on, ordered by `seq`, and lets
`EventFilter.apply` put them in view order, filter and window them. The view is bounded by
compaction, so it is read whole. A session without a boundary is read whole too, minus
rows a 0.10 compaction flagged `archived`; that column is no longer written.

**Indexed attributes** — on every save the repository copies the metadata entries whose
keys are configured as indexed (`JdbcSessionRepository.builder().indexedAttributes(...)`,
always including `SessionAttributes.PRINCIPAL_NAME`) into `AI_SESSION_INDEX`, one row per
attribute, on the first 512 characters of the value. `findByIndex(name, value)` is one
query over that table joined to `AI_SESSION`. The `user_id` column of 0.10 is no longer
part of the model: it is still written with the principal name (so a database that
declares it `NOT NULL` keeps working), a row whose metadata has no principal is read with
`user_id` as its principal, and the schema script copies `user_id` into the index once.

**Cross-session search** — `findEvents(sessionIds, filter)` is one query over the given
sessions (`session_id IN (...)`) that filters, sorts by timestamp and applies the page in
the database.

**`synthetic` column** — stored as a dedicated `BOOLEAN` column (not only in the metadata
JSON blob) so `EventFilter.excludeSynthetic()` translates to a SQL predicate instead of
an in-process scan.

**`EventFilter.keyword()`/`keywords()`** translate to `LOWER(...) LIKE ? ESCAPE '!'`
predicates — one for `keyword`, one per term for `keywords`, joined with `AND`/`OR` per
`matchMode`. Terms are always bound as JDBC parameters, and `%`, `_` and `!` are escaped so
they match literally, as in the in-memory repository.

**`EventFilter.lastN()` keeps turns whole with at most one extra query.** The newest N
rows come from `ORDER BY seq DESC LIMIT ?`. When that window is full, its oldest row is
not a `USER` event and the filter neither searches text nor hides `USER` events, a second
query fetches the rows between the nearest preceding matching `USER` event and the window
(`seq >= (SELECT MAX(seq) … WHERE message_type = 'USER' AND seq < ?)`), bounded by one
turn and served by the `(session_id, seq)` index. Cross-session reads use a plain `LIMIT`.

**`EventFilter.pattern()` falls back to in-memory filtering.** Java regex cannot be
translated to portable SQL — each database has its own regex dialect, none a superset of
Java's (backreferences, lookaround, named groups). When `pattern` is set, every other
criterion is still pushed down to SQL, but pagination (`lastN`/`page`/`pageSize`) is
deferred: the SQL-filtered rows are fetched, re-checked with `EventFilter.matches()` in
Java, and paginated afterward, as `InMemorySessionRepository` does. Expect a `pattern`
query to scan more history than an equivalent `keyword`/`keywords` query.

---

## See also

- [Auto-configuration](auto-configuration.md) — let Spring Boot wire everything automatically
- [Session Concepts](../session-management/concepts.md) — `SessionRepository` SPI details
- [Context Compaction](../session-management/compaction.md) — optimistic-lock compaction with JDBC
