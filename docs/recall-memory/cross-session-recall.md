# Cross-Session Recall

`CrossSessionRecallTools` searches **every session belonging to one user**, not just the
session the current request is scoped to. It exists for background/maintenance agents —
the motivating case is a memory-consolidation agent that periodically mines a user's full
conversation history for signal (corrections, explicit "remember this" requests,
decisions) — not for the live conversational agent answering the user directly.

---

## How this differs from `conversation_search`

| | [`conversation_search`](recall-storage.md) | `cross_session_search` |
|---|---|---|
| Scope | One session | Every session belonging to one user |
| Session/user resolved from | `ToolContext`, per request (the caller passes `ChatMemory.CONVERSATION_ID`) | Bound once at **construction time** |
| Who can change the scope | The calling application, via `.toolContext(...)` | Nobody at runtime — fixed for the tool instance's lifetime |
| Intended caller | The live conversational agent, mid-chat | A background/maintenance agent running out-of-band |
| Query power | Single keyword | Multi-term (`ANY`/`ALL`), plus `since` date scoping |
| Result shape | `timestamp`, `type`, `text` | Same, plus `sessionId` |

!!! warning "Register it only on background agents"
    The user is bound in Java code, never taken as a tool parameter, so a manipulated
    prompt cannot make the tool scan another user's sessions. Register it only on an
    isolated background-agent `ChatClient`, never alongside `conversation_search` on the
    live user-facing agent.

---

## Registration

```java
CrossSessionRecallTools tools = CrossSessionRecallTools.builder(sessionService, "alice").build();

// Custom page size (default EventFilter.DEFAULT_PAGE_SIZE = 10)
CrossSessionRecallTools tools = CrossSessionRecallTools.builder(sessionService, "alice")
    .pageSize(20)
    .build();

ChatClient client = ChatClient.builder(chatModel)
    .defaultTools(tools)
    .build();
```

Unlike `SessionEventTools`, it reads nothing from `ToolContext`, so no advisor or tool
context setup is needed.

---

## Tool signature

The `cross_session_search` tool is automatically discovered by Spring AI's tool mechanism.

| Parameter | Required | Description |
|---|---|---|
| `innerThought` | yes | Agent's private reasoning (not returned to the caller) |
| `query` | yes | Case-insensitive keyword, or comma-separated keywords such as `"we decided, let's go with"` (up to 20 terms per call). A blank query, or one with no usable terms (e.g. `","`), is rejected rather than treated as "no filter" |
| `matchMode` | no | `"any"` (default, at least one term) or `"all"` (every term in the same event). Any other value is rejected |
| `since` | no | ISO-8601 instant (e.g. `2026-07-01T00:00:00Z`); only events at or after this time are considered, useful for a periodic agent that only wants what's new. Omit to search the full history. A malformed value is rejected with an error naming the expected format |
| `page` | no | Zero-indexed result page; defaults to `0`; negative values are clamped to `0` |

The 20-term cap exists because each term becomes its own predicate (and, on JDBC, its own
bound SQL parameter), so an unbounded count would let one call grow the query without
limit. It is a sanity limit, not a security boundary: every term is always bound, never
concatenated into SQL.

Results from all of the user's sessions are sorted chronologically and returned as a JSON
array:

```json
[
  { "sessionId": "sess-123", "timestamp": "2025-06-01T12:00:00Z", "type": "user", "text": "We decided to use PostgreSQL" },
  { "sessionId": "sess-456", "timestamp": "2025-06-03T09:15:00Z", "type": "user", "text": "Actually, let's go with blue-green deploys" }
]
```

When nothing matches: `"No results found."` is returned.

---

## Design notes

- **Read-only.** `CrossSessionRecallTools` has no append/compact/delete capability — it can
  only call `SessionService.findEventsByUserId(...)`.
- **One query per page.** Each call runs a single `findEventsByUserId(userId, filter)`
  with the page in the filter. The JDBC repository answers it with one query that joins
  sessions and events, sorts by timestamp and applies the page in the database; the
  in-memory repository sorts and windows the union in memory. A custom repository gets
  the in-memory behaviour from the default method and should override it with a pushed
  down query.
- **Synthetic summaries are skipped.** The filter sets `excludeSynthetic(true)`: a
  summary only paraphrases real events that are still in the log, archived or not, so
  searching it too would return the same facts twice.
- **No regex.** Only plain-substring `keywords`/`matchMode` search is exposed, never a
  raw regex string a model could supply; see the
  [ReDoS warning](../session-management/event-filtering.md#static-factory-shortcuts).

---

## See also

- [Recall Storage](recall-storage.md) — the single-session `conversation_search` tool this
  complements
- [Event Filtering](../session-management/event-filtering.md) — the full `EventFilter` API, including
  `keywords`/`matchMode`/`pattern`
