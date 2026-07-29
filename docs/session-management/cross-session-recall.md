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
| Session/user resolved from | `ToolContext`, automatically, per request | Bound once at **construction time** |
| Who can change the scope | The calling application, via `SessionMemoryAdvisor` | Nobody at runtime — fixed for the tool instance's lifetime |
| Intended caller | The live conversational agent, mid-chat | A background/maintenance agent running out-of-band |
| Query power | Single keyword | Multi-term (`ANY`/`ALL`), plus `since` date scoping |
| Result shape | `timestamp`, `type`, `text` | Same, plus `sessionId` |

The scope difference is deliberate, not incidental. The target user is bound in Java code
via [`builder(SessionService, String)`](#registration) rather than accepted as a tool-call
parameter, specifically so a misbehaving or manipulated prompt cannot talk the tool into
scanning a different user's sessions — the model never gets a parameter through which it
could choose. Register `CrossSessionRecallTools` only on the `ChatClient` instances that
should have this broader capability (e.g. an isolated background-agent `ChatClient`); never
alongside `conversation_search` on the live user-facing agent, which should keep the
narrower, request-scoped tool.

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

There is no advisor dependency here — unlike `SessionEventTools`, `CrossSessionRecallTools`
does not read anything from `ToolContext`. The `userId` passed to `builder(...)` is fixed
for the tool instance's entire lifetime.

---

## Tool signature

The `cross_session_search` tool is automatically discovered by Spring AI's tool mechanism.

| Parameter | Required | Description |
|---|---|---|
| `innerThought` | yes | Agent's private reasoning (not returned to the caller) |
| `query` | yes | Case-insensitive keyword, or comma-separated keywords (up to 20 terms per call) |
| `matchMode` | no | `"any"` (default) or `"all"` — how multiple comma-separated keywords in `query` combine |
| `since` | no | ISO-8601 instant (e.g. `2026-07-01T00:00:00Z`); only events at or after this time are considered. Omit to search the full history |
| `page` | no | Zero-indexed result page; defaults to `0`; negative values are clamped to `0` |

Results are aggregated across every session belonging to the configured user, sorted
chronologically by the actual event `Instant` (not a string comparison — see
[Design notes](#design-notes)), and returned as a JSON array:

```json
[
  { "sessionId": "sess-123", "timestamp": "2025-06-01T12:00:00Z", "type": "user", "text": "We decided to use PostgreSQL" },
  { "sessionId": "sess-456", "timestamp": "2025-06-03T09:15:00Z", "type": "user", "text": "Actually, let's go with blue-green deploys" }
]
```

When nothing matches: `"No results found."` is returned.

---

## Multi-term queries

Supply comma-separated terms in `query` to search for more than one term at once:

```
query = "we decided, let's go with"        matchMode = (omitted → "any")
```

matches an event whose text contains **either** term. Set `matchMode = "all"` to require
**every** term to be present in the same event's text. A `query` that yields zero usable
terms after splitting and trimming (e.g. `","` or `", "`) is rejected with an error rather
than silently falling back to "no filter" — see [Design notes](#design-notes).

The term count is capped at 20 per call (`CrossSessionRecallTools.MAX_QUERY_TERMS`). Each
term becomes its own predicate — and, on a JDBC-backed `SessionService`, its own bound SQL
parameter — so an unbounded term count would let a single call grow the generated query
without limit. This is a sanity limit, not a security boundary in itself: every term is
still safely bound, never concatenated into SQL text, regardless of the cap.

---

## Date-scoped queries

Pass `since` to only consider events at or after a given instant — useful for an agent
that runs periodically and only wants to see what's new since its last pass:

```
since = "2026-07-01T00:00:00Z"
```

A malformed `since` (e.g. a date without a time component) is rejected with a clear error
naming the expected format, rather than propagating a raw parser exception.

---

## Design notes

- **Read-only.** `CrossSessionRecallTools` has no append/compact/delete capability — it can
  only call `SessionService.findByUserId(...)` and `SessionService.getEvents(...)`.
- **Aggregation happens client-side.** Because results span multiple sessions, each
  matching session is queried individually via `SessionService.getEvents(sessionId,
  filter)`, and the results are merged and sorted afterward — by the events' actual
  `Instant` timestamps, not by comparing their string renderings (`Instant.toString()`
  omits the fractional-seconds component when it is exactly zero but includes it
  otherwise, so two rendered timestamps of different lengths do not always compare in
  chronological order).
- **No per-session pagination pushdown.** Each session's full matching event set is
  fetched before pagination is applied to the aggregate — cost scales with a user's total
  historical event count across all sessions, not just the page size requested. Fine for
  typical usage; worth keeping in mind for a user with a very large number of long-lived
  sessions.
- **`pattern`/regex is intentionally not exposed here.** `EventFilter` supports a
  `pattern` criterion (see [Event Filtering](event-filtering.md)), but
  `cross_session_search` only exposes plain-substring `keywords`/`matchMode` search — never
  a raw regex string a model could supply. See the ReDoS warning on
  [Event Filtering](event-filtering.md#static-factory-shortcuts) for why.

---

## See also

- [Recall Storage](recall-storage.md) — the single-session `conversation_search` tool this
  complements
- [Event Filtering](event-filtering.md) — the full `EventFilter` API, including
  `keywords`/`matchMode`/`pattern`
