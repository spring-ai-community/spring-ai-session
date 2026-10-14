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

!!! note "Branches were removed in 0.10.0"
    Earlier versions isolated sub-agents inside **one** session with ADK-style
    `SessionEvent.branch` labels. That support was deprecated in 0.9.0 and removed in
    0.10.0; see [Upgrading to 0.10.0](../migration.md#upgrading-to-0100).

---

## Session per sub-agent

A sub-agent's session is a **child** of the orchestrator's session: a session of its own,
with its own log and compaction, that belongs to the parent's tree. A child session is
what Claude Managed Agents calls a thread.
`SessionService.createChild(parentId, agentName)`, or `createChild(parentId, request)` with
the agent's version and model as metadata, creates it and enforces the rules that keep the
tree sound:

- the child names its parent (`Session.parentSessionId()`) and the tree's root
  (`Session.rootSessionId()`, the root's own id on a root session), and carries its
  [agent attributes](concepts.md#agent-attributes) when given;
- it **inherits** the parent's principal (and any other attribute the service is told to
  pass down, `DefaultSessionService.builder().inheritedAttributes(...)`) and the parent's
  expiry, and may not diverge from them;
- the tree has a **depth limit**, one level by default (`maxTreeDepth`), so a child of
  a child is rejected;
- a `CHILD_CREATED` marker is appended to the parent's log, so the parent's history shows
  that a delegation happened without copying the child's events;
- **delete and archive cascade down**: deleting the parent deletes its children and their
  events, archiving it archives them; either on a child leaves the parent alone;
- the tree has a **status**: each session records its own turns, and
  `aggregateStatus(rootId)` is `RUNNING` while any member runs a turn, so an orchestrator
  knows whether a delegation is still in flight.

The orchestrator exposes the sub-agent as a tool. When it is called, the tool:

1. derives the sub-agent's session id from the orchestrator's session id;
2. creates that child on first use;
3. calls the sub-agent's own `ChatClient`, which has its own `SessionMemoryAdvisor` and
   compaction settings;
4. returns only the sub-agent's final answer to the orchestrator.

```java
public class ResearcherTool {

    private final SessionService sessionService;

    private final ChatClient researcher;

    public ResearcherTool(SessionService sessionService, ChatClient.Builder chatClientBuilder) {
        this.sessionService = sessionService;
        // The sub-agent has its own session advisors, with compaction sized for its own model
        this.researcher = chatClientBuilder.defaultSystem("You are a researcher. Cite every source.")
            .defaultAdvisors(SessionTurnAdvisor.builder(sessionService)
                .compactionTrigger(new TurnCountTrigger(10))
                .compactionStrategy(SlidingWindowCompactionStrategy.builder().maxEvents(20).build())
                .build(), SessionMemoryAdvisor.builder(sessionService).build())
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
            try {
                this.sessionService.createChild(parentId, CreateSessionRequest.builder()
                    .id(childId)
                    .metadata(SessionAttributes.AGENT_NAME, "researcher")
                    .metadata(SessionAttributes.AGENT_MODEL, "claude-sonnet-5-5")
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

To find a conversation's children:

```java
List<Session> children = sessionService.findChildren(parentId);   // direct children, oldest first
List<Session> tree = sessionService.findTree(rootId);           // the root and every child of its tree
String agentName = SessionAttributes.agentName(children.get(0)); // also agentVersion, agentModel
```

The links are columns of the JDBC repository's `AI_SESSION` table, each with its own
index, so these are plain lookups there; the in-memory repository scans its sessions.

**Markers on the parent's log.** `createChild` records `CHILD_CREATED`; an agent runtime
can record `MESSAGE_SENT` and `MESSAGE_RECEIVED` when it hands a task to a child and
gets the answer back:

```java
Session child = sessionService.createChild(parentId, "researcher");
sessionService.appendEvent(SessionTreeEvents.messageSent(child, task));             // task: a UserMessage or a String
String answer = this.researcher.prompt()
    .user(task)
    .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, child.id()))
    .call()
    .content();                                                                   // the child's own log fills here
sessionService.appendEvent(SessionTreeEvents.messageReceived(child, answer));       // answer: an AssistantMessage or a String
```

The marker is logged on the child's parent; its payload names the child and its agent. The event also carries the message that
crossed the boundary, with all its parts (text, media, tool results), in the role it has on
the child's log: the task as a user message, the answer as an assistant message. This
mirrors the `content[]` of a managed-agent runtime's thread messages, so nothing is
flattened to text. The markers are not conversation: the active view and the prompt skip
them, and a marker never starts a turn. A text search on the parent does find what was
exchanged, so "what did the researcher say" works without opening the child. Together the
markers give a reader of the parent's history the condensed story of the delegation.

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
  [optimistic-concurrency version](concepts.md#optimistic-concurrency);
- with the [security module](scope-and-security.md), a child is granted to whoever is
  granted its parent, since it inherits the principal.

**Things to know:**
- **Lifecycle.** Children are deleted with their parent and take the parent's expiry at
  creation. Extending the parent's time to live later does not extend its children; extend
  them too, or give the root enough time up front.
- **Recall.** [Cross-Session Recall](../recall-memory/cross-session-recall.md) bound with
  `SessionSelector.principal(...)` searches a principal's root sessions only; use
  `SessionSelector.principalWithChildren(...)` to include sub-agent sessions.
- **Timeline.** There is no single merged timeline of a multi-agent run. The parent's
  markers tell the story in brief; merge the sessions' events by timestamp if you need
  the whole.
