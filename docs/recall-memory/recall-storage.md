# Recall Storage

`SessionEventTools` implements the MemGPT *Recall Storage* pattern: the full verbatim
event log stays searchable by keyword, even after compaction has pruned older events from
the active context window, so the agent can surface any prior exchange on demand.

This works because compaction **archives** rather than deletes. The advisor injects only
the `EventFilter.active()` view, while `conversation_search` searches the whole log,
archived events included.

!!! tip "Searching across a user's other sessions"
    `conversation_search` is scoped to the one session in the request's tool context; the
    model never chooses which session it searches. For a background agent that mines
    **every** session of a user, see [Cross-Session Recall](cross-session-recall.md).

---

## Registration

```java
// Default page size (EventFilter.DEFAULT_PAGE_SIZE = 10)
SessionEventTools tools = SessionEventTools.builder(sessionService).build();

// Custom page size
SessionEventTools tools = SessionEventTools.builder(sessionService)
    .pageSize(20)
    .build();

// Sub-agent in a multi-agent session: only search events visible to this branch
SessionEventTools researcherTools = SessionEventTools.builder(sessionService)
    .branch("orch.researcher")
    .build();

ChatClient client = ChatClient.builder(chatModel)
    .defaultTools(tools)
    .defaultAdvisors(advisor)
    .build();

client.prompt()
    .user(question)
    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))   // read by SessionMemoryAdvisor
    .toolContext(Map.of(ChatMemory.CONVERSATION_ID, sessionId))      // read by conversation_search
    .call()
    .content();
```

!!! warning "Pass the session ID to the tool context too"
    Spring AI does not copy advisor parameters into the `ToolContext`. `SessionMemoryAdvisor`
    reads the session ID from the advisor context, while `conversation_search` reads it from
    the `ToolContext`, so pass it to both on every request, as shown above. Both use the
    `chat_memory_conversation_id` key (`ChatMemory.CONVERSATION_ID`). If the tool context has
    no session ID, the tool logs a `WARN`, returns an error message to the model and does
    not search.

---

## Tool signature

The `conversation_search` tool is automatically discovered by Spring AI's tool mechanism.

| Parameter | Required | Description |
|---|---|---|
| `innerThought` | yes | Agent's private reasoning (not returned to the caller) |
| `query` | yes | Case-insensitive keyword to search for |
| `page` | no | Zero-indexed result page (oldest matches first); defaults to `0`; negative values are clamped to `0`. The model can call again with `page=1`, `page=2`, … to walk large histories. Page size is set with the builder's `pageSize` |

Results are returned in chronological order as a JSON array:

```json
[
  { "timestamp": "2025-06-01T12:00:00Z", "type": "user",      "text": "Tell me about Spring AI" },
  { "timestamp": "2025-06-01T12:00:01Z", "type": "assistant", "text": "Spring AI is a framework..." }
]
```

When nothing matches: `"No results found."` is returned.

---

## How it works

The tool calls `SessionService.getEvents(sessionId, EventFilter.keywordSearch(query, page, pageSize))`:
a case-insensitive substring match on each event's `message.getText()`, applied before
pagination. Synthetic summary events produced by `RecursiveSummarizationCompactionStrategy`
are searched too.

!!! note "Branch isolation"
    By default `conversation_search` searches every event in the session, including events
    from every sub-agent branch. In a multi-agent session, give each sub-agent a tool
    instance built with `.branch("orch.researcher")`. It then applies the same visibility
    rule as `EventFilter.forBranch(...)`: root events, the agent's own events and its
    ancestors' events are searchable, but peer sub-agents' events are not.

---

## See also

- [Cross-Session Recall](cross-session-recall.md) — the analogous tool scoped to *every*
  session belonging to a user, for background/maintenance agents
- [Event Filtering](../session-management/event-filtering.md) — the full `EventFilter` API this tool builds on
