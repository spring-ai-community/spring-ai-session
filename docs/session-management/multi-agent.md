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

### Visibility flows down, not up

An agent sees its ancestors' events, never its descendants'. An orchestrator reading
with `EventFilter.forBranch("orch")` sees the root events and its own `"orch"` events,
but none of the work its sub-agents record on `"orch.researcher"` or `"orch.writer"`. It
learns their results the way it delegated, typically as the tool response it stores on
its own branch.

The exception is a reader **without** a branch filter. `EventFilter.all()`, the default of
`SessionMemoryAdvisor`, applies no branch restriction at all, so it sees **every** event
of **every** branch. There is no "root events only" filter: `forBranch(null)` is the same
as no filter.

| Reader's filter | Sees root (`null`) events | Sees ancestor branches | Sees own branch | Sees sub-agent and sibling branches |
|---|---|---|---|---|
| `EventFilter.all()` (no branch) | yes | — | — | **yes, all of them** |
| `EventFilter.forBranch("orch")` | yes | — | yes | no |
| `EventFilter.forBranch("orch.researcher")` | yes | yes (`"orch"`) | yes | no |

So if the top-level agent keeps the default filter while sub-agents write to the same
session, its prompt history includes the sub-agents' internal messages. To keep it
isolated, give the top-level agent a branch too (as `"orch"` above), so that it becomes
an ordinary node of the tree. System messages are the one exception: an agent without a
branch uses only root-level system messages (see
[System messages and branches](#system-messages-and-branches)).

---

## Compaction and branches

Synthetic summary events always have `branch = null`, so summaries stay visible to every
agent. Compaction only cuts at root-level (`branch == null`) `USER` events: a branched
`UserMessage` is a prompt sent *to* a sub-agent inside a root turn (see
[Turn-boundary Safety](compaction.md#turn-boundary-safety)).

---

## System messages and branches

If your application stores system messages (an opt-in), they are scoped by branch: an
agent's system prompt is the latest one stored on the branch of its advisor's
`EventFilter` (`null` for the root agent, whose default `EventFilter.all()` has no
branch). `SessionMemoryAdvisor` never sends another branch's system message, and
compaction keeps the latest one of every branch, but only the root agent's counts toward
the token budget. See
[System Messages](system-messages.md#compaction-the-latest-stored-system-message-wins).

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

`conversation_search` (see [Recall Storage](../recall-memory/recall-storage.md)) searches the whole session
by default, including peer sub-agents' events. To keep a sub-agent's recall inside its
own view of the session, build its tool instance with the agent's branch:

```java
SessionEventTools researcherTools = SessionEventTools.builder(sessionService)
    .branch("orch.researcher")
    .build();
```
