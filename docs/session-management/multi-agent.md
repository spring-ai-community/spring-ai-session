# Multi-Agent

Agents work together in one of two ways, and each maps to a different use of sessions:

| How the agents work together | Use | Why |
|---|---|---|
| **Delegation**: an orchestrator hands a sub-agent a task and gets a result back (agent-as-tool) | [A session per sub-agent](#session-per-sub-agent) | The sub-agent sees only its task and its own history. It compacts on its own, sized to its own model, and only its result goes back to the parent |
| **Handoff**: several agents take turns in one conversation | One shared session | Every agent should see the whole conversation, so no filtering is needed |

A session per sub-agent is how most agent frameworks isolate sub-agents, for example
Claude Code subagents, Microsoft Agent Framework's agent-as-tool, Google ADK's
`AgentTool` and the OpenAI Agents SDK's `as_tool`. It also follows the common context
engineering advice to give every sub-agent the minimum context it needs.

!!! warning "Branches are deprecated"
    Earlier versions isolated sub-agents inside **one** session with ADK-style
    `SessionEvent.branch` labels. That support is deprecated since 0.9.0 and will be
    removed in 0.10.0. See [Branches (deprecated)](#branches-deprecated).

---

## Session per sub-agent

The orchestrator exposes the sub-agent as a tool. When it is called, the tool:

1. derives the sub-agent's session id from the orchestrator's session id;
2. creates that session on first use, linked to the parent through metadata;
3. calls the sub-agent's own `ChatClient`, which has its own `SessionMemoryAdvisor` and
   compaction settings;
4. returns only the sub-agent's final answer to the orchestrator.

```java
public class ResearcherTool {

    // Metadata convention linking a sub-agent session to its parent
    public static final String PARENT_SESSION_ID = "parentSessionId";

    public static final String AGENT = "agent";

    private final SessionService sessionService;

    private final ChatClient researcher;

    public ResearcherTool(SessionService sessionService, ChatClient.Builder chatClientBuilder) {
        this.sessionService = sessionService;
        // The sub-agent has its own memory advisor, with compaction sized for its own model
        this.researcher = chatClientBuilder.defaultSystem("You are a researcher. Cite every source.")
            .defaultAdvisors(SessionMemoryAdvisor.builder(sessionService)
                .compactionTrigger(new TurnCountTrigger(10))
                .compactionStrategy(SlidingWindowCompactionStrategy.builder().maxEvents(20).build())
                .build())
            .build();
    }

    @Tool(description = "Delegate a research task to the researcher agent and return its findings")
    public String research(@ToolParam(description = "The research task, with all needed context") String task,
            ToolContext toolContext) {

        String parentId = (String) toolContext.getContext().get(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY);

        // Stable id: the researcher remembers earlier tasks of this conversation.
        // Use parentId + ":researcher:" + UUID.randomUUID() for a fresh context per task.
        String childId = parentId + ":researcher";

        if (this.sessionService.findById(childId) == null) {
            Session parent = this.sessionService.findById(parentId);
            try {
                this.sessionService.create(CreateSessionRequest.builder()
                    .id(childId)
                    .userId(parent.userId())
                    .metadata(PARENT_SESSION_ID, parentId)
                    .metadata(AGENT, "researcher")
                    .build());
            }
            catch (IllegalStateException alreadyCreated) {
                // a concurrent delegation created it first
            }
        }

        // Only the task goes in and only the final answer comes back to the orchestrator
        return this.researcher.prompt()
            .user(task)
            .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, childId))
            .call()
            .content();
    }

}
```

The orchestrator is an ordinary `ChatClient` call. It passes its session id to its own
advisor and, through the tool context, to the tool:

```java
orchestrator.prompt()
    .user(question)
    .tools(researcherTool)
    .toolContext(Map.of(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId))
    .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId))
    .call()
    .content();
```

To find a conversation's sub-agent sessions, filter the user's sessions on the metadata
key:

```java
List<Session> children = sessionService.findByUserId(userId)
    .stream()
    .filter(s -> parentId.equals(s.metadata().get(ResearcherTool.PARENT_SESSION_ID)))
    .toList();
```

**Stable or per-task session ids.**
- **Stable id** (`parentId + ":researcher"`): the sub-agent remembers its earlier tasks in
  the same conversation, and you can resume it later.
- **Per-task id** (add a random suffix): every delegation starts with a fresh context.
  The orchestrator must then put everything the sub-agent needs into the task.

**What you get:**
- the sub-agent's prompt holds only its task and its own history, not the orchestrator's
  conversation or other sub-agents' work;
- [compaction](compaction.md) runs per session, with triggers and budgets sized to each
  agent's model;
- each sub-agent has its own audit trail, searchable with
  [Recall Storage](../recall-memory/recall-storage.md);
- parallel sub-agents never contend for the same session's
  [optimistic-concurrency version](concepts.md#optimistic-concurrency).

**Things to know:**
- **Parent link.** The link between parent and child sessions is only the metadata
  convention above, not a library API. Finding a parent's children means scanning
  `findByUserId`.
- **Lifecycle.** Sub-agent sessions are not deleted with the parent. Give them the same
  time to live as the parent (`CreateSessionRequest.builder().timeToLive(...)`), or delete
  them yourself.
- **Recall.** [Cross-Session Recall](../recall-memory/cross-session-recall.md) searches
  every session of a user, including sub-agent sessions.
- **Timeline.** There is no single merged timeline of a multi-agent run. Merge the sessions'
  events by timestamp if you need one.

---

## Branches (deprecated)

!!! warning "Deprecated since 0.9.0, removed in 0.10.0"
    The following APIs are deprecated for removal: `SessionEvent.Builder.branch(...)`,
    `SessionEvent.getBranch()` and `isRootEvent()`, `EventFilter.forBranch(...)`,
    `EventFilter.Builder.branch(...)` and `EventFilter.branch()`, and
    `SessionEventTools.Builder.branch(...)`. Use a
    [session per sub-agent](#session-per-sub-agent) instead.

Branches follow the [Google ADK `Event.branch`](https://github.com/google/adk-java/blob/main/core/src/main/java/com/google/adk/events/Event.java)
model: all agents share **one** session, and each event carries a dot-separated path of the
agent that produced it (`"orch"`, `"orch.researcher"`, `"orch.writer"`). A `null` branch
marks a root event, such as the end user's message.

### Filtering by branch

`EventFilter.forBranch("orch.researcher")` returns the root events, the ancestors' events
(`"orch"`) and the agent's own events (`"orch.researcher"`). It hides siblings
(`"orch.writer"`) and children (`"orch.researcher.summarizer"`). The dot-separator check
means that `"orch"` is never confused with `"orchestra"`.

`SessionMemoryAdvisor` applies the branch of its `eventFilter` to both sides: it reads the
agent's view of the session, and it records the agent's user and assistant messages on
that branch.

```java
SessionMemoryAdvisor researcherAdvisor = SessionMemoryAdvisor.builder(sessionService)
    .eventFilter(EventFilter.forBranch("orch.researcher"))
    .build();
```

### Visibility flows down, not up

An agent sees its ancestors' events, never its descendants'. The exception is a reader
**without** a branch filter: `EventFilter.all()`, the advisor's default, sees every event
of every branch. There is no "root events only" filter.

| Reader's filter | Root events | Ancestor branches | Own branch | Sub-agent and sibling branches |
|---|---|---|---|---|
| `EventFilter.all()` (no branch) | yes | — | — | **yes, all of them** |
| `EventFilter.forBranch("orch")` | yes | — | yes | no |
| `EventFilter.forBranch("orch.researcher")` | yes | yes (`"orch"`) | yes | no |

### Compaction and branches

Compaction runs over the whole session, all branches at once:

- **Turns are root turns.** Compaction cuts only at root-level `USER` events, and a
  sub-agent's events are archived or kept together with the root turn that contains them
  (see [Turn-boundary Safety](compaction.md#turn-boundary-safety)). A sub-agent that grows
  inside the newest turn is never compacted.
- **Budgets measure all branches.** The token-based trigger and strategy count every
  branch's events, even though each agent sends only its own view. The event-count
  strategies count only root events.
- **Summaries are visible to every agent.** Synthetic summary events have no branch, so an
  agent can read a summary of a sibling's work.
- **No root turn, no compaction.** If the top-level agent is on a branch too, its advisor
  records the user's messages on that branch, so the session has no root turns. Since
  0.9.0 compaction then archives nothing (before, `TokenCountCompactionStrategy` archived
  the whole active window).

### System messages and branches

When system messages are stored (an opt-in), each agent's system prompt is the latest one
on its own branch, and compaction keeps the latest one of every branch. Only the root
agent's system message counts toward the token budget. See
[System Messages](system-messages.md#compaction-the-latest-stored-system-message-wins).

### Recall search and branches

`conversation_search` searches the whole session by default, including sibling agents'
events. `SessionEventTools.builder(sessionService).branch("orch.researcher")` limits it to
what that branch can see.
