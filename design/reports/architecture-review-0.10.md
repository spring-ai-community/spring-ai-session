# Architecture review of spring-ai-session 0.10.0

*Maintainer review. Status: proposed. Date: 2026-09-28. Code reviewed: `main` at `ea009ee`
(after #54, #56, #58, #59 and #60).*

## 1. Summary

The design is sound, and the 0.9.0 and 0.10.0 work fixed the real bugs. What remains is
structural: the code carries more public surface than it needs, the four compaction
strategies repeat one skeleton, and, above all, a third-party backend has to re-implement
subtle logic that belongs in core. The Redis (#45) and MongoDB (#27) pull requests both
copy the message codec, the paging and the compaction merge from the JDBC repository, and
both get the in-place ordering rules wrong.

Compared with eight other conversation-memory systems (§4), the event-sourced log with
archive-in-place and a version compare-and-swap is unusual and a strength. Two things the
field does that this library doesn't: treating an assistant tool call and its tool results
as one atomic unit everywhere (not only at compaction cuts), and pruning the *in-flight*
prompt inside a tool loop without touching the log.

Recommended order: §3.1 (backend kit) and §3.3 (tool-group atomicity) first, because they
affect the open community PRs and issue #44; §3.2, §3.4 and §3.5 are pre-1.0 API cleanups
that fit one refactoring release; §3.6 and §3.7 are small and can go anytime.

## 2. What to keep

- **The event model:** an append-only log, archive-in-place with the `archived` flag, a
  monotonic `seq`, and the version-then-read compare-and-swap in `compactEvents`. Only
  Google ADK has anything comparable (its stale-session check); Spring AI upstream,
  LangChain4j and the Vercel AI SDK have no versioning at all.
- **Idempotent append by event id**, including the 0.10.0 change that detects a replay
  before inserting, so it can't poison a caller's transaction.
- **Trigger + strategy** as two functional interfaces, with `CompositeCompactionTrigger`.
  Microsoft Agent Framework (trigger/target) and LangChain's summarization middleware
  (`trigger`/`keep`) make the same split.
- **Turn-boundary cutting** and "the newest turn is always kept", and, since #56,
  "compaction never reorders the log".
- **System messages as configuration:** opt-in storage, latest wins, byte-stable prompt
  order, and an error message that says what to do instead.
- **Best-effort compaction** that never fails the user's request and waits for the end of
  the turn.
- **The recall tools' safety properties:** the user is fixed at construction time, and no
  tool accepts a raw regular expression.
- **The JDBC details:** `seq` ordering rather than timestamps, `LIKE` escaping with
  `ESCAPE '!'`, the UTC `LocalDateTime` binding, the Boot schema-initialization
  integration, and re-runnable DDL scripts.

## 3. Recommendations

Each item gives what to change, why, the cost, and a sketch. "Breaking" means a public API
change; the project is pre-1.0, so breaking changes are acceptable with a migration note.

### 3.1 Make third-party backends cheap to write (high value, low cost, additive)

**Problem.** A `SessionRepository` implementer must reproduce three pieces of core logic:

| Logic | Where it lives today | Copied by |
|---|---|---|
| Message ↔ `type/content/data` codec (`messageDataToJson`, `toMessage`, `parseToolCalls`) | `JdbcSessionRepository` | Redis and MongoDB PRs |
| `lastN` / `page` slicing after filtering | `JdbcSessionRepository` and `InMemorySessionRepository` | Redis PR (`applyPagination`) |
| The compaction merge: flag in place, drop, insert-before | `InMemorySessionRepository.compactedLog` and `JdbcSessionRepository.applyRetainedEvents` | both PRs, incorrectly |

The contract in `SessionRepository.compactEvents` also doesn't say that the archived events
are **not** a prefix of the log. Since #56 a kept system message can precede archived
events, and the MongoDB PR assumes the prefix.

**Changes.**

1. **Shared helpers in core.**
   - `SessionEventCodec`: the persisted shape of a message (type, text, tool calls, tool
     responses; later metadata and media, see #53). One place to decide what every backend
     stores.
   - `EventFilter.apply(List<SessionEvent>)`: match, then `lastN` / `page` slicing.
     Document that a backend may push any subset of the filter down to its query and must
     finish with `apply`, which is what the JDBC repository already does for `pattern`.
   - The log merge as a public helper, e.g. `SessionEventLog.applyCompaction(log,
     archived, retained)`.
2. **A precomputed compaction plan.** `DefaultSessionService` already holds the active
   log at the version it will compare-and-swap against, so it can compute everything the
   repository needs:

   ```java
   record CompactionPlan(Set<String> archiveIds, Set<String> deleteIds, List<Insert> inserts) {}
   record Insert(@Nullable String beforeEventId, List<SessionEvent> events) {}

   // SessionRepository
   boolean applyCompaction(String sessionId, CompactionPlan plan, long expectedVersion);
   ```

   The repository then only archives ids, deletes ids and inserts before an anchor. The
   JDBC `SELECT_ACTIVE_EVENT_IDS` re-read disappears, and a backend cannot misread the
   ordering rules. Keep `compactEvents(archived, retained, version)` as a default method
   that builds the plan, for one release.
3. **A repository test kit.** Publish an abstract `SessionRepositoryContractTests` in a
   test jar, built from the scenarios in `DialectScenarios`: idempotent append, version
   rules, insert-before placement, `saveIfAbsent` atomicity, expiry, keyword escaping.
   The in-memory, H2, PostgreSQL and MySQL tests each subclass it, which also removes the
   one-line delegating tests in the two Testcontainers ITs. The Claude Agent SDK ships
   exactly this for its `SessionStore` adapters.
4. **Contract trimming.** Make `saveIfAbsent` and `deleteExpiredSessions` abstract before
   1.0 (their non-atomic defaults invite wrong implementations); drop
   `findExpiredSessionIds`, which has no caller left; consider paging `findByUserId`.

### 3.2 Collapse the four strategies into window + optional summarizer (high value, medium cost, breaking with deprecation)

**Problem.** All four strategies run the same skeleton: split events, compute a raw cut,
`snapToTurnStart`, `retainLastTurn`, `archiving`. The windowing in
`RecursiveSummarizationCompactionStrategy` is a copy of `SlidingWindowCompactionStrategy`'s,
so summarization cannot be combined with a turn window or a token budget. Two leftovers
show the drift: unused `synthetic` locals in the sliding-window and turn-window strategies,
and every strategy constructing its own `JTokkitTokenCountEstimator` only to report
`tokensEstimatedSaved`.

**Sketch.**

```java
@FunctionalInterface
interface CompactionWindow {
    int rawCut(List<SessionEvent> real, ToIntFunction<SessionEvent> tokens, int preservedTokens);
    static CompactionWindow lastEvents(int n) { ... }
    static CompactionWindow lastTurns(int n) { ... }
    static CompactionWindow tokenBudget(int maxTokens) { ... }
}

interface Summarizer {
    @Nullable String summarize(List<SessionEvent> priorSummaries, List<SessionEvent> toSummarize,
            List<SessionEvent> overlap);
}

final class WindowedCompactionStrategy implements CompactionStrategy {
    // window + optional summarizer + optional estimator (only needed for tokenBudget / savings)
}
```

Keep the four classes as thin deprecated factories for one release. Make
`tokensEstimatedSaved` optional or lazy so a plain window needs no estimator.

### 3.3 Tool-group atomicity and in-flight pruning (high value, medium cost)

**Problem.** Every other framework treats "assistant tool call + its tool results" as one
unit: Microsoft Agent Framework's `MessageGroup`, LangChain's `_find_safe_cutoff_point`,
the OpenAI Agents SDK's `drop_orphan_function_calls`, LangChain4j's orphan eviction. Here
that holds only at compaction cuts (which snap to a `USER` event). `EventFilter.lastN`
can split a tool call from its result, or the shadow prompt from its summary; a
`messageTypes` filter can drop tool messages; a `MessageFilter` can skip them. Each
produces a prompt that providers reject: this is the likely root of #44 and touches #11.

Related: MAF and LangChain also prune the **in-flight** prompt before every model call
inside a tool loop, without changing the log. Here a long single turn (a tool loop with
many rounds) can overflow the context before compaction is allowed to run, because
compaction now waits for the turn to end (#58).

**Changes.**

1. A shared `TurnGroups` primitive (user turn, tool-call group, summary pair) used by the
   window strategies **and** by `lastN`, so no read or cut splits a group.
2. A cheap strategy that replaces old tool-call groups with a stub line
   (`[Tool calls: get_weather]`), as MAF's `ToolResultCompactionStrategy` does, and a
   pipeline that runs gentle → summarize → window with an early stop.
3. An optional in-flight pruner on the advisor: trims what is sent to the model in this
   round, leaving the log untouched; the post-turn compaction stays as it is.

### 3.4 Slim `EventFilter` (medium value, medium cost, breaking)

**Problem.** One 12-component record mixes an event predicate (`from`, `to`,
`messageTypes`, `excludeSynthetic`, `excludeArchived`), text search (`keyword`,
`keywords`, `matchMode`, `pattern`) and a retrieval window (`lastN`, `page`, `pageSize`).
`keyword` is a one-element `keywords`. `pattern` cannot be pushed to a database, so JDBC
filters in memory and defers paging. Five validation rules and a special case in `merge`
exist only for the window fields.

**Sketch.** A sealed retrieval window `All | Last(n) | Page(p, size)`; `keyword` folded into
`keywords`; `pattern` applied in the service as a post-filter (or dropped from the
repository contract). Also reconsider the default view: `getEvents(id)` and
`getMessages(id)` return **all** events, archived included; the active view is the safer
default, with recall callers asking for "all" explicitly.

### 3.5 Shrink `SessionMemoryAdvisor` (medium value, low cost, no API break)

At 628 lines it carries session creation, the ownership check, filter merging, prompt
assembly, the tool-loop check, persistence and compaction. Changes:

- Move find-or-create (with its race handling) and the ownership check into
  `SessionService.findOrCreate(id, userId)`; note that the check is skipped whenever
  `USER_ID_CONTEXT_KEY` is absent, which should be a documented policy.
- Extract prompt assembly (history merge, latest-wins, reorder, dedupe, the
  "history already in prompt" check) into a package-private `PromptHistoryAssembler`
  that is unit-testable without a `ChatClient`.
- Pair `compactionTrigger` and `compactionStrategy`, which must be set together, in one
  `CompactionPolicy` value.
- Collapse `SessionEventRequestIdGenerator` and `SessionEventResponseIdGenerator` into one
  `SessionEventIdGenerator(Map<String, Object> context, Message message)`: both
  implementations only read the context map and the message, and the response context
  carries the request context forward.
- Consider MAF's approach to the tool-loop problem: stamp history messages with a
  provenance marker and skip them when persisting, instead of matching contiguous runs by
  content.
- Rename the value of `EVENT_FILTER_CONTEXT_KEY` (`"chat_memory_event_filter_id"`); it is
  not an id.

### 3.6 JDBC and configuration (medium value, low cost)

- **Locking in `appendEvent`:** replace the increment-then-decrement with
  `SELECT event_version FROM AI_SESSION WHERE id = ? FOR UPDATE`, then check the owner,
  insert, and increment. One statement fewer, no compensating write, portable across H2,
  PostgreSQL and MySQL.
- **Dialect SPI:** `getKeywordFilterFragment()` returns the same string in all three
  dialects and duplicates the default `getKeywordPredicateFragment()`; keep only the
  predicate. Only the upsert and insert-if-absent statements really vary per database.
- **Schema:** consider a composite event key `(session_id, id)` plus a per-session `seq`
  assigned under the session row lock. That gives one DDL shape (no `IDENTITY` /
  `AUTO_INCREMENT` differences), keeps the id contract per session, and with strided
  values would let a summary be inserted without re-inserting the tail. Drop the unused
  `branch` column in a later release.
- **Auto-configuration:** let backends order themselves with
  `@AutoConfiguration(before = SessionServiceAutoConfiguration.class)` instead of every
  backend PR editing the core auto-config; use Boot's `JdbcTemplate` and
  `PlatformTransactionManager` beans via `ObjectProvider`; make the schema initializer
  conditional on the JDBC repository being the active one; add an optional expiry-sweep
  scheduler and `spring.ai.session.compaction.*` properties, since users wire both by hand
  today.
- **Splitting `JdbcSessionRepository` (~850 lines):** an event mapper (the codec of §3.1)
  and a small query builder are the useful cuts; a session-store / event-store split is
  not, because append and compaction need both tables in one transaction.

### 3.7 Small cleanups (low cost)

- `@since 2.0.0` on 24 types (one says `0.7.0`); the project is 0.10.0.
- `Session` Javadoc promises "mutations return new instances", but `Session` has no
  mutators; there is also no service-level way to extend a session's TTL.
- `CompactionStrategy` Javadoc mentions "token estimates" the request doesn't carry.
- `synthetic` is read from the metadata map while `archived` is a field and JDBC already
  stores a `synthetic` column; promote it to a field.
- Accessor style: `id()` on `Session` and `CreateSessionRequest`, `getId()` on
  `SessionEvent`; pick one.
- `CompactionRequest.currentEventCount` always equals `events.size()`.
- The two recall tools duplicate page normalization, JSON formatting and builder
  validation, and disagree on errors (`conversation_search` returns an error string and
  accepts a blank query; `cross_session_search` throws and rejects it). Extract a shared
  query parser and result formatter. Also: pages are cut before events without text are
  filtered out, and the summary's shadow prompt matches searches.
- Missing `@Nullable` on optional tool parameters and on `CreateSessionRequest`
  constructor parameters; no null checks on the advisor's `compactionTrigger` /
  `compactionStrategy` setters; `DefaultSessionService.Builder` doesn't check that a
  repository was set.
- `Session.Builder` duplicates the 60-day default TTL of `DefaultSessionService`.

### 3.8 The synthetic summary pair: keep, but tie it together

The user + assistant pair is unique to this library (§4). LangChain stores one tagged human
message, ADK one model-authored compaction event, Claude Code a `compact_boundary` marker.
The pair guarantees user/assistant alternation for strict providers, which is a fair reason
to keep it, but nothing ties the two events together, so `lastN` can separate them, and
the legacy `SYSTEM`-summary path is still a second code path in the advisor and the
recursive strategy. Tag both events with a shared metadata key, make windows and `lastN`
respect it (§3.3), and drop the legacy path before 1.0.

## 4. How other systems do it

Verified against source or official docs on 2026-09-28; the appendix lists the sources.

| System | Store shape | Reads | Trimming / summarization | Concurrency | Tool-group integrity | Backend SPI size |
|---|---|---|---|---|---|---|
| **spring-ai-session** | Event log, archive in place, `seq` | `EventFilter` (types, time, keywords, pattern, lastN, page) | Post-turn, sync; trigger + strategy; summary = synthetic user + assistant pair | Version CAS | At compaction cuts only | 11 methods incl. CAS compaction |
| Spring AI upstream | Message list, `saveAll` overwrites | Whole conversation | `MessageWindowChatMemory` deletes; cut moves to next user message; none built-in for summaries | None | Via the user-message cut | 4 methods |
| LangChain4j | Message list, `updateMessages` overwrites | Whole conversation | Message / token window, destructive; orphan tool results evicted with their call | None | Yes (orphan eviction) | 3 methods |
| LangGraph / LangChain v1 | Checkpoint per super-step, parent links | `get_tuple`, `list` | `trim_messages` on the request; `SummarizationMiddleware` before each model call, summary = one human message, safe cutoff keeps tool groups | Checkpoint lineage | Yes (`_find_safe_cutoff_point`) | put / put_writes / get_tuple / list / delete_thread |
| OpenAI Agents SDK | Item list | `get_items(limit)` | `OpenAIResponsesCompactionSession` decorator after the run; server-side compact; replaces history | Lock | Yes (`drop_orphan_function_calls`) | 4 methods |
| Google ADK | Append-only events + state | `num_recent_events`, `after_timestamp` | Sliding or token-based after the invocation; **appends** a compaction event, originals kept; overlap | `StaleSessionError` (optimistic) | Not specific | Session service (create/get/append/list/delete) |
| Letta (MemGPT) | Core blocks in the prompt + recall (all messages) + archival (hybrid search) | `conversation_search`, archival search | Sliding window / self-compact when context overflows; old messages retrievable | – | – | – |
| Microsoft Agent Framework | `ChatHistoryProvider`: provide + store (new messages only); provenance-stamped | Whole history | `MessageGroup` units; trigger + target; pipeline gentle→aggressive; `ToolResultCompactionStrategy`; runs before each model call (request only) or as a reducer on stored history | – | Yes (`MessageGroup`) | 2 methods |
| Vercel AI SDK | `UIMessage[]` saved at the end | Whole chat | `pruneMessages` on the request (reasoning, tool calls) | – | Per-tool pruning options | `saveChat` / `loadChat` |
| Claude Agent SDK / Claude Code | Append-only JSONL transcript | `load`, post-compaction chain | Auto-compact near the limit, `compact_boundary` marker, `PreCompact` hook; raw log kept | Dedupe on entry uuid | – | `append` + `load` required; conformance suite provided |

**What is unusual here:** the version compare-and-swap, the filtered reads, the synthetic
user + assistant summary pair, keyword-only recall, and a built-in TTL sweep (most systems
leave retention to the store).

**What matches good practice:** append-only with soft archive (ADK, Letta, Claude Code),
turn-boundary cutting (Spring AI, MAF, LangChain), trigger + strategy (MAF, LangChain),
moving system messages first and deduping history in tool loops (Spring AI upstream).

## Appendix: sources

- Spring AI upstream: `spring-ai-model/.../chat/memory/ChatMemoryRepository.java`,
  `MessageWindowChatMemory.java`, `MessageChatMemoryAdvisor.java` (local checkout, 2.1.0-SNAPSHOT)
- LangChain4j chat memory: https://docs.langchain4j.dev/tutorials/chat-memory
- LangGraph checkpointers: https://docs.langchain.com/oss/python/langgraph/checkpointers;
  memory: https://docs.langchain.com/oss/python/langgraph/add-memory;
  langmem: https://langchain-ai.github.io/langmem/reference/short_term/
- OpenAI Agents SDK sessions: https://openai.github.io/openai-agents-python/sessions/
- Google ADK sessions: https://adk.dev/sessions/session/; compaction: https://adk.dev/context/compaction/
- Letta: https://docs.letta.com/guides/agents/base-tools
- Microsoft Agent Framework compaction:
  https://learn.microsoft.com/en-us/agent-framework/concepts/agents/conversations/compaction;
  storage: https://learn.microsoft.com/en-us/agent-framework/concepts/agents/conversations/storage
- Vercel AI SDK: https://ai-sdk.dev/docs/ai-sdk-ui/chatbot-message-persistence;
  https://ai-sdk.dev/docs/reference/ai-sdk-ui/prune-messages
- Claude Agent SDK: https://code.claude.com/docs/en/agent-sdk/session-storage;
  https://code.claude.com/docs/en/agent-sdk/agent-loop
- Community PRs reviewed for backend needs: #45 (Redis), #27 (MongoDB)
