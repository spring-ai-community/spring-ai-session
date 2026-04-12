# ChatClient Integration

`SessionMemoryAdvisor` is the primary integration point between Spring AI Session and a
`ChatClient`. It wires session management into the ChatClient pipeline transparently —
no manual history loading or appending required in application code.

---

## What the advisor does

On every request the advisor:

1. Looks up the session by the `SESSION_ID_CONTEXT_KEY` value in the advisor context
   (falls back to `defaultSessionId`). If the session does not exist, it is created
   automatically using the `USER_ID_CONTEXT_KEY` value (or `defaultUserId`) and the
   resolved session ID.
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
    .defaultSessionId("session-123")
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

!!! warning "Trigger and strategy must be set together"
    Setting only one of `compactionTrigger` or `compactionStrategy` throws
    `IllegalStateException`. Set both or neither.

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
`defaultUserId`.

---

## Context keys

| Key constant | String value | Purpose |
|---|---|---|
| `SESSION_ID_CONTEXT_KEY` | `"chat_memory_session_id"` | Routes the request to a session |
| `USER_ID_CONTEXT_KEY` | `"chat_memory_user_id"` | Used when auto-creating a session |
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
compare-and-swap write via `SessionRepository.replaceEvents(sessionId, events,
expectedVersion)`. The event-log version is read before events are fetched; if another
writer mutates the log between that read and the CAS write, `replaceEvents` returns `false`
and the second writer skips silently — no compacted result is lost or corrupted.

---

## Scheduler pinning

Both the blocking (`advise()`) and streaming (`adviseStream()`) paths run `before()` and
`after()` on the configured `Scheduler` (default: `BaseAdvisor.DEFAULT_SCHEDULER`). In
`adviseStream()`, a second `.publishOn(scheduler)` is applied after
`.flatMapMany(chain::nextStream)` so that the aggregation callback and compaction always
run on the scheduler rather than the LLM streaming thread.
