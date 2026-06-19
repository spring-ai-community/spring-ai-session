# ChatClient Integration

`SessionMemoryAdvisor` is the primary integration point between Spring AI Session and a
`ChatClient`. It wires session management into the ChatClient pipeline transparently —
no manual history loading or appending required in application code.

---

## What the advisor does

On every request the advisor:

1. Resolves the session ID from `SESSION_ID_CONTEXT_KEY` in the advisor context — this
   key **must** be present on every request. If the session does not exist, it is created
   automatically using the `USER_ID_CONTEXT_KEY` value (or `defaultUserId`) and the
   resolved session ID. If the session already exists and `USER_ID_CONTEXT_KEY` is set,
   the advisor validates that the requesting user owns the session and throws
   `IllegalStateException` on mismatch.
2. Retrieves the session's event history (filtered by the configured `eventFilter`,
   default `EventFilter.all()`) and **prepends** it to the prompt messages. If the
   request context contains an `EVENT_FILTER_CONTEXT_KEY` value, it is merged with the
   advisor-level filter — request-level fields win over advisor defaults.
3. Reorders all `SystemMessage` instances to the front of the combined message list,
   preserving their relative order.
4. Appends the current user message to the session.
5. After the model responds, appends the assistant message.
6. If a trigger fires, runs compaction **synchronously** before returning — the full turn
   (user + assistant) is already written at this point, so there is no race between
   compaction and message appending.

---

## Setup

```java
SessionMemoryAdvisor advisor = SessionMemoryAdvisor.builder(sessionService)
    .defaultUserId("alice")
    // Compact when 20 turns accumulate, using LLM summarization to retain context
    .compactionTrigger(new TurnCountTrigger(20))
    .compactionStrategy(
        RecursiveSummarizationCompactionStrategy.builder(chatClient)
            .maxEventsToKeep(10)
            .build()
    )
    .build();

ChatClient client = ChatClient.builder(chatModel)
    .defaultAdvisors(advisor)
    .build();
```

!!! warning "Session ID is required on every request"
    `SESSION_ID_CONTEXT_KEY` must be set in the advisor context on every call.
    Omitting it throws `IllegalStateException`. This is intentional — a shared fallback
    session ID would silently merge history across different users.

!!! warning "Trigger and strategy must be set together"
    Setting only one of `compactionTrigger` or `compactionStrategy` throws
    `IllegalStateException`. Set both or neither.

!!! note "Default advisor order"
    The default order is `Ordered.HIGHEST_PRECEDENCE + 1000` (≈ `Integer.MIN_VALUE + 1000`),
    giving `SessionMemoryAdvisor` higher precedence than `ToolAdvisor` (order `300`).
    Higher precedence means `before()` runs first and `after()` runs last, so
    `SessionMemoryAdvisor` wraps the tool advisor — tool results are fully resolved before
    the session write in `after()`. Override with `.order(n)` if your pipeline requires a
    different position.

---

## Passing a session ID per request

Pass a session ID at call time via the advisor context:

```java
String response = client.prompt()
    .user("Hello!")
    .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, "session-abc"))
    .call()
    .content();
```

If no session exists for the given ID, the advisor creates one automatically using the
`USER_ID_CONTEXT_KEY` value from the request context, falling back to `defaultUserId`.

!!! note "Ownership enforcement"
    When `USER_ID_CONTEXT_KEY` is present and the session already exists, the advisor
    checks that the supplied user ID matches `session.userId()`. A mismatch throws
    `IllegalStateException` — this prevents one user from reading or appending to
    another user's session if session IDs are ever guessable or shared.

    The check is **skipped** when `USER_ID_CONTEXT_KEY` is absent so that callers
    which rely solely on `defaultUserId` (or do their own authorization upstream) are
    not affected.

    ```java
    client.prompt()
        .user("Hello!")
        .advisors(a -> a
            .param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, "session-abc")
            .param(SessionMemoryAdvisor.USER_ID_CONTEXT_KEY, "alice")  // enforced
        )
        .call()
        .content();
    ```

---

## Context keys

| Key constant | String value | Purpose |
|---|---|---|
| `SESSION_ID_CONTEXT_KEY` | `"chat_memory_conversation_id"` (= `ChatMemory.CONVERSATION_ID`) | Routes the request to a session |
| `USER_ID_CONTEXT_KEY` | `"chat_memory_user_id"` | Used when auto-creating a session; also enforces ownership on existing sessions when set |
| `EVENT_FILTER_CONTEXT_KEY` | `"chat_memory_event_filter_id"` | Per-request `EventFilter` merged with the advisor-level filter |

---

## Per-request filter override

Pass an `EventFilter` via `EVENT_FILTER_CONTEXT_KEY` to narrow or adjust history
retrieval on a single call without reconfiguring the advisor:

```java
// Advisor is configured with EventFilter.all() (default).
// This request overrides to see only the last 5 events.
String response = client.prompt()
    .user("Quick summary please")
    .advisors(a -> a
        .param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId)
        .param(SessionMemoryAdvisor.EVENT_FILTER_CONTEXT_KEY, EventFilter.lastN(5))
    )
    .call()
    .content();
```

`EventFilter.merge()` semantics: every non-null field from the request filter replaces
the corresponding field from the advisor default; `excludeSynthetic` is OR-ed so either
side can opt in. A `null` value for `EVENT_FILTER_CONTEXT_KEY` is ignored.

---

## Concurrent compaction safety

If two requests for the same session complete concurrently (e.g. parallel fan-out), both
`after()` calls may reach the compaction step simultaneously. Compaction uses an optimistic
compare-and-swap write via `SessionRepository.compactEvents(sessionId, archivedEvents,
retainedEvents, expectedVersion)`. The event-log version is read before events are fetched;
if another writer mutates the log between that read and the CAS write, `compactEvents`
returns `false` and the second writer skips silently — no compacted result is lost or
corrupted.

---

## Scheduler pinning

Both the blocking (`advise()`) and streaming (`adviseStream()`) paths run `before()` and
`after()` on the configured `Scheduler` (default: `BaseAdvisor.DEFAULT_SCHEDULER`). In
`adviseStream()`, a second `.publishOn(scheduler)` is applied after
`.flatMapMany(chain::nextStream)` so that the aggregation callback and compaction always
run on the scheduler rather than the LLM streaming thread.
