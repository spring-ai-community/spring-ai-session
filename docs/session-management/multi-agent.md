# Multi-Agent Branch Isolation

When an orchestrator delegates work to several sub-agents running in parallel, all agents
can share the same `Session` — but each sub-agent must see only its own history plus its
ancestors'. Peer agents on sibling branches must be invisible to each other.

This design mirrors the [Google ADK Java `Event.branch`](https://github.com/google/adk-java/blob/main/core/src/main/java/com/google/adk/events/Event.java)
field and the isolation semantics it defines.

---

## Branch format

`SessionEvent.branch` is a dot-separated path that records the agent hierarchy that
produced the event:

```
orchestrator                        branch = "orch"
├── researcher                      branch = "orch.researcher"
│   └── summarizer                  branch = "orch.researcher.summarizer"
└── writer                          branch = "orch.writer"
```

Events produced before any delegation (e.g. the initial user message) have
`branch = null` and are visible to every agent in the session.

---

## Tagging events

Each agent tags its own events with its branch when appending to the session:

```java
// Root event (no branch) — visible to all agents
service.appendEvent(SessionEvent.builder()
    .sessionId(sessionId)
    .message(new UserMessage("Summarise the news today"))
    .build());

// Orchestrator tags its own planning events
service.appendEvent(SessionEvent.builder()
    .sessionId(sessionId)
    .message(new AssistantMessage("Delegating to researcher and writer"))
    .branch("orch")
    .build());

// Each sub-agent tags its events with its own branch
service.appendEvent(SessionEvent.builder()
    .sessionId(sessionId)
    .message(new AssistantMessage("Research findings..."))
    .branch("orch.researcher")
    .build());

service.appendEvent(SessionEvent.builder()
    .sessionId(sessionId)
    .message(new AssistantMessage("Draft article..."))
    .branch("orch.writer")
    .build());
```

---

## Filtering by branch

Pass `EventFilter.forBranch(agentBranch)` when loading history for a sub-agent:

```java
// Researcher sees: null-branch events + "orch" events + own "orch.researcher" events
// Hidden from researcher: "orch.writer" (sibling), "orch.researcher.summarizer" (child)
List<SessionEvent> researcherHistory = service.getEvents(sessionId,
    EventFilter.forBranch("orch.researcher"));
```

To apply branch isolation automatically inside `SessionMemoryAdvisor`, configure the
`eventFilter` on the builder:

```java
SessionMemoryAdvisor researcherAdvisor = SessionMemoryAdvisor.builder(sessionService)
    .eventFilter(EventFilter.forBranch("orch.researcher"))
    .build();

// Pass the shared session ID on every request
chatClient.prompt()
    .user(userMessage)
    .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sharedSessionId))
    .call()
    .content();
```

This ensures the advisor only injects events visible to `orch.researcher` into the
prompt — root events and its own events — while sibling events from `orch.writer` remain
hidden.

---

## Visibility rules

`EventFilter.matches()` applies the following rule per event:

| Event branch | Visible to `orch.researcher`? | Reason |
|---|---|---|
| `null` | **yes** | Root event — visible to all |
| `"orch"` | **yes** | Direct ancestor |
| `"orch.researcher"` | **yes** | Own branch |
| `"orch.writer"` | no | Sibling branch |
| `"orch.researcher.summarizer"` | no | Child branch |

The dot-separator check (`filterBranch.startsWith(eventBranch + ".")`) ensures that a
branch named `"orch"` is never confused with one named `"orchestra"`.

---

## Synthetic events and branch

Synthetic summary events produced by `RecursiveSummarizationCompactionStrategy` always
have `branch = null`. This ensures compaction summaries remain visible to every agent in
the session after context has been pruned, regardless of which branch was active when
compaction ran.

---

## Compaction and branches

Compaction strategies are branch-aware. When computing the cut point, `snapToTurnStart`
only considers root-level (`branch == null`) `USER` events as valid turn boundaries.
Branched `UserMessage` events represent prompts sent *to* a sub-agent and are
turn-internal — snapping to one would split the root turn that contains the sub-agent
exchange.

See [Turn-boundary Safety](compaction.md#turn-boundary-safety) in the compaction reference
for the full explanation and event-log diagram.

---

## System messages and branches

System messages are configuration, and they are scoped by branch like everything else. If
your application stores system messages (an opt-in, see
[System Messages](system-messages.md)):

- **Each agent uses its own.** An agent's system prompt is the latest system message stored
  on its own branch: `branch == null` for the root agent, `branch == "orch.researcher"` for
  that sub-agent. `SessionMemoryAdvisor` never sends another branch's system message, so a
  sub-agent's instructions cannot leak into the orchestrator's prompt, and the
  orchestrator's instructions are not added to a sub-agent's prompt.
- **Compaction.** The latest system message of every branch is kept, placed first and never
  summarized, so a sub-agent delegated to again in a later turn still has its system
  prompt. Earlier system messages of the same branch are superseded and archived.

```java
// The researcher's own instructions, stored on its branch
service.appendEvent(SessionEvent.builder()
    .sessionId(sessionId)
    .branch("orch.researcher")
    .message(new SystemMessage("You are a researcher. Cite every source."))
    .build());
```

---

## Recall search and branches

`conversation_search` (see [Recall Storage](recall-storage.md)) searches the whole session
by default, including peer sub-agents' events. To keep a sub-agent's recall inside its
own view of the session, build its tool instance with the agent's branch:

```java
SessionEventTools researcherTools = SessionEventTools.builder(sessionService)
    .branch("orch.researcher")
    .build();
```
