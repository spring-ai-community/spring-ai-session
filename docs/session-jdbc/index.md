# Session JDBC — Overview & Setup

`spring-ai-session-jdbc` provides a JDBC-backed implementation of `SessionRepository`
that stores session data in two relational tables. It supports PostgreSQL, MySQL, MariaDB,
and H2 out of the box.

---

## Tables

```
AI_SESSION          — session metadata (id, user_id, TTL, metadata JSON, event_version)
AI_SESSION_EVENT    — append-only event log (FK → AI_SESSION, ON DELETE CASCADE)
```

`AI_SESSION_EVENT` rows are ordered by a monotonic `seq` column (insertion order) and carry
`synthetic` and `archived` flags. Compaction archives events in place rather than deleting
them, so the full history stays searchable.

---

## Dependency

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-session-jdbc</artifactId>
    <version>${spring-ai-session.version}</version>
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
    .userId("alice")
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

**Message serialisation** — each `SessionEvent`'s wrapped `Message` is stored in three
columns: `message_type` (enum name), `message_content` (plain text), and `message_data`
(JSON for tool calls and tool responses). This avoids Jackson polymorphism on Spring AI
message types.

**Optimistic concurrency** — the `event_version` column on `AI_SESSION` is incremented on
every `appendEvent` and `compactEvents` call. `compactEvents` claims the version slot with
`UPDATE … WHERE event_version = ?` before modifying the event log, so concurrent
compactions cannot both succeed.

**`synthetic` column** — stored as a dedicated `BOOLEAN` column (not only in the metadata
JSON blob) so `EventFilter.excludeSynthetic()` translates to a SQL predicate instead of
an in-process scan.

**`EventFilter.keyword()`/`keywords()`** translate to `LOWER(...) LIKE ? ESCAPE '!'`
predicates — one for `keyword`, one per term for `keywords`, joined with `AND`/`OR` per
`matchMode`. Terms are always bound as JDBC parameters, and `%`, `_` and `!` are escaped so
they match literally, as in the in-memory repository.

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
