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
them, so the full history stays searchable. The `event_version` column on `AI_SESSION` is
incremented on every `appendEvent` and `compactEvents` call, enabling optimistic-lock
compaction.

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

DDL scripts are bundled on the classpath. Run the one matching your database:

| Database | Script |
|---|---|
| PostgreSQL | `org/springframework/ai/session/jdbc/schema-postgresql.sql` |
| H2 | `org/springframework/ai/session/jdbc/schema-h2.sql` |
| MySQL / MariaDB | `org/springframework/ai/session/jdbc/schema-mysql.sql` |

Apply the schema with Spring Boot's SQL initialisation:

```yaml
spring:
  sql:
    init:
      schema-locations: classpath:org/springframework/ai/session/jdbc/schema-postgresql.sql
```

Or use your existing migration tool (Flyway, Liquibase) by copying the appropriate script
into your migration directory.

---

## Manual bean setup

```java
@Bean
SessionRepository sessionRepository(DataSource dataSource) {
    return JdbcSessionRepository.builder()
        .dataSource(dataSource)   // SQL dialect is auto-detected from the DataSource URL
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

## Supported databases

| Database | Dialect class |
|---|---|
| PostgreSQL | `PostgresJdbcSessionRepositoryDialect` |
| H2 | `H2JdbcSessionRepositoryDialect` |
| MySQL / MariaDB | `MysqlJdbcSessionRepositoryDialect` |

Other databases default to the PostgreSQL dialect. Open an issue or contribute a dialect
implementation for additional databases.

---

## Design notes

**Message serialisation** — each `SessionEvent`'s wrapped `Message` is stored in three
columns: `message_type` (enum name), `message_content` (plain text), and `message_data`
(JSON for tool calls and tool responses). This avoids Jackson polymorphism on Spring AI
message types.

**Optimistic concurrency** — the `event_version` column on `AI_SESSION` is incremented on
every `appendEvent` and `compactEvents` call. `compactEvents` atomically claims the version
slot with `UPDATE … WHERE event_version = ?` before modifying the event log, making
compaction safe under concurrent access.

**`synthetic` column** — stored as a dedicated `BOOLEAN` column (not only in the metadata
JSON blob) so `EventFilter.excludeSynthetic()` translates to a SQL predicate instead of
an in-process scan.

**`EventFilter.keyword()`/`keywords()`** both translate to SQL `LIKE` predicates —
`keyword` as a single `AND LOWER(...) LIKE ?` clause, `keywords` as N such clauses joined
with `AND`/`OR` per `matchMode`, one bound parameter per term. Every term is bound as a
JDBC parameter, never concatenated into the SQL text.

**`EventFilter.pattern()` falls back to in-memory filtering.** A `java.util.regex.Pattern`
cannot be safely translated to portable SQL — H2, MySQL, and PostgreSQL each have their
own regex dialect, none a strict superset of Java's regex syntax (backreferences,
lookaround, named groups). When `pattern` is set, every other criterion is still pushed
down to SQL as usual, but pagination (`lastN`/`page`/`pageSize`) is deferred: the full
SQL-filtered result set is fetched, `pattern` (and, redundantly but harmlessly, every
other criterion) is re-checked via `EventFilter.matches()` in Java, and pagination is
applied afterward — mirroring how `InMemorySessionRepository` filters and paginates.
Expect a `pattern` query to scan more of a session's event history than an equivalent
`keyword`/`keywords` query.

---

## See also

- [Auto-configuration](auto-configuration.md) — let Spring Boot wire everything automatically
- [Session Concepts](../session-management/concepts.md) — `SessionRepository` SPI details
- [Context Compaction](../session-management/compaction.md) — optimistic-lock compaction with JDBC
