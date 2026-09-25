# Migration Guide

## Upgrading to 0.9.0

### Breaking: `conversation_search` needs the session ID in the tool context

`SessionEventTools` reads the session ID from the `ToolContext`, and Spring AI does not copy
advisor parameters into it. Before 0.9.0 the tool then silently searched a shared
`"default"` session. It now returns an error message to the model instead. Pass the ID to
both the advisor and the tool context:

```java
chatClient.prompt()
    .user(question)
    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
    .toolContext(Map.of(ChatMemory.CONVERSATION_ID, sessionId))
    .call()
    .content();
```

### Breaking: `SessionService.create(...)` rejects an existing session ID

`create` used to upsert: an existing session with the same ID got a new `userId` and TTL
but kept its event log. It now throws `IllegalStateException("Session already exists: …")`.
Use `findById` first if the session may already exist. `SessionMemoryAdvisor` handles this
itself, including two concurrent first requests for the same new session.

`create` now goes through the new `SessionRepository.saveIfAbsent(Session)`, which inserts
only if the id is free. The built-in repositories implement it atomically. **Custom
`SessionRepository` implementations should override it** with an atomic insert, such as a
primary-key-guarded `INSERT`. The default implementation is a non-atomic `findById` +
`save`, so two concurrent creates of the same id could still both succeed.

Relatedly, `SessionRepository.save(...)` on an existing session now keeps its original
`createdAt` in every implementation.

### Breaking: JDBC timestamps are stored as UTC

The `TIMESTAMP` / `DATETIME` columns carry no time zone. They used to be written in the
JVM's default time zone and are now always written and read as UTC wall-clock time. If
your application ran in a non-UTC zone, existing rows are off by that zone's offset. To
fix them, shift `AI_SESSION.created_at`, `AI_SESSION.expires_at` and
`AI_SESSION_EVENT.timestamp` by the offset, or accept the shift for historical data.

### Breaking: unsupported databases fail fast

`JdbcSessionRepositoryDialect.from(DataSource)` used to fall back to the PostgreSQL
dialect for an unknown or undetectable database, which then failed at query time. It now
throws `IllegalStateException`. For other databases, implement `JdbcSessionRepositoryDialect`
and pass it via `JdbcSessionRepository.builder().dialect(...)`.

### Breaking (custom dialects only): `LIKE` patterns are escaped

Keyword and branch filters now treat `%`, `_` and `!` literally. The keyword parameter is
escaped with `!`, so a custom `JdbcSessionRepositoryDialect` must add `ESCAPE '!'` to its
`getKeywordFilterFragment()` / `getKeywordPredicateFragment()` SQL. The built-in dialects
already do.

### Behavior changes

- **`IdempotentSessionEventIdGenerator` id format.** Ids are now
  `<messageType>-<sha256>` (at most 74 characters, which fits the `VARCHAR(255)` column).
  Blank tool-call ids (e.g. from Ollama) fall back to a content hash instead of all
  colliding. A retry that spans the upgrade is not recognized as a replay.
- **Compaction keeps the newest turn.** When the most recent turn alone exceeds the budget,
  the sliding-window, token-count and recursive-summarization strategies keep that turn
  instead of archiving the whole active window.
- **Compaction failures no longer fail the chat call.** An exception from compaction in
  `SessionMemoryAdvisor.after()` is logged, and the reply is returned as normal.
- **`EventFilter.merge`** treats `lastN` / `page`+`pageSize` as one unit, so a per-request
  paginated filter replaces an advisor-level `lastN` instead of throwing.
- **`CrossSessionRecallTools`** rejects a blank `query` and an unknown `matchMode`. These
  used to return everything or silently use `any`.
- **JDBC event ids** are a table-wide key. An id already used by another session now throws
  `IllegalStateException` instead of silently dropping the event.
- **New `SessionService.getActiveMessages(sessionId)`** returns the active context window
  (archived events excluded), which is the list to send to a model. `getMessages(sessionId)`
  is unchanged and still returns the full log, archived events included.
- **Auto-configuration** backs off when there is no single `DataSource`, and when you define
  your own `SessionRepository` bean of any type. `DefaultSessionService` needs a single (or
  `@Primary`) `SessionRepository`.
- **MySQL schema script** can be re-run safely (`initialize-schema: always`), and MariaDB
  now uses the MySQL script.

## Upgrading to 0.8.0

No breaking API or schema changes. Two things to be aware of:

### Behavior change: `SessionMemoryAdvisor` no longer duplicates history inside a tool-calling loop

At default orders, `ToolCallingAdvisor` (order `HIGHEST_PRECEDENCE + 300`) wraps
`SessionMemoryAdvisor` (order `HIGHEST_PRECEDENCE + 1000`), so the advisor's
`before()`/`after()` run once per round of the tool-call loop. Before 0.8.0, from round 2
onward `before()` prepended the session history again even though the prompt already
carried this turn's messages, sending duplicate messages to the model.

`before()` now detects that the retrieved history is already a contiguous run in the
prompt and skips prepending it (the same guard as Spring AI's
`MessageChatMemoryAdvisor`, spring-ai GH-6211). Persisted events were never duplicated;
only the prompt sent to the model was affected.

**Action needed:** none. If you worked around the duplication — e.g. by calling
`ToolCallingAdvisor`'s `.disableInternalConversationHistory()` or giving
`SessionMemoryAdvisor` a custom order so it wraps the loop — those setups still work, but
the workaround is no longer required at default orders. See the "Default advisor order"
note in [ChatClient Integration](session-management/chat-client.md) for details.

### Dependency baseline: Spring AI 2.0.1, Spring Boot 4.1.1

0.8.0 builds against Spring AI 2.0.1 and Spring Boot 4.1.1 (previously 2.0.0 / 4.0.7).
Align your application's Spring AI BOM and Spring Boot versions accordingly.

## Upgrading to 0.7.0

No breaking API or schema changes. New features:

- **Cross-session recall.** `CrossSessionRecallTools` provides a `cross_session_search` tool
  that searches every session of one user. See
  [Cross-Session Recall](session-management/cross-session-recall.md).
- **Multi-term and pattern search.** `EventFilter` gained `keywords` + `matchMode`
  (`ANY`/`ALL`) and a `pattern` (regex) criterion. See
  [Event Filtering](session-management/event-filtering.md).
- **Idempotent appends.** `SessionRepository.appendEvent` is a no-op for an event id that
  is already stored, and `SessionMemoryAdvisor` accepts pluggable event-id generators
  (`requestEventIdGenerator` / `responseEventIdGenerator`, e.g.
  `IdempotentSessionEventIdGenerator`). Custom `SessionRepository` implementations should
  follow the same replay contract. See
  [Idempotent session-event ids](session-management/chat-client.md#idempotent-session-event-ids).

## Upgrading to 0.6.0

Compaction now **archives** events instead of deleting them, so the full history stays
searchable through Recall Storage (issue #21). This introduces four breaking changes.

### Breaking: core module artifact renamed from `spring-ai-session-management` to `spring-ai-session`

The `groupId` (`org.springaicommunity`) and Java package
(`org.springframework.ai.session`) are unchanged — only the Maven artifact/module name
moved:

```xml
<!-- Before (0.5.x) -->
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-session-management</artifactId>
</dependency>

<!-- After (0.6.0) -->
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-session</artifactId>
</dependency>
```

No other module (`spring-ai-session-jdbc`, `spring-ai-autoconfigure-session`,
`spring-ai-autoconfigure-session-jdbc`, `spring-ai-starter-session-jdbc`,
`spring-ai-session-bom`) changed name. If you depend on the JDBC starter or the BOM rather
than the core artifact directly, no change is needed.

### Breaking: `SessionRepository.replaceEvents(...)` replaced by `compactEvents(...)`

Both `replaceEvents` overloads are removed. Custom `SessionRepository` implementations must
implement the new method instead:

```java
// Before (0.5.x)
boolean replaceEvents(String sessionId, List<SessionEvent> events, long expectedVersion);

// After (0.6.0): archive the removed events, swap in the new active window
boolean compactEvents(String sessionId, List<SessionEvent> archivedEvents,
        List<SessionEvent> retainedEvents, long expectedVersion);
```

The implementation must mark `archivedEvents` as archived (retained, not deleted), set the
active event set to `retainedEvents`, and preserve any previously-archived events.

### Breaking: `getEvents(sessionId)` / `getMessages(sessionId)` now include archived events

`EventFilter.all()` (the default for the no-arg accessors) returns the **full** log,
archived events included. To get the active context window — what you pass to the model —
use the new `EventFilter.active()`:

```java
// Active context window only (archived events excluded)
sessionService.getEvents(sessionId, EventFilter.active());

// Recall Storage: full history, archived included (unchanged)
sessionService.getEvents(sessionId, EventFilter.keywordSearch("topic"));
```

`SessionMemoryAdvisor` already forces `active()` when building the prompt, so no change is
needed there.

### Breaking: JDBC schema adds `seq` and `archived` columns

`AI_SESSION_EVENT` gains an `archived` flag and a monotonic `seq` ordering column (events
are now ordered by `seq`, not `timestamp`). Recreate the table or apply, e.g. for H2:

```sql
ALTER TABLE AI_SESSION_EVENT ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE AI_SESSION_EVENT ADD COLUMN seq BIGINT GENERATED BY DEFAULT AS IDENTITY;
```

See the bundled `schema-{h2,postgresql,mysql}.sql` for the full DDL per dialect.

## Upgrading to 0.3.0

### Breaking: `SessionMemoryAdvisor.Builder.defaultSessionId()` removed

**What changed**

`defaultSessionId(String)` has been removed from `SessionMemoryAdvisor.Builder`. The
advisor no longer falls back to a shared session — `SESSION_ID_CONTEXT_KEY` is now
**required** on every request. Omitting it throws `IllegalStateException`.

**Why**

A single default session ID was shared across all requests to the same advisor instance,
silently merging conversation history from different users or threads. This is a
correctness and security issue in any multi-user deployment.

**How to migrate**

Before (0.2.x):

```java
SessionMemoryAdvisor advisor = SessionMemoryAdvisor.builder(sessionService)
    .defaultSessionId("my-session-id")   // ← removed
    .build();

// Session ID came from the advisor default — no per-request param needed
client.prompt().user("Hello").call().content();
```

After (0.3.0):

```java
SessionMemoryAdvisor advisor = SessionMemoryAdvisor.builder(sessionService)
    .build();

// Session ID must be passed on every request
client.prompt()
    .user("Hello")
    .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId))
    .call()
    .content();
```

In a typical web application, resolve the session ID from the authenticated principal or
HTTP session in the controller, then pass it per call:

```java
@PostMapping("/chat")
String chat(@AuthenticationPrincipal UserDetails user, @RequestBody String message) {
    String sessionId = resolveSessionId(user.getUsername());
    return chatClient.prompt()
        .user(message)
        .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId))
        .call()
        .content();
}
```
