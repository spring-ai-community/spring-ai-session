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
only runs the strategy when the trigger fires. The strategy's result is turned into a
`CompactionPlan` (which events to archive, remove and insert), and the repository applies
that plan in one version-checked write; when the plan is empty, nothing is written.

```java
// Compact when turn count exceeds 20, keeping the last 10 events
CompactionResult result = service.compact(
    sessionId,
    new TurnCountTrigger(20),
    SlidingWindowCompactionStrategy.builder().maxEvents(10).build()
);

System.out.println(result.eventsRemoved());        // derived: archivedEvents().size()
System.out.println(result.compactedEvents());      // the new active window, in log order
System.out.println(result.archivedEvents());       // the archived (not deleted) events
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
  and the [JDBC concurrency diagram](compaction-internals.md#5-sequence-jdbc-applycompaction-and-a-concurrent-append).
- **Stored system messages are configuration.** If you store them (opt-in), the latest one
  is always kept where it was stored, never summarized and not counted against `maxEvents` /
  `maxTurns` / `maxEventsToKeep`; earlier ones are archived on every pass. See
  [System Messages](system-messages.md#compaction-the-latest-stored-system-message-wins).

---

## Compaction Triggers

Triggers implement `CompactionTrigger` (a `@FunctionalInterface`) and decide whether
compaction should run based on the current `CompactionRequest`.

### TurnCountTrigger

Fires when the session has more than `n` turns. Every non-synthetic `USER` event counts
toward the turn total, including the turn in progress.

```java
new TurnCountTrigger(20);  // compact when > 20 turns
```

### TokenCountTrigger

Fires when the estimated total token count is at or above a threshold (`threshold` is
required). It uses the same [token accounting](#token-accounting) as the strategies, so tool
calls and responses count, and it counts the same events as
`TokenCountCompactionStrategy`'s [budget](#how-the-budget-is-spent): the latest stored
system message, the summaries and the conversation.

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
`CompactionRequest` with the session and its active events, and returns a
`CompactionResult`: the new active window, in log order, and the events it archived.

| Strategy | LLM call? | Context preserved | Best for |
|---|---|---|---|
| `SlidingWindowCompactionStrategy` | No | Last N messages verbatim | Cost-sensitive, short-term context |
| `TurnWindowCompactionStrategy` | No | Last N complete turns verbatim | Turn-structured dialogues |
| `TokenCountCompactionStrategy` | No | Token-budget suffix verbatim | Hard context window limits |
| `RecursiveSummarizationCompactionStrategy` | Yes | Rolling LLM summary + active window | Long-running, context-rich sessions |

**Common behaviour.** Every strategy:

- keeps the latest stored system message and the synthetic summary events, and archives
  earlier stored system messages;
- **never reorders events**: archived events are flagged in place, and every kept event,
  including the system message, stays where it was stored. A new summary goes right
  before the kept conversation. `SessionMemoryAdvisor` still puts system messages first
  in the prompt;
- applies its limit only to the real conversation events;
- starts the kept window at a `USER` message and always keeps the most recent turn (see
  [Turn-boundary Safety](#turn-boundary-safety)). The exception is
  `TurnWindowCompactionStrategy`'s preamble: events before the first `USER` message, kept
  verbatim;
- returns the new active window: every event that is not archived, in log order.

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

Keeps the last `N` **real** events (default `N` = `DEFAULT_MAX_EVENTS` = 20).
Simple, predictable, no LLM call required.

```java
// keep the last 20 real events
SlidingWindowCompactionStrategy.builder().maxEvents(20).build();

// custom token estimator
SlidingWindowCompactionStrategy.builder().maxEvents(20).tokenCountEstimator(myEstimator).build();
```

It keeps the last `maxEvents` real events, then snaps the cut forward to the next `USER`
message.

### TurnWindowCompactionStrategy

Keeps the last `N` complete turns (default `N` = `DEFAULT_MAX_TURNS` = 10). Its limit is
counted in turns rather than events, so the size of the active window follows the
conversation's shape: ten short turns and ten tool-heavy turns both count as ten.

```java
// keep the last 10 turns
TurnWindowCompactionStrategy.builder().maxTurns(10).build();

// custom token estimator
TurnWindowCompactionStrategy.builder().maxTurns(10).tokenCountEstimator(myEstimator).build();
```

It groups events into turns (each starting at a `USER` message) and archives the oldest
until `maxTurns` remain. Events before the first `USER` message form a **preamble** that is
always kept, where it was stored.

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
events would leave gaps that break conversation coherence. The cut then snaps forward to
the next `USER` message, so leading events that are not `USER` messages are archived with
the older ones. Unlike the other strategies it has no "everything fits" shortcut: it
always walks the events.

#### How the budget is spent

The preserved events that are sent with the conversation take their tokens off
`maxTokens` before any conversation is considered:

```
remainingBudget = maxTokens − tokens(kept system message + synthetic summary events)
```

[Worked example 6](compaction-internals.md#6-worked-examples-of-the-tricky-cases) walks
through a budget with a stored system prompt.

If the deducted events use up the whole budget (`remainingBudget ≤ 0`), for example a
very large stored system prompt, only the newest turn is kept and every compaction
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
        .maxEventsToKeep(10)           // active window size: real events kept
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

1. Compute the cut so that the newest `maxEventsToKeep` real events form the
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
summarization, leaving the conversation unchanged (superseded stored system messages are
still archived). If the LLM call throws, the exception propagates
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
        .eventFormatter(event -> {
            // ToolResponseMessage.getText() is null — render the response data instead
            if (event.getMessage() instanceof ToolResponseMessage trm) {
                return "Tool result: " + trm.getResponses()
                    .stream()
                    .map(ToolResponseMessage.ToolResponse::responseData)
                    .collect(Collectors.joining("; "));
            }
            return RecursiveSummarizationCompactionStrategy.formatEvent(event);
        })
        .build();
```

---

## Turn-boundary Safety

All four strategies share a common safety rule: the kept window always starts at a
`USER` message (apart from `TurnWindowCompactionStrategy`'s preamble, see above). The
sliding-window, token-count and recursive-summarization strategies snap their cut point
forward to the next such message (package-private `CompactionUtils.snapToTurnStart`);
`TurnWindowCompactionStrategy` gets the same result by grouping events into turns. This
prevents keeping a tool result or assistant reply without the user message that started
its turn.

```
Before snap:  [u1, a1, u2, a2a, | a2b, u3, a3]   ← cut lands on a2b (middle of turn 2)
After snap:   [u1, a1, u2, a2a, a2b, | u3, a3]   ← cut moved to u3 (turn start)
```

The full cut-point pipeline and seven worked examples are in
[Compaction Internals](compaction-internals.md#3-activity-how-a-strategy-chooses-what-to-archive).

### The most recent turn is always kept

If there is no later turn start to snap to, the cut is moved back to the start of the
most recent turn instead. This happens when the newest turn alone exceeds the budget, for
example a long tool-calling loop or a very large tool result. That turn stays active even
though it goes over `maxEvents` / `maxTokens` / `maxEventsToKeep`, so compaction never
archives the turn in progress. When the whole history is a single oversize turn, nothing
is archived (and `RecursiveSummarizationCompactionStrategy` makes no LLM call). The same
holds when the active window has no `USER` message at all: there is no turn boundary to
cut at, so nothing is archived.

```
Budget: 2 events   [u1, a1, u2, a2, a3, a4]
Forward snap:      no USER after the cut → would archive everything
Kept instead:      [u1, a1, | u2, a2, a3, a4]   ← last turn kept, over budget
```
