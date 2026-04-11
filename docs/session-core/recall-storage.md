# Recall Storage

`SessionEventTools` implements the MemGPT *Recall Storage* pattern: the full verbatim
event log is always retained and searchable by keyword, even after context compaction has
pruned older events from the active context window. The agent can surface any prior
exchange on demand rather than relying solely on what fits in the prompt.

---

## Registration

```java
// Default page size (EventFilter.DEFAULT_PAGE_SIZE = 10)
SessionEventTools tools = SessionEventTools.builder(sessionService).build();

// Custom page size
SessionEventTools tools = SessionEventTools.builder(sessionService)
    .pageSize(20)
    .build();

ChatClient client = ChatClient.builder(chatModel)
    .defaultTools(tools)
    .defaultAdvisors(advisor)   // SessionMemoryAdvisor sets chat_memory_session_id
    .build();
```

!!! tip
    Register `SessionMemoryAdvisor` alongside `SessionEventTools`. The advisor writes the
    `chat_memory_session_id` context key that the tool uses to resolve the active session.
    Without the advisor, the tool falls back to the literal session ID `"default"`.

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

1. Resolves the session ID from `ToolContext` using the `chat_memory_session_id` key
   (the same key written by `SessionMemoryAdvisor`). If the key is absent or blank, a
   `WARN`-level log is emitted and the tool falls back to the literal session ID
   `"default"`.
2. Calls `SessionService.getEvents(sessionId, EventFilter.keywordSearch(query, page, pageSize))`.
3. `EventFilter.matches()` applies a case-insensitive substring check on each event's
   `message.getText()` before pagination is applied.
4. Returns structured JSON, or `"No results found."` when nothing matches.

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
