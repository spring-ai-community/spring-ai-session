# spring-ai-agent Integration

`spring-ai-session-agent` keeps a [spring-ai-agent](https://github.com/spring-projects/spring-ai-agent)
run in a session tree. It is the second way to use the session, next to the
[`ChatClient` advisors](../chat-client/chat-client.md): the same `SessionService`, the same log,
the same tree, status, usage, cursor and live stream, driven by the agent runtime's features
instead of advisors.

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-session-agent</artifactId>
</dependency>
```

---

## SessionFeature

Enable it on the top-level agent. It implements the runtime's `RunListener`,
`ConversationInterceptor` and `AgentEventListener<AgentEvent>`:

```java
SessionFeature feature = SessionFeature.builder(sessionService)
    .agent(AgentSnapshot.of("planner", "3", "claude-sonnet-5-5"))
    .compaction(new TurnCountTrigger(20), SlidingWindowCompactionStrategy.builder().maxEvents(40).build())
    .build();

Agent<String, String> agent = ModelAwareGraphAgent.builder(Graphs.toolCallLoop(), chatModel)
    .enable(feature)
    .enable(new CheckpointerFeature(feature.checkpointer()))   // the log is the checkpoint store
    .build();

agent.output("I am planning a trip to Paris.", new RunIdentification("trip-1", "run-1"));
agent.output("Which city did I mention?", new RunIdentification("trip-1", "run-2"));  // continues
```

| The runtime's | Becomes in the session |
| --- | --- |
| `RunIdentification.sessionId()` | The root session, created on first use with the feature's agent snapshot, restored from its active view on every later run |
| A run | A turn: `startTurn(id, runId)` when the run starts, `endTurn` with `END_TURN`, `ERROR` or `REQUIRES_ACTION` when it ends; both status events carry the run id |
| The main `Conversation` | `MESSAGE` events of the root session, appended from the conversation interceptor; compaction after each update when configured |
| A nested context (`Nodes.agent`, `AgentTools`) | A child session, `rootId/path`, created on the first event from that path, with the nested agent's node name as its agent and `inheritedMessages` in its metadata; `SHARED` contexts write to their parent's session |
| What a nested agent receives and answers | `MESSAGE_SENT` and `MESSAGE_RECEIVED` markers on the parent |
| `NodeStartedEvent`, `NodeCompletedEvent`, `NodeFailedEvent` | `NODE` events with the node, the phase, and the input or output as JSON |
| `ModelCallStartedEvent`, `ModelCallFailedEvent` | `MODEL_CALL` events with the phase |
| `ModelCallCompletedEvent` | A `USAGE` event: the call's token usage, model, path and message count |
| `ToolCallStartedEvent`, `ToolCallCompletedEvent`, `ToolCallFailedEvent` | `TOOL_CALL` events with the name, arguments, phase and result |
| An application's own `AgentEvent` record | An event of type `AGENT.<CLASS_NAME>`, its fields as the payload |
| `RunInterruptedEvent` (`Nodes.interrupt`) | An `INTERACTION` question and a turn parked with `REQUIRES_ACTION`; `resumeOutput(..)` on any agent instance records the answer and resumes |
| A cancelled run (`AgentRun#close`, a `CancellationException`) | An `INTERRUPT` event and a turn ended with `INTERRUPTED`, not `ERROR` |
| `ChatResponseEvent` (chunks), `TurnProgressedEvent` | Not stored |

**Message ids are deterministic.** A message appended by a run gets an id from the run, the
path, its position and a digest of its content. A re-attempt of a crashed run that appends the
same message again appends nothing, since a store ignores an id it holds; a different message
at the same position is a new event. That is what makes the log safe to use as the checkpoint
store.

**Nested conversations are self-contained.** A child session's log starts with the messages
the nested conversation inherited (`FORKED` and `DELEGATED` start from a copy of the parent's,
`ISOLATED` from nothing), then holds the nested agent's own messages, so a resume reads one
session. Nested conversations carry no interceptor in the runtime, so the feature stores them
from what each event's context shows. Raise `DefaultSessionService.builder().maxTreeDepth(..)`
to the nesting depth of your graphs; the default is one level.

---

## SessionCheckpointer

`feature.checkpointer()` is a `Checkpointer` over the log, for the runtime's
`CheckpointerFeature`: each checkpoint is a `CHECKPOINT` event of the root session holding
the resume chain without its conversations, which are the sessions themselves. Resuming
reads the newest checkpoint of the run and rebuilds each level's conversation from the
session of its path. One store therefore gives memory, replay, resume and the live stream,
and no message is stored twice. A run that completed and is run again with the same
`RunIdentification` replays from its last checkpoint without executing its nodes.

---

## Interrupts and the parked turn

A run interrupted by `Nodes.interrupt(..)` or by the tool-confirmation gate parks its
session: an `INTERACTION` event records where the question was asked and what it is, and
the turn ends with `REQUIRES_ACTION` naming it. `SessionService.pendingInteraction(sessionId)`
returns the question a session waits on. `resumeOutput(input, runId, answer)` on any agent
instance, with the checkpointer enabled, records the answer as an `INTERACTION` event and
resumes the run where it stopped.

---

## BudgetFeature and ToolConfirmationFeature

```java
agent = ModelAwareGraphAgent.builder(Graphs.toolCallLoop(), chatModel)
    .tools(new WeatherTools())
    .enable(feature)
    .enable(new CheckpointerFeature(feature.checkpointer()))
    .enable(new BudgetFeature(sessionService, BudgetPolicy.fixed(Budget.ofTotalTokens(200_000))))
    .enable(new ToolConfirmationFeature(ToolConfirmationPolicy.forTools("bash")))
    .build();
```

- `BudgetFeature` is a `ModelCallInterceptor`: before every model call it checks the tree's
  usage against the budget the policy gives the root, ends the turn with `BUDGET_REACHED` and
  refuses the call with a `BudgetReachedException` once the budget is reached. The same
  `Budget` and `BudgetPolicy` the `ChatClient` module's `BudgetAdvisor` uses.
- `ToolConfirmationFeature` is a `ToolCallInterceptor`: a call the `ToolConfirmationPolicy`
  gates interrupts the run with a `PendingToolCall` as the question. Resume with a
  `ToolConfirmation`: an allowed call runs through the agent's own tools, a denied one
  answers the model with its message; the `SessionFeature` records the decision as the
  answer to the question the run parked on.

---

## Following a session as a run

`AgentRun.events()` is the live stream of one run on one node, model chunks included.
`SessionService.subscribe(sessionId)` is the live stream of what the session kept, on the
node that writes it, and `EventFilter.after(eventId)` fills the gap on reconnect. The
`reactor-adapter` of stream-source turns the one into the other:

```java
Source<SessionEvent> source = SourceAdapter.toSource(sessionService.subscribe(sessionId));
```

The stored events are session events, not the runtime's records: a `NODE` or `MODEL_CALL`
event carries the step's name, phase and JSON values, not the `Prompt` or `ChatResponse`
objects, which were never stored.

## Limitations

- A nested conversation seeded by a custom propagation is stored from its first event on,
  seed included; messages a node contributes to a nested conversation after its last event
  are stored by the next event from that path, or not at all if none follows.
- A child's agent snapshot is its node's name; the runtime does not expose the nested
  agent's definition to a feature.
- The question of an interrupt is stored as JSON; a value read back from a store is a map or
  a scalar, and the resume type is not recorded because the runtime's event does not carry it.
- Streamed model chunks are not stored; subscribe to the run for them and to the session
  for what was kept.
- Without the checkpointer, a re-attempt of a run re-records the `MESSAGE_RECEIVED` marker of
  a nested agent that completes again; with it, a completed node is skipped and nothing repeats.
- An agent exposed as a tool runs under the tool's name, which the runtime does not make
  unique when the model calls it more than once in a run, so repeated calls share one child
  session; its log then holds the messages of every call.
- The module depends on a `spring-ai-agent` snapshot that is not published yet, so it is the
  integration that validates the design, not part of a release until the runtime is.
