# Context Compaction

As conversations grow, they eventually exceed the model's context window. Compaction
reduces the session's event history to fit within that window while preserving
conversational coherence. It is driven by two composable abstractions: **triggers** (when
to compact) and **strategies** (how to compact).

!!! tip "How it works internally"
    For class, sequence and activity diagrams of the compaction algorithms, and worked
    examples of the tricky cases, see [Compaction Internals](compaction-internals.md).

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

- **Archived, not deleted.** Archived events leave the prompt but stay in the log, and
  remain searchable through [Recall Storage](../recall-memory/recall-storage.md). The only events
  compaction removes are superseded synthetic summaries, replaced by a newer summary that
  builds on them. See [Event lifecycle](concepts.md#event-lifecycle).
- **Concurrent writes are safe.** The write is version-checked: if another writer changed
  the log during the pass, compaction is silently skipped, and a no-op result skips the
  write entirely. See the [compaction pass sequence](compaction-internals.md#2-sequence-a-compaction-pass-end-to-end)
  and the [JDBC concurrency diagram](compaction-internals.md#5-sequence-jdbc-compactevents-and-a-concurrent-append).
- **Stored system messages are configuration.** If you store them (opt-in), the latest one
  of each branch is always kept, placed first, never summarized and not counted against
  `maxEvents` / `maxTurns` / `maxEventsToKeep`; earlier ones are archived on every pass. See
  [System Messages](system-messages.md#compaction-the-latest-stored-system-message-wins).

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

Fires when the estimated total token count is at or above a threshold (`threshold` is
required). It uses the same [token accounting](#token-accounting) as the strategies, so tool
calls and responses count, and it counts the same events as
`TokenCountCompactionStrategy`'s [budget](#how-the-budget-is-spent): the root agent's latest
stored system message, the summaries and the conversation.

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

Strategies implement `CompactionStrategy` (a `@FunctionalInterface`). Each receives a
`CompactionRequest` with the session and its active events, and returns what to keep.

| Strategy | LLM call? | Context preserved | Best for |
|---|---|---|---|
| `SlidingWindowCompactionStrategy` | No | Last N messages verbatim | Cost-sensitive, short-term context |
| `TurnWindowCompactionStrategy` | No | Last N complete turns verbatim | Turn-structured dialogues |
| `TokenCountCompactionStrategy` | No | Token-budget suffix verbatim | Hard context window limits |
| `RecursiveSummarizationCompactionStrategy` | Yes | Rolling LLM summary + active window | Long-running, context-rich sessions |

**Common behaviour.** Every strategy:

- keeps the latest stored system message of each branch and the synthetic summary events,
  places them first, and archives earlier stored system messages;
- counts only root-level (`branch == null`) real events against its limit; sub-agent
  events stay with the root turn that contains them;
- starts the kept window at a root-level `USER` message and always keeps the most recent
  turn (see [Turn-boundary Safety](#turn-boundary-safety));
- returns `[system messages] + [synthetics] + [kept events]`.

### Token accounting

All four strategies — and `TokenCountTrigger` — estimate token cost using the same event
formatter. Tool calls and tool responses contribute their full formatted representation,
not the raw `getText()`, which is `null` for both types:

| Message type | Formatted as |
|---|---|
| `UserMessage` / `AssistantMessage` / `SystemMessage` | `User: <text>` / `Assistant: <text>` / `System: <text>` |
| `AssistantMessage` with tool calls | `Assistant [tool calls: name(args), ...]`, or `Assistant: <text> [tool calls: ...]` when it also has text |
| `ToolResponseMessage` | `Tool [responses: name -> data, ...]` |

This keeps `tokensEstimatedSaved` accurate for tool-heavy turns.
`RecursiveSummarizationCompactionStrategy` also uses it to build the summarization prompt.

---

### SlidingWindowCompactionStrategy

Keeps the last `N` **root-level real** events (default `N` = `DEFAULT_MAX_EVENTS` = 20).
Simple, predictable, no LLM call required.

```java
// keep the last 20 real events
SlidingWindowCompactionStrategy.builder().maxEvents(20).build();

// custom token estimator
SlidingWindowCompactionStrategy.builder().maxEvents(20).tokenCountEstimator(myEstimator).build();
```

It keeps the last `maxEvents` root-level real events, then snaps the cut forward to the
next root-level `USER` message.

### TurnWindowCompactionStrategy

Keeps the last `N` complete turns (default `N` = `DEFAULT_MAX_TURNS` = 10). Unlike the
sliding window, this never cuts inside a turn — it always archives whole user↔agent
exchanges.

```java
// keep the last 10 turns
TurnWindowCompactionStrategy.builder().maxTurns(10).build();

// custom token estimator
TurnWindowCompactionStrategy.builder().maxTurns(10).tokenCountEstimator(myEstimator).build();
```

It groups events into turns (each starting at a root-level `USER` message) and archives
the oldest until `maxTurns` remain. Events before the first root-level `USER` message form
a **preamble** that is always kept, placed after the synthetics and before the turns.

### TokenCountCompactionStrategy

Keeps a **contiguous** suffix of events that fits within a token budget (default
`DEFAULT_MAX_TOKENS` = 4000), walking from newest to oldest.

```java
// stay within 4000 tokens
TokenCountCompactionStrategy.builder().maxTokens(4000).build();

// custom estimator
TokenCountCompactionStrategy.builder().maxTokens(4000).tokenCountEstimator(myEstimator).build();
```

It walks real events from newest to oldest and stops at the first event that would exceed
the remaining budget. The result is a **contiguous suffix**: skipping individual oversize
events would leave gaps that break conversation coherence. Leading kept events that are
not root-level `USER` messages are then dropped.

#### How the budget is spent

The preserved events that are sent with the conversation take their tokens off
`maxTokens` before any conversation is considered:

```
remainingBudget = maxTokens − tokens(root agent's kept system message + synthetic summary events)
```

Sub-agent system messages are kept too, but they are not deducted: each is sent only to
its own sub-agent, never with the root view of the conversation, so counting them would
shrink the conversation's budget for nothing. See
[worked example 7](compaction-internals.md#6-worked-examples-of-the-tricky-cases).

If the deducted events use up the whole budget (`remainingBudget ≤ 0`), for example a
very large stored root system prompt, only the newest turn is kept and every compaction
drops all older context. Size `maxTokens` so that the deducted events leave room for the
conversation, or keep system prompts out of the session (see
[System Messages](system-messages.md)).

### RecursiveSummarizationCompactionStrategy

LLM-powered strategy that uses a `ChatClient` to summarize the events being archived.
The summary is stored as a synthetic user+assistant turn so subsequent compaction passes
can build on it — creating a rolling, recursive compressed history.

```java
RecursiveSummarizationCompactionStrategy strategy =
    RecursiveSummarizationCompactionStrategy.builder(chatClient)
        .maxEventsToKeep(10)           // active window size: root-level real events kept
                                       // intact; defaults to 10
        .overlapSize(2)                // events from active window fed to summary prompt;
                                       // >= 0 and < maxEventsToKeep, else
                                       // IllegalArgumentException; defaults to 2
        .systemPrompt("...")           // optional custom system prompt
        .shadowPrompt("...")           // optional custom USER shadow prompt; defaults to
                                       // DEFAULT_SUMMARY_SHADOW_PROMPT
        .tokenCountEstimator(myEst)   // custom estimator (default: JTokkitTokenCountEstimator)
        .eventFormatter(myFormatter)  // optional custom event-to-text renderer (see below)
        .build();
```

!!! warning "Use a separate ChatClient for summarization"
    Don't pass a `ChatClient` that has `SessionMemoryAdvisor` among its default advisors.
    The summarization call has no session ID in its advisor context, so the advisor would
    reject it with `IllegalStateException`. Build a plain `ChatClient` for the summarizer.

**Algorithm**

1. Compute the cut so that the newest `maxEventsToKeep` root-level real events form the
   active window, and snap it to a turn boundary. If that leaves nothing to summarize,
   stop without calling the LLM.
2. Feed `[prior synthetic summaries] + [events to archive] + [overlap events]` to the LLM.
   Stored system messages are never included.
3. Replace the archived events and the prior summaries with a new synthetic summary turn
   `[USER shadow, ASSISTANT summary]`.

The **recursive** property: the `ASSISTANT` text from any prior synthetic summary is fed
back to the LLM as `=== PRIOR SUMMARY ===` context, so each summary builds on its
predecessors without starting from scratch.

**LLM failure handling**

If the LLM returns a null or blank summary, the strategy logs a `WARN` and skips
compaction, leaving the history unchanged. If the LLM call throws, the exception propagates
out of `SessionService.compact(...)`. `SessionMemoryAdvisor` catches and logs it, so the
user's chat call still succeeds; if you call `compact(...)` yourself, handle it there.
Register a callback to react to a blank summary:

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

Override the [shared formatter](#token-accounting) via `eventFormatter` for domain-specific
rendering or multilingual summaries:

```java
RecursiveSummarizationCompactionStrategy strategy =
    RecursiveSummarizationCompactionStrategy.builder(chatClient)
        .maxEventsToKeep(10)
        .eventFormatter(event -> switch (event.getMessage()) {
            // ToolResponseMessage.getText() is null — render the response data instead
            case ToolResponseMessage trm -> "Tool result: " + trm.getResponses()
                .stream()
                .map(ToolResponseMessage.ToolResponse::responseData)
                .collect(Collectors.joining("; "));
            default -> RecursiveSummarizationCompactionStrategy.formatEvent(event);
        })
        .build();
```

---

## Turn-boundary Safety

All four strategies share a common safety rule: the kept window always starts at a
**root-level** `USER` message — one whose `branch` is `null`. The sliding-window,
token-count and recursive-summarization strategies snap their cut point forward to the
next such message (package-private `CompactionUtils.snapToTurnStart`);
`TurnWindowCompactionStrategy` gets the same result by grouping events into turns. This
prevents keeping a tool result or assistant reply without the user message that started
its turn.

```
Before snap:  [u1, a1, u2, a2, | a3, u3, a3]   ← cut lands on a3 (middle of turn 2)
After snap:   [u1, a1, u2, a2, a3, | u3, a3]   ← cut moved to u3 (turn start)
```

The full cut-point pipeline and eight worked examples are in
[Compaction Internals](compaction-internals.md#3-activity-how-a-strategy-chooses-what-to-archive).

### The most recent turn is always kept

If there is no later turn start to snap to, the cut is moved back to the start of the
most recent turn instead. This happens when the newest turn alone exceeds the budget, for
example a long tool-calling loop or a very large tool result. That turn stays active even
though it goes over `maxEvents` / `maxTokens` / `maxEventsToKeep`, so compaction never
archives the turn in progress. When the whole history is a single oversize turn, nothing
is archived (and `RecursiveSummarizationCompactionStrategy` makes no LLM call).

```
Budget: 2 events   [u1, a1, u2, a2, a3, a4]
Forward snap:      no USER after the cut → would archive everything
Kept instead:      [u1, a1, | u2, a2, a3, a4]   ← last turn kept, over budget
```

### Branch-awareness in multi-agent sessions

In multi-agent sessions, `UserMessage` events also appear on named branches (e.g.
`branch="orch.researcher"`). A branched `UserMessage` is the prompt sent *to* a sub-agent:
it is **turn-internal**, not a turn boundary. A single root turn can contain an entire
sub-agent exchange:

```
[branch=null]  USER:      "What's the weather in Paris?"      ← real turn start
[branch=null]  ASSISTANT: [tool call: delegate_to_agent]
[branch="sub"] USER:      "Fetch weather for Paris"           ← internal sub-agent prompt
[branch="sub"] ASSISTANT: [tool call: get_weather]
[branch="sub"] TOOL:      {temp: "22C"}
[branch="sub"] ASSISTANT: "It's 22°C in Paris"
[branch=null]  ASSISTANT: "The weather in Paris is 22°C"
```

`snapToTurnStart` skips all branched events and stops only at a `USER` event with
`branch == null` (`SessionEvent.isRootEvent()`), so the cut never lands on a sub-agent
prompt and leaves the root turn's user message archived.
