# Migration Guide

## Upgrading to 0.10.0

### Breaking: branch-based multi-agent isolation removed

ADK-style branches, deprecated in 0.9.0, are removed. These APIs no longer exist:

- `SessionEvent.Builder.branch(...)`, `SessionEvent.getBranch()` and
  `SessionEvent.isRootEvent()`;
- `EventFilter.forBranch(...)`, `EventFilter.Builder.branch(...)` and the `branch`
  component of the `EventFilter` record (`EventFilter.branch()` and the constructor
  parameter);
- `SessionEventTools.Builder.branch(...)`.

Give each sub-agent its own session instead, with its own `SessionMemoryAdvisor` and
compaction settings, and pass only the task in and the result back. See
[Multi-Agent](session-management/multi-agent.md#session-per-sub-agent) for an example.

### Behavior changes

- **`CompactionResult.eventsRemoved()` is renamed to `archivedEventCount()`.** The events it
  counts are archived, not removed; the superseded summaries that compaction does remove
  were never counted. No deprecated alias.
- **Every `USER` event starts a turn.** Turn counting (`TurnCountTrigger`) and every
  strategy's turn boundaries now use all non-synthetic `USER` events, and the event-count
  strategies (`maxEvents`, `maxEventsToKeep`) count all real events. Events that were
  stored on a branch before the upgrade become ordinary events: every reader sees them,
  and their `USER` events now count as turns and turn boundaries. Sessions with branched
  history may therefore be compacted sooner after the upgrade.
- **One latest stored system message per session.** When system messages are stored
  (opt-in), the latest one in the session is the system prompt; it used to be the latest
  one per branch. Earlier ones, including those stored on former branches, are archived at
  the next compaction. The kept system message counts fully toward the
  `TokenCountCompactionStrategy` budget and the `TokenCountTrigger` threshold.
- **`SessionMemoryAdvisor`** no longer reads or writes by branch: it records every user and
  assistant event as an ordinary event and uses the latest stored system message. It also
  compacts only once a turn is complete: inside a tool-calling loop it no longer compacts
  after a reply that requests tool calls. Compacting in the middle of a turn with a
  summarizing strategy made the next round re-send the whole history.
- **`RecursiveSummarizationCompactionStrategy`:** when the summarizer returns a blank
  summary, superseded stored system messages are still archived, and
  `tokensEstimatedSaved` is now net of the new summary turn's tokens.
- **Compaction validates its inputs:** a new event in a strategy result that belongs to
  another session is rejected with `IllegalArgumentException` when the plan is built, and
  both built-in repositories reject a plan that archives an id not in the log.
- **`SessionService.findEventsByUserId(userId, filter)`** returns the matching events of
  every session of a user, sorted by timestamp across sessions and windowed by the
  filter's `lastN` or page. `cross_session_search` now runs one such query per page
  instead of one query per session plus in-memory paging; a page can be shorter than
  `pageSize` because events without text are dropped after paging.
- **Compaction never reorders the log.** A kept system message stays where it was stored
  instead of moving to the front of the active window, and archived events stay where they
  were. `getEvents(...)` therefore returns events in the order they were appended, plus
  each summary turn right before the conversation it precedes. `SessionMemoryAdvisor`
  still puts system messages first in the prompt. A custom loop that reads the active
  window must put the system message first itself (see
  [System Messages](session-management/system-messages.md)).
- **JDBC idempotent replay works inside a caller's transaction.** `appendEvent` now looks
  the event id up before inserting instead of catching the duplicate-key error. A retried
  append inside an application-managed transaction no longer marks it rollback-only (or,
  on PostgreSQL, aborts it).

### JDBC: the `branch` column is unused

`JdbcSessionRepository` no longer reads or writes `AI_SESSION_EVENT.branch`. The column
stays in the bundled schema scripts so existing databases keep working, and it will be
removed from them in a later release. You can drop it yourself:

```sql
ALTER TABLE AI_SESSION_EVENT DROP COLUMN branch;
```

### Custom implementers: the `SessionRepository` contract

The repository contract changed so that a backend owns *selection* (filtering, paging,
searching) and core owns *interpretation* (what a compaction means). A custom repository
(Redis, MongoDB, …) must be updated:

- **`compactEvents(sessionId, archivedEvents, retainedEvents, expectedVersion)` is
  removed.** Implement
  `applyCompaction(String sessionId, CompactionPlan plan, long expectedVersion)` instead.
  The service computes the plan from the active log and the strategy result; the
  repository only applies it, under the same compare-and-swap on `expectedVersion`:
    - flag every id in `plan.archiveIds()` archived **in place** (reject an unknown id
      with `IllegalArgumentException` and change nothing);
    - remove every id in `plan.deleteIds()` (a superseded summary);
    - insert each `plan.inserts()` group immediately before its `beforeEventId` anchor,
      or at the end of the log when the anchor is `null`;
    - never move an existing event.

  `CompactionPlan.applyTo(List<SessionEvent> log)` is the reference implementation for a
  log held as a list; a list-based store can call it directly.
- **`saveIfAbsent(Session)` and `deleteExpiredSessions(Instant)` are abstract.** Their
  non-atomic defaults are gone: implement `saveIfAbsent` with an atomic insert-if-absent
  (a primary-key-guarded `INSERT`, a compare-and-set put) and `deleteExpiredSessions`
  with one guarded delete (`DELETE … WHERE expires_at < ?`), so a session whose TTL was
  extended concurrently is kept.
- **`findExpiredSessionIds(Instant)` is removed.** It had no caller left.
- **New `findEventsByUserId(userId, filter)`** backs `cross_session_search`. The default
  runs the filter over each of the user's sessions and windows the union in memory; a
  store that can join sessions and events should override it with one sorted, paged
  query.
- **`findEvents` must push the filter down.** `excludeArchived`, `excludeSynthetic`,
  `messageTypes`, the time range, `lastN`, `page`/`pageSize` and, where the store can,
  the keywords belong in the query; a `lastN` or a page must not read the whole log. Use
  `EventFilter.apply(List<SessionEvent>)` only for what the store cannot express (a
  `pattern`, typically). Stores that keep messages as columns or fields should use
  `SessionEventCodec` (package `org.springframework.ai.session.support`) so every backend
  stores the same shape.
- **Run the contract tests.** Add `org.springaicommunity:spring-ai-session-test` in test
  scope, subclass `AbstractSessionRepositoryContractTests` and implement
  `createRepository()`. It is the suite the built-in repositories run; see
  [Implementing a `SessionRepository`](session-management/concepts.md#implementing-a-sessionrepository).

### Custom implementers: `CompactionStrategy` results must be in log order

`CompactionResult.compactedEvents()` is the new active window **in log order**: every
event that is not archived keeps its position, and a new event (such as a summary turn)
goes right before the existing event it should precede. The repositories use only the
position of new events. Existing events are kept as they are stored, so returning a
modified copy of an existing event (same id, e.g. with a truncated tool result) or a
different order for existing events no longer has any effect. To change an event's
content, archive it and add a new event instead.

### Custom implementers: `JdbcSessionRepositoryDialect.getBranchFilterFragment()` removed

The method is gone from the dialect contract. A custom dialect that overrides it no longer
compiles because of its `@Override`; delete the method.

## Upgrading to 0.9.0

!!! note "Branches were removed in 0.10.0"
    The branch-related items below (branch filters, `getBranchFilterFragment()`,
    `SessionEventTools.builder().branch(...)`, per-branch system messages and root-level
    turns) are superseded by [Upgrading to 0.10.0](#upgrading-to-0100). If you upgrade from
    0.8 straight to 0.10, skip them.

### Application changes

#### Breaking: `conversation_search` needs the session ID in the tool context

`SessionEventTools` reads the session ID from the `ToolContext`, and Spring AI does not copy
advisor parameters into it. Before 0.9.0 the tool then silently searched a shared
`"default"` session. It now returns an error message to the model instead. Pass the ID to
both the advisor and the tool context:

```java
chatClient.prompt()
    .user(question)
    .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId))
    .toolContext(Map.of(ChatMemory.CONVERSATION_ID, sessionId))   // same key value
    .call()
    .content();
```

#### Breaking: storing system messages requires opt-in

`DefaultSessionService.appendEvent` / `appendMessage` now reject a `SystemMessage` with an
`IllegalArgumentException`; supply system prompts per request instead (see
[System Messages](session-management/system-messages.md)). `SessionMemoryAdvisor` never
stored them, so `ChatClient` users are unaffected. If you store system messages on purpose,
enable it:

```java
DefaultSessionService.builder()
    .sessionRepository(repository)
    .allowSystemMessages(true)
    .build();
```

or set `spring.ai.session.allow-system-messages=true` with Spring Boot auto-configuration.
Existing stored system messages can still be read either way.

#### Breaking: `SessionService.create(...)` rejects an existing session ID

`create` used to upsert: an existing session with the same ID got a new `userId` and TTL
but kept its event log. It now throws `IllegalStateException("Session already exists: …")`.
Use `findById` first if the session may already exist. `SessionMemoryAdvisor` handles this
itself, including two concurrent first requests for the same new session.

`create` now goes through the new `SessionRepository.saveIfAbsent(Session)`, and
`save(...)` on an existing session keeps its original `createdAt` in every implementation.
Custom repositories: see the [checklist](#breaking-checklist-for-custom-sessionrepository-implementations).

#### Breaking: JDBC timestamps are stored as UTC

The `TIMESTAMP` / `DATETIME` columns carry no time zone. They used to be written in the
JVM's default time zone and are now always written and read as UTC wall-clock time. If
your application ran in a non-UTC zone, existing rows are off by that zone's offset. To
fix them, shift `AI_SESSION.created_at`, `AI_SESSION.expires_at` and
`AI_SESSION_EVENT.timestamp` by the offset, or accept the shift for historical data.

#### Breaking: unsupported databases fail fast

`JdbcSessionRepositoryDialect.from(DataSource)` used to fall back to the PostgreSQL
dialect for an unknown or undetectable database, which then failed at query time. It now
throws `IllegalStateException`. For other databases, implement `JdbcSessionRepositoryDialect`
and pass it via `JdbcSessionRepository.builder().dialect(...)`.

#### Breaking: JDBC auto-configuration no longer brings a connection pool

`spring-ai-autoconfigure-session-jdbc` now declares `spring-boot-jdbc` as an **optional**
dependency, and the auto-configuration backs off when it is missing. The
`spring-ai-starter-session-jdbc` starter brings `spring-boot-starter-jdbc` (and so HikariCP)
instead, so starter users are unaffected. If you depend on the auto-configuration module
directly, add the JDBC starter yourself:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
```

#### Breaking: JDBC keyword and branch filters match `%` and `_` literally

Keyword searches (`EventFilter.keyword(...)` / `keywords(...)`, `conversation_search`,
`cross_session_search`) and branch filters used to pass `%` and `_` through to SQL `LIKE`
as wildcards, so JDBC results differed from the in-memory repository. They now match
`%`, `_` and `!` literally on every repository. If you relied on them as JDBC wildcards,
use `EventFilter.pattern(...)` instead.

#### Deprecated: branch-based multi-agent isolation

ADK-style branches in one shared session are deprecated for removal in 0.10.0. The
deprecated APIs are:

- `SessionEvent.Builder.branch(...)`, `SessionEvent.getBranch()` and
  `SessionEvent.isRootEvent()`;
- `EventFilter.forBranch(...)`, `EventFilter.Builder.branch(...)` and
  `EventFilter.branch()`;
- `SessionEventTools.Builder.branch(...)`.

They still work in 0.9.0. Give each sub-agent its own session instead, with its own
`SessionMemoryAdvisor` and compaction settings, and pass only the task in and the result
back. See [Multi-Agent](session-management/multi-agent.md#session-per-sub-agent) for an
example. Branches were removed in 0.10.0; see [Upgrading to 0.10.0](#upgrading-to-0100).

### Custom implementers

#### Breaking: checklist for custom `SessionRepository` implementations

The `SessionRepository` contract was tightened. A custom implementation (for example a
Redis repository) should:

- **Implement `saveIfAbsent(Session)` atomically** (e.g. a primary-key-guarded `INSERT`, or
  Redis `SET … NX`). `SessionService.create` relies on it. The default implementation is a
  non-atomic `findById` + `save`, so two concurrent creates of the same id could both
  succeed.
- **Keep the original `createdAt` in `save(...)`** for an existing session, and **return
  the stored session**, not the argument.
- **In `appendEvent(...)`, reject an event id that belongs to another session** with
  `IllegalStateException`. A duplicate id in the *same* session is still an idempotent
  no-op.
- **In `compactEvents(...)`, reject `archivedEvents` that are not in the session's log**
  (the whole call should have no effect). Otherwise a mistaken caller can lose events.
  (`compactEvents` was replaced by `applyCompaction` in 0.10.0; see
  [Upgrading to 0.10.0](#upgrading-to-0100).)

The built-in in-memory and JDBC repositories already do all of this.

#### Breaking: checklist for custom `JdbcSessionRepositoryDialect` implementations

- **`getKeywordFilterFragment()` / `getKeywordPredicateFragment()` must declare
  `ESCAPE '!'`.** The keyword parameter is now escaped with `!`.
- **`getBranchFilterFragment()` must escape the stored branch** before using it as a `LIKE`
  prefix, and declare `ESCAPE '!'`: `REPLACE(REPLACE(REPLACE(e.branch, '!', '!!'), '%', '!%'), '_', '!_')`.
  Otherwise `%` and `_` in branch names act as wildcards.
- **`getUpsertSessionSql()` must no longer update `created_at`** on an existing row; only
  `user_id`, `expires_at` and `metadata` are refreshed. The five parameters are unchanged.
- **New `getInsertSessionIfAbsentSql()`,** used by `saveIfAbsent`. The default is a plain
  `INSERT` (a duplicate key means "already exists"). Override it if a failed statement
  aborts the enclosing transaction on your database, as the PostgreSQL dialect does with
  `ON CONFLICT (id) DO NOTHING`.
- **The public `JdbcSessionRepositoryDialect.logger` constant was removed.** Use your own
  logger.

The built-in PostgreSQL, MySQL/MariaDB and H2 dialects already follow these rules.

### Behavior changes

- **`SessionMemoryAdvisor` records events on its filter's branch.** An advisor configured
  with `EventFilter.forBranch("orch.researcher")` (or given one per request) used to read
  only its branch's view but write every user and assistant message as a root event, which
  the orchestrator and every sibling agent could then see. It now records them on that
  branch. Advisors without a branch filter (the default) still write root events. Since
  only root `USER` events count as turns, a branched advisor's messages no longer advance
  `TurnCountTrigger` or move compaction's turn boundaries. Events already stored at the
  root are not moved. Branches were removed in 0.10.0; see
  [Upgrading to 0.10.0](#upgrading-to-0100).
- **The latest stored system message wins.** Stored system messages used to be archived
  like any other event. Now every strategy keeps the latest one per branch active, places
  it first and never summarizes it; earlier ones are archived when compaction runs, and
  `SessionMemoryAdvisor` sends only the latest one of its own branch. The root agent's kept
  system message counts toward `TokenCountCompactionStrategy`'s `maxTokens` and
  `TokenCountTrigger`'s threshold, so size them with it in mind. See
  [the full rules](session-management/system-messages.md#compaction-the-latest-stored-system-message-wins).
- **Stored system messages no longer cause duplicates.** In a tool-calling loop with a
  stored system message and a request system prompt, the 0.8.0 loop check failed from
  round 2 on and the whole history was sent twice; it no longer does. `SessionMemoryAdvisor`
  also drops a system message whose text exactly matches an earlier one in the prompt,
  e.g. a stored system message that is also sent on the request.
- **The tool-loop history check works with JDBC.** It compared messages with `equals`, but
  `JdbcSessionRepository` stores neither message metadata (such as the finish reason) nor
  media, so reloaded messages never matched and the whole history was sent twice from
  round 2 on. Messages are now compared by type, text, tool calls and tool responses.
  The workaround of ordering `SessionMemoryAdvisor` ahead of `ToolCallingAdvisor` is no
  longer needed.
- **`IdempotentSessionEventIdGenerator` id format.** Ids are now
  `<messageType>-<sha256>` (at most 74 characters, which fits the `VARCHAR(255)` column).
  Blank tool-call ids (e.g. from Ollama) fall back to a content hash instead of all
  colliding. A retry that spans the upgrade is not recognized as a replay.
- **Compaction keeps the newest turn.** When the most recent turn alone exceeds the budget,
  the sliding-window, token-count and recursive-summarization strategies keep that turn
  instead of archiving the whole active window. When the active window has no root-level
  user message at all (for example, every event is on a sub-agent branch), compaction
  archives nothing; `TokenCountCompactionStrategy` used to archive the whole window.
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
- **New `SessionEventTools.builder().branch(...)`** scopes `conversation_search` to what a
  sub-agent's branch can see, so peer sub-agents don't search each other's events.
- **`EVENT_FILTER_CONTEXT_KEY` is type-checked.** A value that is not an `EventFilter` now
  throws `IllegalArgumentException` with a clear message instead of a `ClassCastException`.
- **`TokenCountTrigger.builder()` defaults to the JTokkit estimator,** as documented. It
  used to throw unless `tokenCountEstimator(...)` was set.
- **Stricter builder validation.** `TokenCountTrigger.builder().threshold(...)`,
  `TokenCountCompactionStrategy.builder().maxTokens(...)` and
  `SlidingWindowCompactionStrategy.builder().maxEvents(...)` now reject a value `<= 0`, and
  their `tokenCountEstimator(...)` rejects `null`, when the setter is called rather than
  at `build()`.

## Upgrading to 0.8.0

No breaking API or schema changes. Two things to be aware of:

### Behavior change: `SessionMemoryAdvisor` no longer duplicates history inside a tool-calling loop

At default orders, `ToolCallingAdvisor` (`HIGHEST_PRECEDENCE + 300`) wraps
`SessionMemoryAdvisor` (`HIGHEST_PRECEDENCE + 1000`), so the advisor runs once per round of
the tool-call loop. Before 0.8.0, from round 2 on it prepended the session history again,
sending duplicate messages to the model (persisted events were never duplicated). It now
skips history that is already in the prompt, the same guard as Spring AI's
`MessageChatMemoryAdvisor` (spring-ai GH-6211).

**Action needed:** none. Workarounds such as `ToolCallingAdvisor`'s
`.disableInternalConversationHistory()` or a custom advisor order still work but are no
longer required. See the "Default advisor order" note in
[ChatClient Integration](chat-client/chat-client.md).

### Dependency baseline: Spring AI 2.0.1, Spring Boot 4.1.1

0.8.0 builds against Spring AI 2.0.1 and Spring Boot 4.1.1 (previously 2.0.0 / 4.0.7).
Align your application's Spring AI BOM and Spring Boot versions accordingly.

## Upgrading to 0.7.0

No breaking API or schema changes. New features:

- **Cross-session recall.** `CrossSessionRecallTools` provides a `cross_session_search` tool
  that searches every session of one user. See
  [Cross-Session Recall](recall-memory/cross-session-recall.md).
- **Multi-term and pattern search.** `EventFilter` gained `keywords` + `matchMode`
  (`ANY`/`ALL`) and a `pattern` (regex) criterion. See
  [Event Filtering](session-management/event-filtering.md).
- **Idempotent appends.** `SessionRepository.appendEvent` is a no-op for an event id that
  is already stored, and `SessionMemoryAdvisor` accepts pluggable event-id generators
  (`requestEventIdGenerator` / `responseEventIdGenerator`, e.g.
  `IdempotentSessionEventIdGenerator`). Custom `SessionRepository` implementations should
  follow the same replay contract. See
  [Idempotent session-event ids](chat-client/chat-client.md#idempotent-session-event-ids).

## Upgrading to 0.6.0

Compaction now **archives** events instead of deleting them, so the full history stays
searchable through Recall Storage (issue #21). This introduces four breaking changes.

### Breaking: core module artifact renamed from `spring-ai-session-management` to `spring-ai-session`

Only the Maven artifact changed; the `groupId` and the `org.springframework.ai.session`
package are unchanged:

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

No other module was renamed, so if you depend on the JDBC starter or the BOM rather than
the core artifact, no change is needed.

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

`defaultSessionId(String)` has been removed: the shared default session silently merged
the history of different users or threads. `SESSION_ID_CONTEXT_KEY` is now **required** on
every request; omitting it throws `IllegalStateException`.

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
