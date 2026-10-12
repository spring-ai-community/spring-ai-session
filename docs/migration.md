# Migration Guide

## Upgrading to 0.11.0

### Dependency baseline: Spring AI 2.1.0-SNAPSHOT, Spring Boot 4.1.1

0.11.0 builds against Spring AI 2.1.0-SNAPSHOT and Spring Boot 4.1.1 (previously 2.0.1 / 4.1.1).
Spring AI 2.1 is where messages carry their content as ordered `MessagePart`s, which the
0.11.0 event log stores. Until Spring AI 2.1.0 is released, add the Spring snapshot
repository to your build (see [Maven Repositories](getting-started.md#maven-repositories))
and align your application's Spring AI BOM version accordingly.

### Breaking: `SessionEvent` has a type, and not every event is a message

`SessionEvent` gains `getType()` (an `EventType`: `MESSAGE`, `STATUS`, `INTERRUPT`,
`TOOL_CONFIRMATION`, `USAGE`, `COMPACTION`, `CHILD_CREATED`, `MESSAGE_SENT`,
`MESSAGE_RECEIVED`), `hasMessage()` and `getPayload()`. A builder without `type(...)` still
builds a `MESSAGE` event, so existing code that creates events is unchanged. What changes
is reading: `getMessage()` and `getMessageType()` throw `IllegalStateException` for an event
that is not a message. Code that iterates `getEvents(sessionId)` (which is
`EventFilter.all()`) must check `hasMessage()` or filter by type, because a log can now hold
other kinds of events. `SessionService.getMessages()` and `getActiveMessages()` already skip
them.

`EventFilter.active()` now selects the `MESSAGE` events of the active view (see the next
section); it is the prompt view and the compaction input, and runtime events do not belong
in either.
`EventFilter` gets a 13th record component, `eventTypes`; code calling the canonical
constructor must pass it (`null` for no restriction). The builder and the factories are
unchanged.

### Breaking: compaction is a projection, and the log is append-only

Compaction no longer modifies the event log. Up to 0.10.0 a pass flagged events
`archived` in place and inserted the summary turn in front of the kept window. In 0.11.0
a pass **appends** one `COMPACTION` boundary event, whose payload is a `CompactionBoundary`
naming by id the events the active view starts with (pinned, summary, preserved), followed
by the summary events. Every existing event stays exactly where and what it was. The
active view is projected from the newest boundary on every read: the events it names, in
that order, then everything appended since. Events the boundary does not name are
**covered**: still in the log, searchable through Recall Storage, never sent to the model.

This is the model that managed-agent harnesses use (a compaction is a record in the
transcript, not an edit of it), and it has three practical effects: a compaction is one
cheap batch insert, a store never rewrites rows, and every compaction is observable after
the fact (which strategy ran, what it covered, what it estimated to save).

What changes in the API:

- **`SessionEvent.isArchived()`, `asArchived()` and `Builder.archived(...)` are removed.**
  An event has no compaction state. To know whether an event is in the view, read the view
  (`EventFilter.active()`) or project it with `CompactionBoundary.project(log)`.
- **`EventFilter.excludeArchived` is renamed `activeView`.** Same record position, same
  builder position; `EventFilter.active()` sets it. With `activeView(true)` the filter
  applies to the view instead of the whole log, so a `lastN`, a page or a `messageTypes`
  selection never reaches into covered events. `EventFilter.apply(List)` now projects
  first when the flag is set.
- **`CompactionResult` is reshaped.** `compactedEvents()` / `archivedEvents()` /
  `archivedEventCount()` are replaced by `coveredEvents()`, `pinnedEvents()`,
  `preservedEvents()`, `summary()` (the new summary turn as `Message`s, empty for the window
  strategies), `strategy()`, plus `coveredEventCount()`, `isUnchanged()`, the `unchanged()`
  factory and `toEvents(sessionId)`. A custom strategy returns
  what should leave the view and what should stay; it no longer builds the new window or
  creates summary events itself, the service does.
- **`CompactionPlan` is removed and `SessionRepository.applyCompaction` is replaced by
  `appendEvents(sessionId, events, expectedVersion)`:** an atomic, version-checked append of
  several events that increments the version once. A custom repository implements it
  (validate that every event belongs to the session and has a new id, check the version,
  append) and honours `EventFilter.activeView()` in `findEvents`: read the newest
  `COMPACTION` event, fetch the events it names plus everything from it on, and hand them
  to `EventFilter.apply`, which projects, filters and windows. `findEvents(sessionIds, filter)`
  with an active view can fall back to the default per-session loop. The contract test kit
  checks all of it; its protected `plan(...)` and `apply(...)` helpers are replaced by
  `compact(sessionId, pinned, summary, preserved)` and `event(sessionId, message)`.
- **`SessionEvent.METADATA_COMPACTION_ID`** is set on every summary event to the id of its
  boundary.

What changes in behaviour:

- **The view has an order of its own.** Before, every kept event stayed at its log position
  and the summary was inserted in front of the kept window. Now the view is pinned events
  (the latest stored system message, and for the window strategies the earlier summary
  turns), then the summary, then the preserved events, then everything appended since. For
  a session without a stored system message the two orders are the same. With one, the
  system message is now first in the view, which is where `SessionMemoryAdvisor` put it
  anyway.
- **A pass that needs no cut writes nothing.** Superseded stored system messages used to be
  archived on every pass; now the next pass that cuts covers them, wherever they sit, and
  they stay in the view until then. `SessionMemoryAdvisor` uses only the latest one, so the
  prompt is unchanged. `TurnWindowCompactionStrategy`'s preamble is pinned rather than
  preserved.
- **Logs written by 0.10.0 keep working.** The JDBC repository no longer writes the
  `archived` column but still reads it: a session without a boundary excludes rows flagged
  `archived` from its view, so a session compacted by 0.10.0 keeps the view it had until
  its first 0.11.0 compaction, whose boundary then takes over. The column can be dropped
  once no such session remains. The in-memory repository holds nothing across versions.

### Breaking: scope and ownership leave the session model

`Session.userId` modelled an application concern and authorized nothing: the service never
compared the caller with the owner. It is gone. Who a session belongs to is now an
attribute in its metadata, resolved from the caller, indexed by the repository and
enforced by an authorization layer in front of the service, the way Spring Session and
Spring Security divide the work. See [Scope and Security](session-management/scope-and-security.md).

| Before | After |
|---|---|
| `Session.userId()`, `Session.builder().userId(...)`, `CreateSessionRequest.builder().userId(...)` (required) | removed; `metadata` carries `SessionAttributes.PRINCIPAL_NAME` (`"principalName"`). `SessionAttributes.principalName(session)` reads it; `Session.builder().metadata(key, value)` is new |
| `SessionRepository.findByUserId(userId)`, `SessionService.findByUserId(userId)` | `findByIndex(name, value)`: the sessions whose metadata holds `value` under `name`. The principal is indexed by every store; JDBC indexes other keys when told to (`indexedAttributes`) |
| `findEventsByUserId(userId, filter)` on the repository and the service | `findEvents(Collection<String> sessionIds, EventFilter filter)`; the caller chooses the sessions, through `findByIndex` or a `SessionSelector` |
| `CrossSessionRecallTools.builder(sessionService, userId)` | `builder(sessionService, SessionSelector)`, with `SessionSelector.principal(name)` for one principal's root sessions |
| `SessionMemoryAdvisor.USER_ID_CONTEXT_KEY`, `Builder.defaultUserId(...)`, the advisor's ownership check | removed. The session a request creates gets its scope from the service's `SessionScopeResolver`; `SESSION_METADATA_CONTEXT_KEY` passes other creation metadata. Authorization is the `AuthorizingSessionService`'s job |
| — | new `SessionScopeResolver` (`none()`, `fixed(Map)`; `DefaultSessionService.builder().scopeResolver(...)`), `SessionAttributes`, `SessionSelector` (`principal`, `byIndex`, `of`) |
| — | JDBC: `JdbcSessionRepository.builder().indexedAttributes(Set)`, `getIndexedAttributes()`, `MAX_INDEXED_VALUE_LENGTH` (512: an indexed value and a lookup value are both cut to their first 512 characters, so longer values match by prefix); property `spring.ai.session.repository.jdbc.indexed-attributes` |
| — | new module `spring-ai-session-security` (in the BOM): `SecurityContextScopeResolver`, `PrincipalSessionAuthorizationManager`, `AuthorizingSessionService`; auto-configured when Spring Security is present, off with `spring.ai.session.security.enabled=false`. The auto-configured `SessionService` bean is then an `AuthorizingSessionService`, so inject it as `SessionService`, not `DefaultSessionService` |

What to do:

- **Creating sessions.** Drop `.userId(...)`. With Spring Security and the security module
  nothing replaces it: the principal is resolved. Otherwise configure a
  `SessionScopeResolver`, or set `.metadata(SessionAttributes.PRINCIPAL_NAME, ...)` on the
  request.
- **Looking sessions up.** `findByUserId(x)` becomes
  `findByIndex(SessionAttributes.PRINCIPAL_NAME, x)`; `findEventsByUserId(x, filter)`
  becomes `findEvents(ids, filter)` with the ids from that lookup, or
  `SessionSelector.principal(x).select(service)`.
- **Authorization.** If you relied on the advisor's `USER_ID_CONTEXT_KEY` check, add the
  security module: the check it did is now made on every service call, for every caller,
  not only when the key was passed.
- **Custom repositories** implement `findByIndex(name, value)` and may override
  `findEvents(Collection, EventFilter)`; the contract test kit's `newSession(principalName)`
  helper now stores the principal in the metadata, and `principalSession(id, principalName)`
  builds such a session.
- **Streaming with the security module** is limited in this release; see the
  [streaming note](session-management/scope-and-security.md#with-spring-security). The JDBC schema adds `AI_SESSION_INDEX`
  (see below).

**Sessions written before 0.11.0** keep their owner: the JDBC row mapper reads `user_id` as
the principal when the metadata has none, the column is still written with the principal
name (a database that declares it `NOT NULL` keeps working), and the schema script copies
`user_id` into `AI_SESSION_INDEX` once (`INSERT … SELECT … WHERE NOT EXISTS`, safe to
re-run). Databases managed outside the bundled script need the new table and that copy.

### New: session trees for sub-agents

`SessionService.createChild(parentId, request)` (and `createChild(parentId, agentName)`)
creates a session that belongs to the parent's tree, what Claude Managed Agents calls a
thread. `Session` gains two fields for it:
`parentSessionId()` (`null` on a root) and `rootSessionId()` (the root of the tree; a
root's own id on a root, so it is never `null`), with `isRoot()` and `isChild()`. The
child inherits the parent's principal and expiry (and any key named in
`DefaultSessionService.builder().inheritedAttributes(...)`) and may not diverge from them,
carries `SessionAttributes.AGENT_NAME` when given, and appends a `CHILD_CREATED` marker
to the parent's log. The depth is limited to one level by default (`maxTreeDepth`).
`findChildren(parentId)` lists a session's direct children and `findTree(rootId)` the root
with every child of its tree; `SessionRepository` gains `findByParentSessionId` and
`findByRootSessionId` for them. `SessionTreeEvents` builds the `CHILD_CREATED`,
`MESSAGE_SENT` and `MESSAGE_RECEIVED` markers; the two message markers carry the exchanged
message next to their payload, so `hasMessage()` is true for them too
(`EventType.canCarryMessage()`), text searches find their text, and `isTurnStart()` is
false for them. See [Multi-Agent](session-management/multi-agent.md).

Two behaviour changes come with it:

- **`delete(sessionId)` cascades to the session's children.** A session created before
  0.11.0 has no children, so nothing changes for it.
- **`SessionSelector.principal(name)` selects root sessions only**, so
  `CrossSessionRecallTools.builder(service, SessionSelector.principal("alice"))` no longer searches sub-agent sessions;
  `SessionSelector.principalWithChildren(name)` does.

The JDBC `AI_SESSION` table gains the `parent_session_id` and `root_session_id` columns
(see the schema section below). `AuthorizingSessionService.createChild` requires the
caller to be granted the parent, and narrows `findChildren` and `findTree` like any
listing. The metadata convention the 0.10 multi-agent guide suggested (`parentSessionId`
and `agent` keys set by hand) is replaced by the API: those keys are plain metadata now,
so a session written that way stays a root, and `findChildren`, the cascade and the
selectors leave it alone.

### New: session status, turns and archive

`Session` gains `status()` (`SessionStatus.IDLE`, `RUNNING`, `TERMINATED`), `stopReason()`
(`StopReason.END_TURN`, `REQUIRES_ACTION`, `BUDGET_REACHED`, `INTERRUPTED`, `ERROR`),
`archivedAt()` and `isArchived()`. `SessionService` gains `startTurn(id)`, a compare-and-set
from `IDLE` to `RUNNING`; `endTurn(id, stopReason)`; `archive(id)`, which cascades to the
children, stamps `archivedAt` and makes the session read-only; and `aggregateStatus(rootId)`.
Each transition appends a `STATUS` event, built and read with `SessionStatusEvents`.
`SessionRepository` gains `updateStatus(id, expected, status, stopReason, archivedAt)`, the
compare-and-set behind them. `AuthorizingSessionService` guards the four calls.

Behaviour changes:

- **`SessionMemoryAdvisor` tracks the turn.** It calls `startTurn` in `before()` and
  `endTurn(END_TURN)` in `after()` once the reply no longer requests tool calls, and ends a
  failed call with `ERROR`. Two concurrent `ChatClient` calls on the same session therefore
  no longer interleave: the second fails with `IllegalStateException`. A turn left running by
  a crashed process is ended with `endTurn(id, StopReason.INTERRUPTED)`.
  `SessionMemoryAdvisor.builder().trackStatus(false)` restores the old behaviour.
- **An archived session rejects `appendEvent`, `startTurn`, `createChild` and `compact`**
  with `IllegalStateException`; reads keep working.
- **`SessionSelector.principal` and `principalWithChildren` leave archived sessions out**;
  `byIndex` includes them.

The JDBC `AI_SESSION` table gains `status`, `stop_reason` and `archived_at`; a row written
before reads as an idle root (see the schema section below).

### Renames and small API changes

- `EventFilter.compactedView` is `activeView` (record accessor, builder method and
  `EventFilter.active()` both), and `EventFilter.toBuilder()` copies a filter; `merge` and
  `withoutWindow` are unchanged in behaviour.
- `RecursiveSummarizationCompactionStrategy.Builder.maxEventsToKeep(...)` is `maxEvents(...)`, the
  name the sliding window already used, and `DEFAULT_MAX_EVENTS_TO_KEEP` is `DEFAULT_MAX_EVENTS`.
- `CompactionResult.keptEvents()`, `CompactionRequest.currentEventCount()` and
  `CompactionBoundary.namedEventIds()` are removed; `CompactionResult.toEvents(sessionId)` builds the
  boundary and summary events `SessionService.compact` appends.
- `CrossSessionRecallTools.builder(sessionService, principalName)` is removed; use
  `builder(sessionService, SessionSelector.principal(principalName))`.
- `Session.Builder.metadata(Map)` now adds the entries to the ones already set, like
  `CreateSessionRequest.Builder.metadata(Map)`, instead of replacing the map.
- New properties `spring.ai.session.max-tree-depth` (default `1`) and
  `spring.ai.session.inherited-attributes`.

### Breaking: `SessionEventCodec` encodes events, not messages

Custom `SessionRepository` implementations that use the shared codec: `encode(Message)` and
`EncodedMessage` are replaced by `encode(SessionEvent)` returning an `EncodedEvent`
(`type`, `messageType`, `text`, `payload`), and `decode(EncodedEvent)` returns a
`SessionEvent.Builder` holding the decoded type and content. For a message event the
payload is JSON of the message's Spring AI 2.1 `MessagePart`s and metadata, so media, tool
calls, tool results and signed reasoning now round-trip (this closes #53 and #46). The old
`decode(EncodedMessage)` stays, deprecated, to read what earlier versions wrote.

### Breaking: the JDBC schema changes once for 0.11.0

`AI_SESSION` gains the session-tree columns `parent_session_id` and `root_session_id` (each
indexed), the status columns `status`, `stop_reason` and `archived_at`, and the
`AI_SESSION_INDEX` table for indexed metadata attributes; `user_id` is kept and still written
on every save. `AI_SESSION_EVENT` gains `event_type` and `payload`, and allows a
`NULL` `message_type` (an event that is not a message). `message_data` is no longer written but
is still read, so existing rows load unchanged; the same goes for `archived`, which bounds the
view of a session without a boundary and must stay until that read is removed.

**H2 and PostgreSQL:** the bundled script upgrades an existing 0.10 database by itself
(`ALTER TABLE … ADD COLUMN IF NOT EXISTS`, `CREATE TABLE IF NOT EXISTS`, one-off backfills that
are safe to re-run), so `initialize-schema: always` is all that is needed. **MySQL** has no
`ADD COLUMN IF NOT EXISTS`, so apply the statements below once, in this order, before starting
0.11.0 with the bundled script; MariaDB users can use the H2/PostgreSQL form.

```sql
-- MySQL, in order. (PostgreSQL and H2: the bundled script runs the equivalent itself.)
ALTER TABLE AI_SESSION ADD COLUMN parent_session_id VARCHAR(255);
ALTER TABLE AI_SESSION ADD COLUMN root_session_id VARCHAR(255);
ALTER TABLE AI_SESSION MODIFY user_id VARCHAR(255) NOT NULL DEFAULT '';
ALTER TABLE AI_SESSION ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'IDLE';
ALTER TABLE AI_SESSION ADD COLUMN stop_reason VARCHAR(32);
ALTER TABLE AI_SESSION ADD COLUMN archived_at DATETIME(6);
CREATE INDEX idx_ai_session_parent_session_id ON AI_SESSION (parent_session_id);
CREATE INDEX idx_ai_session_root_session_id ON AI_SESSION (root_session_id);
UPDATE AI_SESSION SET root_session_id = id WHERE root_session_id IS NULL;   -- existing sessions are roots

ALTER TABLE AI_SESSION_EVENT ADD COLUMN event_type VARCHAR(32) NOT NULL DEFAULT 'MESSAGE';
ALTER TABLE AI_SESSION_EVENT ADD COLUMN payload LONGTEXT;
ALTER TABLE AI_SESSION_EVENT MODIFY message_type VARCHAR(20) NULL;

CREATE TABLE AI_SESSION_INDEX (
    session_id VARCHAR(255) NOT NULL,
    attr_name  VARCHAR(255) NOT NULL,
    attr_value VARCHAR(512) NOT NULL,
    PRIMARY KEY (session_id, attr_name),
    INDEX idx_ai_session_index_name_value (attr_name, attr_value),
    CONSTRAINT fk_ai_session_index_session
        FOREIGN KEY (session_id) REFERENCES AI_SESSION (id) ON DELETE CASCADE
);
-- Once: the owner of existing sessions becomes their principal attribute
INSERT INTO AI_SESSION_INDEX (session_id, attr_name, attr_value)
SELECT s.id, 'principalName', s.user_id FROM AI_SESSION s
WHERE s.user_id <> '' AND NOT EXISTS (SELECT 1 FROM AI_SESSION_INDEX i
                                      WHERE i.session_id = s.id AND i.attr_name = 'principalName');
```

The `user_id` index of 0.10 is no longer created by the bundled scripts; an existing one is
harmless and may be dropped.

See the bundled `schema-{h2,postgresql,mysql}.sql` for the full DDL per dialect.

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
  counts are archived, not removed. No deprecated alias.
- **Compaction never deletes.** A synthetic summary that a newer recursive summary replaces
  used to be removed from the log; it is now archived in place like the events it
  summarized, and `RecursiveSummarizationCompactionStrategy` lists it in
  `archivedEvents` (so `archivedEventCount()` includes it). Nothing leaves the log except
  through `delete` or `deleteExpiredSessions`.
- **`lastN` keeps turns whole.** A `lastN` window on one session is extended back to the
  start of the turn it lands in (the nearest preceding `USER` event among the matching
  events), so it never returns an assistant tool call without its results or a summary
  without its shadow prompt; the result can therefore hold more than N events. Text
  searches, pages and cross-session reads stay plain. `SessionEvent.isTurnStart()` marks
  the turn starts; `EventFilter.applyTurnAwareWindow` is the reference rule. See
  [Windows keep turns whole](session-management/event-filtering.md#windows-keep-turns-whole).
- **`SessionMemoryAdvisor` keeps tool calls answerable** (#44). On read it drops a tool
  call or tool result that has no counterpart in the prompt, with a `WARN`; on write it
  stores a trailing tool response exactly when the tool call it answers was stored,
  overriding the `MessageFilter` either way with a `WARN`. Under a `lastN` or page window
  the stored system prompt is looked up separately instead of being dropped. See
  [Tool-call integrity](chat-client/chat-client.md#tool-call-integrity).
- **Recall tools skip synthetic events.** `conversation_search` (`SessionEventTools`) and
  `CrossSessionRecallTools` exclude synthetic summaries from their results, since a
  summary only paraphrases real events that are still in the log. Build your own
  `EventFilter` to search summaries.
- **Every `USER` event starts a turn.** Turn counting (`TurnCountTrigger`) and every
  strategy's turn boundaries now use all non-synthetic `USER` events, and the event-count
  strategies (`maxEvents`) count all real events. Events that were
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
  instead of one query per session plus in-memory paging.
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
    - insert each `plan.inserts()` group immediately before its `beforeEventId` anchor,
      or at the end of the log when the anchor is `null`;
    - never move or remove an existing event.

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
- **A `lastN` window must keep turns whole.** When the oldest event of the window is not
  a `USER` event, extend the window back to the nearest preceding `USER` event among the
  matching events, unless the filter searches text or no such event exists. A list-based
  store gets this from `EventFilter.apply`; the JDBC repository runs one bounded extra
  query. The contract kit's window tests check it.
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
