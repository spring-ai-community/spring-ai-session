# Recall Storage

`SessionEventTools` implements the MemGPT *Recall Storage* pattern: the full verbatim
event log is always retained and searchable by keyword, even after context compaction has
pruned older events from the active context window. The agent can surface any prior
exchange on demand rather than relying solely on what fits in the prompt.

This works because compaction **archives** rather than deletes. Events dropped from the
active window are flagged `SessionEvent.isArchived()` and kept in the log. The active
context window the advisor injects is the `EventFilter.active()` view (archived events
excluded), while `conversation_search` searches the whole log (archived events included).

!!! tip "Need to search across a user's other sessions, not just this one?"
    `conversation_search` is deliberately scoped to a single session, resolved from the
    request's tool context — the model never chooses which session it searches. For a background/maintenance agent that needs to mine signal across **every**
    session a user has, see [Cross-Session Recall](cross-session-recall.md) instead.

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
    no session ID, the tool returns an error message to the model and does not search.

---

## Tool signature

The `conversation_search` tool is automatically discovered by Spring AI's tool mechanism.

| Parameter | Required | Description |
|---|---|---|
| `innerThought` | yes | Agent's private reasoning (not returned to the caller) |
| `query` | yes | Case-insensitive keyword to search for |
| `page` | no | Zero-indexed result page; defaults to `0`; negative values are clamped to `0` |

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

1. Resolves the session ID from `ToolContext` using the `chat_memory_conversation_id` key
   (`ChatMemory.CONVERSATION_ID`, the same key `SessionMemoryAdvisor` reads from the advisor
   context). If the key is absent or blank, a `WARN`-level log is emitted and the tool
   returns an error message without searching.
2. Calls `SessionService.getEvents(sessionId, EventFilter.keywordSearch(query, page, pageSize))`.
3. `EventFilter.matches()` applies a case-insensitive substring check on each event's
   `message.getText()` before pagination is applied.
4. Returns structured JSON, or `"No results found."` when nothing matches.

!!! note "Branch isolation"
    By default `conversation_search` searches every event in the session, including events
    from every sub-agent branch. In a multi-agent session, give each sub-agent a tool
    instance built with `.branch("orch.researcher")`. It then applies the same visibility
    rule as `EventFilter.forBranch(...)`: root events, the agent's own events and its
    ancestors' events are searchable, but peer sub-agents' events are not.

!!! note "Synthetic events are searchable"
    Synthetic summary events produced by `RecursiveSummarizationCompactionStrategy` are
    included in keyword search — their summary text is part of the recall history too.

---

## Pagination

Page size defaults to `EventFilter.DEFAULT_PAGE_SIZE` (10) and is configurable via the
builder (`SessionEventTools.builder(sessionService).pageSize(20).build()`). Pass
`page=1`, `page=2`, … to walk through large histories:

```
page=0  →  10 oldest matching events
page=1  →  next 10 matching events
...
```

The model can call `conversation_search` multiple times with incrementing `page` values to
paginate through the full history until it finds what it needs.

---

## See also

- [Cross-Session Recall](cross-session-recall.md) — the analogous tool scoped to *every*
  session belonging to a user, for background/maintenance agents
- [Event Filtering](event-filtering.md) — the full `EventFilter` API this tool builds on
