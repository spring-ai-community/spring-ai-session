# Session Concepts

Spring AI Session introduces four closely related concepts that together describe how a
conversation is stored, structured, and managed over time.

---

## Session

`Session` is the **identity and lifecycle container** for a single, continuous conversation
between a user and an agent. It is an immutable value object — it holds only metadata.
The event log is stored separately in the repository and fetched on demand.

| Field | Description |
|-------|-------------|
| `id` | Unique session identifier |
| `userId` | Owning user or agent — required, used for isolation |
| `createdAt` | Creation timestamp |
| `expiresAt` | Expiry instant; defaults to 60 days from creation; `null` means no expiry. The builder rejects past values. |
| `metadata` | Arbitrary key/value pairs (model info, tags, etc.) |

Keeping `Session` metadata-only means it can be passed across boundaries cheaply, and
compaction strategies receive the event list as an explicit parameter rather than
extracting it from the session object.

Sessions are created through `SessionService`, which is the primary API for the entire
lifecycle:

```java
SessionService service = new DefaultSessionService(InMemorySessionRepository.builder().build());

Session session = service.create(
    CreateSessionRequest.builder()
        .id("my-session-id")           // optional; a UUID is generated when omitted
        .userId("alice")
        .timeToLive(Duration.ofHours(2)) // optional; defaults to 60 days
        .metadata("agentType", "research-assistant")
        .build()
);
```

---

## SessionEvent

`SessionEvent` is an immutable record that wraps a Spring AI `Message` type. It adds only
what `Message` intentionally omits: identity, ownership, ordering, and framework flags.

| Field | Description |
|-------|-------------|
| `id` | Unique identity per event (UUID by default) |
| `sessionId` | Ownership / isolation |
| `timestamp` | Chronological ordering (`Instant.now()` by default) |
| `message` | The Spring AI message — no duplication of content |
| `metadata` | Framework flags such as `METADATA_SYNTHETIC` and `METADATA_COMPACTION_SOURCE` |
| `branch` | Dot-separated agent path (e.g. `"orch.researcher"`); `null` for root-level events |

### Message types

| Message type | `SessionEvent.isSynthetic()` | Meaning |
|---|---|---|
| `UserMessage` | `false` | Real user input |
| `AssistantMessage` (no tool calls) | `false` | Agent response |
| `AssistantMessage` (with tool calls) | `false` | Agent tool invocation |
| `ToolResponseMessage` | `false` | Tool output |
| `UserMessage` | `true` | Synthetic shadow prompt opening a summary turn |
| `AssistantMessage` | `true` | Synthetic summary text closing a summary turn |

### Building events

```java
// Root event (no branch) — visible to all agents; timestamped at Instant.now()
SessionEvent event = SessionEvent.builder()
    .sessionId(sessionId)
    .message(new UserMessage("Hello"))
    .build();

// Branched event — attributed to a specific sub-agent
SessionEvent branched = SessionEvent.builder()
    .sessionId(sessionId)
    .message(new AssistantMessage("Research result..."))
    .branch("orch.researcher")
    .build();

// With metadata
SessionEvent custom = SessionEvent.builder()
    .sessionId(sessionId)
    .message(new AssistantMessage("Response"))
    .metadata("model", "gpt-4o")
    .metadata("latencyMs", 230)
    .build();

// Deterministic timestamp (useful for tests)
SessionEvent deterministic = SessionEvent.builder()
    .sessionId(sessionId)
    .timestamp(Instant.parse("2025-06-01T12:00:00Z"))
    .message(new UserMessage("Hello"))
    .build();
```

Builder defaults: `id` is a random UUID, `timestamp` is `Instant.now()`, `metadata` is
empty, `branch` is `null`. Only `sessionId` and `message` are required.

---

## Turn

A **turn** is the atomic unit of conversation:

> One `UserMessage` plus all subsequent events (assistant replies, tool calls, tool
> results) up to the next `UserMessage`.

Working with turns rather than raw message counts prevents compaction from splitting a
tool-call/result pair, or from removing an assistant reply while keeping the user question
that prompted it.

```
Turn 1: [USER "What is Spring AI?"]  [ASSISTANT "Spring AI is..."]
Turn 2: [USER "Can it use tools?"]   [ASSISTANT (tool call)]  [TOOL result]  [ASSISTANT "Yes, ..."]
Turn 3: [USER "Show me an example"]  [ASSISTANT "Here is..."]
```

Turn count is measured via `CompactionRequest.currentTurnCount()`, which counts only
non-synthetic, **root-level** (`branch == null`) `USER` events. Synthetic and sub-agent
`USER` messages on named branches are excluded so that multi-agent sessions do not inflate
the count used by `TurnCountTrigger`.

---

## Synthetic Summary Turn

When `RecursiveSummarizationCompactionStrategy` compacts a session it replaces archived
events with two synthetic events that form a coherent conversation turn:

```
[USER  / synthetic] "Summarize the conversation we had so far."
[ASST  / synthetic] "The user asked about topic X. The assistant explained..."
[USER  / real     ] "Next actual question"
[ASST  / real     ] "Next actual response"
```

This mirrors the OpenAI Agents SDK shadow-prompt pattern and ensures that downstream
models always see a valid user↔assistant alternation.

All compaction strategies treat synthetic events as opaque — they separate them from real
events before processing, preserve them, and place them first in the compacted output.

Build a synthetic summary turn explicitly:

```java
Instant now = Instant.now();
List<SessionEvent> summaryTurn = List.of(
    SessionEvent.builder()
        .sessionId(sessionId)
        .timestamp(now)
        .message(new UserMessage("Summarize the conversation we had so far."))
        .metadata(SessionEvent.METADATA_SYNTHETIC, true)
        .metadata(SessionEvent.METADATA_COMPACTION_SOURCE, "recursive-summarization")
        .build(),
    SessionEvent.builder()
        .sessionId(sessionId)
        .timestamp(now)
        .message(new AssistantMessage("The user asked about X. The assistant..."))
        .metadata(SessionEvent.METADATA_SYNTHETIC, true)
        .metadata(SessionEvent.METADATA_COMPACTION_SOURCE, "recursive-summarization")
        .build()
);
```

Both events share the same `Instant` so the user/assistant pair is treated as an atomic
unit.

---

## Architecture Overview

![Spring AI Session API Classes](../images/spring-ai-session-api-classes.png)

**Why `Session` carries no events**

Storing the event log inside `Session` would force every consumer that needs to mutate the
list (compaction, archiving) to hold a mutable reference inside what is meant to be an
immutable value object. By keeping `Session` as pure metadata, all event mutations go
through dedicated repository methods (`appendEvent`, `replaceEvents`) and `SessionService`
operates on the event list as an explicit parameter.

**Optimistic concurrency**

`SessionRepository` exposes `getEventVersion(sessionId)` — a monotonically increasing
counter incremented on every `appendEvent` and `replaceEvents` call. Callers read this
version before fetching events, then pass it to `replaceEvents(sessionId, events,
expectedVersion)`. If another writer mutated the log in the interval, the CAS variant
returns `false` — the caller treats this as a no-op rather than retrying. Durable
implementations (JDBC, Redis) should map this to a database-level optimistic-lock column
or a Redis `WATCH`.

---

## Session Lifecycle

```java
SessionService service = new DefaultSessionService(InMemorySessionRepository.builder().build());

// 1. Create
Session session = service.create(
    CreateSessionRequest.builder()
        .userId("alice")
        .build()
);

// 2. Append events (shorthand wraps a Message in a SessionEvent automatically)
service.appendMessage(session.id(), new UserMessage("What is Spring AI?"));
service.appendMessage(session.id(), new AssistantMessage("Spring AI is..."));

// 3. Retrieve as Message list (for passing directly to an LLM)
List<Message> history = service.getMessages(session.id());

// 4. Retrieve as SessionEvent list (for filtering, inspection, compaction)
List<SessionEvent> events = service.getEvents(session.id());

// 5. Delete
service.delete(session.id());
```

Deleted sessions are removed from the repository entirely — there is no tombstone state.

---

## Package Structure

```
org.springframework.ai.session          (Java package — unchanged from upstream)
├── Session.java                        – immutable metadata-only value object
├── SessionEvent.java                   – immutable wrapper around a Spring AI Message
├── SessionService.java                 – primary lifecycle + compaction API
├── SessionRepository.java              – persistence SPI
├── CreateSessionRequest.java           – builder for session creation parameters
├── EventFilter.java                    – composable criteria for event retrieval
├── DefaultSessionService.java          – default SessionService implementation
├── InMemorySessionRepository.java      – ConcurrentHashMap-backed repository
│
├── advisor/
│   └── SessionMemoryAdvisor.java       – ChatClient advisor with auto-compaction
│
├── compaction/
│   ├── CompactionRequest.java          – (session, events, eventCount, turnCount)
│   ├── CompactionResult.java           – compacted events + archived events + metrics
│   ├── CompactionStrategy.java         – strategy SPI
│   ├── CompactionTrigger.java          – trigger SPI
│   ├── CompositeCompactionTrigger.java – OR-composite of triggers
│   ├── TurnCountTrigger.java
│   ├── TokenCountTrigger.java
│   ├── SlidingWindowCompactionStrategy.java
│   ├── TurnWindowCompactionStrategy.java
│   ├── TokenCountCompactionStrategy.java
│   └── RecursiveSummarizationCompactionStrategy.java
││
└── tool/
    └── SessionEventTools.java          – @Tool conversation_search (Recall Storage)
```
