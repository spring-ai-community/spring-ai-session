# Context Compaction

As conversations grow, they eventually exceed the model's context window. Compaction
reduces the session's event history to fit within that window while preserving
conversational coherence. It is driven by two composable abstractions: **triggers** (when
to compact) and **strategies** (how to compact).

---

## Entry point

`SessionService.compact()` is the single entry point. It evaluates the trigger first and
only runs the strategy — and writes back to the repository — when the trigger fires:

```java
// Compact when turn count exceeds 20, keeping the last 10 events
CompactionResult result = service.compact(
    sessionId,
    new TurnCountTrigger(20),
    SlidingWindowCompactionStrategy.builder().maxEvents(10).build()
);

System.out.println(result.eventsRemoved());        // derived: archivedEvents().size()
System.out.println(result.compactedEvents());      // the kept event list
System.out.println(result.archivedEvents());       // the archived (not deleted) event list
System.out.println(result.tokensEstimatedSaved()); // rough token saving estimate

// Compact unconditionally — pass an always-fire trigger
service.compact(sessionId, req -> true, SlidingWindowCompactionStrategy.builder().maxEvents(10).build());
```

!!! note "Archived, not deleted"
    Events in `archivedEvents()` are removed from the **active context window** but retained
    in the event log with `SessionEvent.isArchived() == true`. They are excluded from the
    prompt (`EventFilter.active()`) yet remain searchable through
    [Recall Storage](recall-storage.md) (`EventFilter.keywordSearch(...)`). Compaction never
    destroys conversation history.

!!! note "CAS write safety"
    `DefaultSessionService.compact()` reads the event-log version **before** fetching
    events. If another writer mutated the log between that read and the write,
    `compactEvents()` returns `false` and compaction is silently skipped — the concurrent
    writer already handled the session. No-op results skip the write entirely, important
    for production persistence backends.

---

## Compaction Triggers

Triggers implement `CompactionTrigger` (a `@FunctionalInterface`) and decide whether
compaction should run based on the current `CompactionRequest`.

### TurnCountTrigger

Fires when the session has more than `n` complete turns. Only non-synthetic, root-level
(`branch == null`) `USER` events count toward the turn total.

```java
new TurnCountTrigger(20);  // compact when > 20 turns
```

### TokenCountTrigger

Fires when the estimated total token count is at or above a threshold.

```java
// Uses JTokkitTokenCountEstimator by default
TokenCountTrigger.builder().threshold(4000).build();

// Custom estimator (e.g. for a different model's tokenizer)
TokenCountTrigger.builder().threshold(4000).tokenCountEstimator(myEstimator).build();
```

### CompositeCompactionTrigger

Combines multiple triggers with OR semantics — compaction fires if **any** trigger fires.

```java
CompactionTrigger trigger = CompositeCompactionTrigger.anyOf(
    new TurnCountTrigger(20),
    TokenCountTrigger.builder().threshold(4000).build()
);
```

---

## Compaction Strategies

Strategies implement `CompactionStrategy` (a `@FunctionalInterface`) and define what to
do with the event history. Each strategy receives a `CompactionRequest` containing the
session metadata and the full event list.

### Token accounting

All four strategies estimate the token cost of archived events using the same event
formatter. Tool calls and tool responses contribute their full formatted representation —
not just the raw `getText()` content, which is `null` for both types.

| Message type | Formatted as |
|---|---|
| `UserMessage` / `AssistantMessage` / `SystemMessage` | `User: <text>` / `Assistant: <text>` / `System: <text>` |
| `AssistantMessage` with tool calls | `Assistant [tool calls: name(args), ...]` |
| `ToolResponseMessage` | `Tool [responses: name -> data, ...]` |

This ensures `tokensEstimatedSaved` in `CompactionResult` accurately reflects the full
cost of removed events, including tool-heavy turns.

`RecursiveSummarizationCompactionStrategy` uses the same formatter when building the
summarization prompt sent to the LLM, and exposes an `eventFormatter` builder option to
override it (see below).

---

### SlidingWindowCompactionStrategy

Keeps the last `N` **real** events. Simple, predictable, no LLM call required. Synthetic
summary events are always preserved and placed first; they do not count against the
`maxEvents` budget.

```java
// keep the last 20 real events
SlidingWindowCompactionStrategy.builder().maxEvents(20).build();

// custom token estimator
SlidingWindowCompactionStrategy.builder().maxEvents(20).tokenCountEstimator(myEstimator).build();
```

**Algorithm**

1. Separate synthetic events (always preserved, placed first in output).
2. Keep the last `maxEvents` real events.
3. Snap the cut point forward to the nearest root-level `USER` message (turn-boundary safety).
4. Return: `[synthetics] + [kept real events]`.

### TurnWindowCompactionStrategy

Keeps the last `N` complete turns. Unlike the sliding window, this never cuts inside a
turn — it always archives whole user↔agent exchanges.

```java
// keep the last 10 turns
TurnWindowCompactionStrategy.builder().maxTurns(10).build();

// custom token estimator
TurnWindowCompactionStrategy.builder().maxTurns(10).tokenCountEstimator(myEstimator).build();
```

**Algorithm**

1. Strip synthetic events (always preserved, placed first in output).
2. Collect preamble events that appear before the first `USER` message.
3. Group remaining events into turns (each turn starts at a `USER` message).
4. Archive the oldest turns until only `maxTurns` remain.
5. Return: `[synthetics] + [preamble] + [kept turns]`.

### TokenCountCompactionStrategy

Keeps a **contiguous** suffix of events that fits within a token budget, walking from
newest to oldest.

```java
// stay within 4000 tokens
TokenCountCompactionStrategy.builder().maxTokens(4000).build();

// custom estimator
TokenCountCompactionStrategy.builder().maxTokens(4000).tokenCountEstimator(myEstimator).build();
```

**Algorithm**

1. Separate synthetic events (their token cost is deducted from the budget first).
2. Walk real events from newest to oldest, accumulating token cost (estimated via the
   shared event formatter — see [Token accounting](#token-accounting) above). Stop at the
   first event that would exceed the remaining budget. This produces a **contiguous
   suffix** — skipping individual oversize events would create non-contiguous gaps that
   break conversation coherence.
3. Drop any leading kept events that are not root-level `USER` messages (turn-boundary safety).
4. Return: `[synthetics] + [kept events]`.

### RecursiveSummarizationCompactionStrategy

LLM-powered strategy that uses a `ChatClient` to summarize the events being archived.
The summary is stored as a synthetic user+assistant turn so subsequent compaction passes
can build on it — creating a rolling, recursive compressed history.

```java
RecursiveSummarizationCompactionStrategy strategy =
    RecursiveSummarizationCompactionStrategy.builder(chatClient)
        .maxEventsToKeep(10)           // active window size (real events kept intact)
        .overlapSize(2)                // events from active window fed to summary prompt
                                       // must be < maxEventsToKeep; defaults to 2
        .systemPrompt("...")           // optional custom system prompt
        .shadowPrompt("...")           // optional custom USER shadow prompt
        .tokenCountEstimator(myEst)   // custom estimator (default: JTokkitTokenCountEstimator)
        .eventFormatter(myFormatter)  // optional custom event-to-text renderer (see below)
        .build();
```

!!! note "Builder validation"
    `overlapSize` must be `>= 0` and strictly less than `maxEventsToKeep`. Violating this
    throws `IllegalArgumentException`.

**LLM failure handling**

If the LLM returns a null or blank summary, the strategy logs a `WARN`-level message and
skips compaction — the event history is left unchanged. Register an optional failure
callback to react programmatically:

```java
RecursiveSummarizationCompactionStrategy strategy =
    RecursiveSummarizationCompactionStrategy.builder(chatClient)
        .maxEventsToKeep(10)
        .onSummarizationFailure(req -> {
            log.error("Compaction failed for session {}", req.session().id());
            // retry, alert, increment a metric, etc.
        })
        .build();
```

**Custom event formatter**

The strategy uses the shared event formatter (see [Token accounting](#token-accounting))
when building the summarization prompt, so tool call names, arguments, and response data
are all visible to the LLM. Override it via `eventFormatter` for domain-specific rendering
or multilingual summaries:

```java
RecursiveSummarizationCompactionStrategy strategy =
    RecursiveSummarizationCompactionStrategy.builder(chatClient)
        .maxEventsToKeep(10)
        .eventFormatter(event -> switch (event.getMessageType()) {
            case TOOL -> "Tool result: " + event.getMessage().getText();
            default   -> RecursiveSummarizationCompactionStrategy.formatEvent(event);
        })
        .build();
```

**Algorithm**

1. Separate synthetic and real events.
2. Compute the raw cut point: the newest `maxEventsToKeep` real events form the active window.
3. Snap the cut point forward to the nearest turn boundary.
4. Feed `[prior synthetic summaries] + [events to archive] + [overlap events]` to the LLM.
5. Replace the archived events with a new synthetic summary turn `[USER shadow, ASSISTANT summary]`.
6. Return: `[summary turn] + [active window]`.

The **recursive** property: the `ASSISTANT` text from any prior synthetic summary is fed
back to the LLM as `=== PRIOR SUMMARY ===` context, so each summary builds on its
predecessors without starting from scratch.

---

## Turn-boundary Safety

All four strategies share a common safety rule enforced by
`CompactionUtils.snapToTurnStart`: the kept window always starts at a **root-level**
`USER` message — one whose `branch` is `null`.

If a raw cut point lands in the middle of a turn, it is advanced forward to the next
qualifying event. This prevents keeping a tool result or assistant reply without the user
message that originated its turn.

```
Before snap:  [u1, a1, u2, a2, | a3, u3, a3]   ← cut lands on a3 (middle of turn 2)
After snap:   [u1, a1, u2, a2, a3, | u3, a3]   ← cut moved to u3 (turn start)
```

### Branch-awareness in multi-agent sessions

In multi-agent sessions, `UserMessage` events appear on named branches (e.g.
`branch="orch.researcher"`) as well as at the root level. A branched `UserMessage` is the
prompt sent *to* a sub-agent — it is **turn-internal**, not a turn boundary.

A single root turn can contain an entire sub-agent exchange:

```
[branch=null]  USER:      "What's the weather in Paris?"      ← real turn start
[branch=null]  ASSISTANT: [tool call: delegate_to_agent]
[branch="sub"] USER:      "Fetch weather for Paris"           ← internal sub-agent prompt
[branch="sub"] ASSISTANT: [tool call: get_weather]
[branch="sub"] TOOL:      {temp: "22C"}
[branch="sub"] ASSISTANT: "It's 22°C in Paris"
[branch=null]  ASSISTANT: "The weather in Paris is 22°C"
```

`snapToTurnStart` skips all branched events regardless of message type, and only stops
when it finds a `USER` event with `branch == null` (i.e. `SessionEvent.isRootEvent()`).
This prevents the cut from landing on a sub-agent prompt and leaving the root turn's user
message in the archived window.

---

## Choosing a strategy

| Strategy | LLM call? | Context preserved | Best for |
|---|---|---|---|
| `SlidingWindowCompactionStrategy` | No | Last N messages verbatim | Cost-sensitive, short-term context |
| `TurnWindowCompactionStrategy` | No | Last N complete turns verbatim | Turn-structured dialogues |
| `TokenCountCompactionStrategy` | No | Token-budget suffix verbatim | Hard context window limits |
| `RecursiveSummarizationCompactionStrategy` | Yes | Rolling LLM summary + active window | Long-running, context-rich sessions |
