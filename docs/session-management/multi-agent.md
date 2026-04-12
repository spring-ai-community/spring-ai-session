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
    .defaultSessionId(sharedSessionId)
    .eventFilter(EventFilter.forBranch("orch.researcher"))
    .build();
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
