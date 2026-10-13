# Event Filtering

`EventFilter` controls which events are returned by `SessionService.getEvents()`: the
per-event criteria (all set criteria must match), the view (the whole log or the active
view), a retrieval **window** (`all`, `last(n)`, `page(p, size)` or `after(eventId)`) and an
**order**.

---

## Static factory shortcuts

```java
// All events (default — no filtering applied)
service.getEvents(id, EventFilter.all());

// Most recent N events, extended to the start of the turn the window lands in
service.getEvents(id, EventFilter.lastN(20));

// Everything logged after an event the client has seen — a cursor
service.getEvents(id, EventFilter.after(lastSeenEventId));

// Exclude synthetic summary events — only real conversation turns
service.getEvents(id, EventFilter.realOnly());

// The active view's message events — the active context window only
service.getEvents(id, EventFilter.active());

// Keyword search — first page (default page size 10)
service.getEvents(id, EventFilter.keywordSearch("Spring AI"));

// Keyword search — explicit page and page size
service.getEvents(id, EventFilter.keywordSearch("Spring AI", 1, 5));

// Multi-term search — match ANY of the terms (case-insensitive substring per term)
service.getEvents(id, EventFilter.keywordsSearch(List.of("no,", "don't", "actually"), MatchMode.ANY));

// Multi-term search — match ALL of the terms
service.getEvents(id, EventFilter.keywordsSearch(List.of("we decided", "going forward"), MatchMode.ALL));

// Regular-expression search — first page (default page size 10)
service.getEvents(id, EventFilter.patternSearch(Pattern.compile("\\bwe decided\\b")));
```

!!! danger "Never compile an untrusted string into the `pattern` argument"
    `patternSearch(Pattern)` and `Builder.pattern(Pattern)` take an already-compiled
    `java.util.regex.Pattern` — a **Java-level API**, not a tool-call parameter. Never call
    `Pattern.compile(...)` on a string sourced from a user, an LLM tool-call argument, or
    any other untrusted input and pass the result here: an attacker-chosen expression can
    exhibit catastrophic backtracking (ReDoS) when evaluated against attacker-influenced
    message text. Only pass patterns compiled from a fixed or developer-authored
    expression. This is exactly why no `@Tool`-annotated method in this library accepts a
    raw regex string — see [Cross-Session Recall](../recall-memory/cross-session-recall.md), which
    deliberately exposes only plain-substring `keywords`/`matchMode` search instead.

---

## Builder

```java
EventFilter filter = EventFilter.builder()
    .from(Instant.parse("2025-01-01T00:00:00Z"))          // exclude events before
    .to(Instant.parse("2025-12-31T23:59:59Z"))             // exclude events after
    .messageTypes(Set.of(MessageType.USER,
                         MessageType.ASSISTANT))           // keep only these types
    .excludeSynthetic(true)                                // exclude summary events
    .lastN(50)                                             // window: newest 50 matches, whole turns
    .keywords(List.of("no,", "actually"))                  // multi-term substring match
    .matchMode(MatchMode.ANY)                               // ANY (default) or ALL of keywords
    .order(EventFilter.Order.DESC)                         // newest first
    .build();

EventFilter since = EventFilter.builder()
    .after(lastSeenEventId, 100)                           // window: at most 100 events after it
    .eventTypes(Set.of(EventType.MESSAGE, EventType.STATUS))
    .build();
```

`keyword(String)` is a one-term `keywords`. `keywords` and `pattern` are independent
criteria combined with AND; in practice, callers set one. The window is one value: `lastN`,
`page`/`pageSize`, `after` and `window(Window)` each replace it.

---

## Fields reference

| Field | Type | Description |
|-------|------|-------------|
| `from` | `Instant` | Exclude events before this instant |
| `to` | `Instant` | Exclude events after this instant |
| `messageTypes` | `Set<MessageType>` | Keep only events carrying a message of these message types. A `MESSAGE_SENT` / `MESSAGE_RECEIVED` tree marker carries one too; add `eventTypes` to narrow to `MESSAGE` |
| `eventTypes` | `Set<EventType>` | Keep only events of these event types; unset means every type. `EventFilter.active()` sets `MESSAGE` |
| `excludeSynthetic` | `boolean` | When `true`, synthetic summary events are excluded |
| `keywords` | `List<String>` | Case-insensitive substring terms matched against `message.getText()`, combined per `matchMode`; `Builder.keyword(String)` sets one. The text criteria apply to every event that carries a message, tree markers included |
| `matchMode` | `EventFilter.MatchMode` | `ANY` (at least one term present) or `ALL` (every term present) — only meaningful when `keywords` is set. Import `org.springframework.ai.session.EventFilter.MatchMode` |
| `pattern` | `Pattern` | Compiled regular expression matched against `message.getText()` via `Matcher.find()`. Applied by the service after the store's read, before the window (see [Patterns](#patterns)). **Only ever pass a developer-authored `Pattern`** — see the ReDoS warning above |
| `window` | `EventFilter.Window` | `Window.ALL` (default), `Window.last(n)`, `Window.page(page, size)` or `Window.after(eventId[, limit])`; the builder's `lastN`, `page`/`pageSize` and `after` set it. See [Windows keep turns whole](#windows-keep-turns-whole) and [Cursor reads](#cursor-reads) |
| `order` | `EventFilter.Order` | `ASC` (oldest first, the default when unset) or `DESC` (newest first); applied to the windowed result |
| `activeView` | `boolean` | When `true`, the filter applies to the active view defined by the newest compaction boundary (pinned events, summary, preserved events, then everything appended since) instead of the whole log — used by `EventFilter.active()`. A boundary names message events, so a non-message event logged before it is not in the view; read runtime state without this flag. See [Event lifecycle](concepts.md#event-lifecycle) |

---

## Windows keep turns whole

A `lastN` window on one session is a lower bound. If its oldest event is not a
[turn start](concepts.md#turn) (a `USER` event, real or synthetic), the window is extended
back to the nearest turn start before it, so the result never begins with an assistant reply
whose question was cut off, a tool result without its tool call, or a summary without its
shadow prompt. A tool call is therefore never returned without its results, and a prompt
built from the window is one every provider accepts.

```
log:          U1 A1 U2 A2[tool call] T2[result] A2'
lastN(2):     U2 A2[tool call] T2[result] A2'      extended from "T2 A2'" to the turn start
lastN(5):     U1 A1 U2 A2[tool call] T2[result] A2'
```

The extension is bounded by one turn and skipped when it cannot apply:

- **Text searches are plain.** A filter with `keyword`, `keywords` or `pattern` returns
  exactly the newest N matches; a search result is not a prompt.
- **No turn start before the window.** A window that lands in a preamble of stored
  system messages, or a `messageTypes` filter that hides `USER` events, returns the plain
  window.
- **Pages and cursors are plain**, and so are cross-session reads (`findEvents(sessionIds, filter)`), where turns
  do not exist.

`EventFilter.apply(List)` is the reference implementation; the JDBC repository pushes the
same rule down as one bounded extra query when the window lands mid-turn.

---

## Cursor reads

`after(eventId)` returns the matching events logged after the given one, oldest first, and
`after(eventId, limit)` at most `limit` of them: what happened since an event the client
has seen. The cursor event is located in the session's log before the criteria apply, so it
need not match them: a client that keeps the id of the last `STATUS` event it rendered can
ask for the messages after it. A cursor that names no event of the session throws
`IllegalArgumentException`; so does a cursor on a cross-session read, where there is no
single log.

Pair a cursor with a live subscription to catch up without missing or duplicating
anything: subscribe first, read after the last event seen, then skip the live events whose
ids the read returned. See [Live events](concepts.md#live-events).

```java
Disposable live = service.subscribe(sessionId).subscribe(ui::render);
service.getEvents(sessionId, EventFilter.after(lastSeenId)).forEach(ui::render);
```

---

## Patterns

A `pattern` is not part of the repository contract: a Java regular expression has no
portable SQL equivalent, so stores ignore it and `SessionService.getEvents` /
`findEvents` apply it after the read, before the window. The store is asked for the
criteria without the pattern and without the window (a cursor keeps its position but not
its limit); the service matches the pattern, then windows and orders the matches. A pattern
read therefore scans every event the other criteria admit; narrow it with `eventTypes`,
`messageTypes` or a time range when the log is long. The repository's `findEvents` returns
the unmatched page when called directly with a pattern.

---

## Constraints

The window records and the builder enforce these rules:

- **`lastN`, `page`/`pageSize` and `after` are one window**: the last one set wins, except
  that a `pageSize` together with another window throws `IllegalArgumentException`.
- **`lastN` and `pageSize` must be > 0; `page` must be >= 0; a cursor's `limit` must be > 0.**
- **Setting `page` without `pageSize`** throws `IllegalArgumentException`.
- **Setting `pageSize` without `page`** is allowed — `page` defaults to `0` (first page).
- **`keywords`** are normalised: blank entries are dropped and the rest lowercased. An
  empty or all-blank list, or a blank `keyword`, becomes `null` (no filter).
- **`matchMode`** defaults to `ANY` when `keywords` is set. If `keywords` is `null`,
  `matchMode` is forced to `null` too, even if you set one.
- **`messageTypes`** is normalised on construction: an empty set becomes `null`
  (equivalent to no type filter).

---

## Merging filters

`EventFilter.merge(other)` merges two filters: every non-null field from `other` replaces
the corresponding field from `this`; the two boolean flags, `excludeSynthetic` and
`activeView`, are OR-ed so either side can opt in. `SessionMemoryAdvisor` uses it to
combine its default filter with a
[per-request override](../chat-client/chat-client.md#per-request-filter-override), and always merges in
`EventFilter.active()` so the prompt never sees the events a compaction covered:

```java
EventFilter advisorDefault = EventFilter.lastN(50);
EventFilter requestOverride = EventFilter.lastN(5);

EventFilter merged = advisorDefault.merge(requestOverride);
// merged.window() == Window.last(5)  (request-level wins)
```

The window is one value: when `other` has a window other than `Window.ALL`, it replaces
the base filter's window, so a per-request paginated search can override an advisor-level
`lastN` window (and the other way round). The `order` of `other` wins when it is set.

```java
EventFilter merged = EventFilter.lastN(20).merge(EventFilter.keywordSearch("spring", 1, 5));
// merged.window() == Window.page(1, 5)
```

---

## Write-side filtering

`EventFilter` is a **read-side** filter: events it excludes remain in storage — they are
only hidden from the retrieved history. To control which messages get **persisted** in
the first place, use `MessageFilter` on the `SessionMemoryAdvisor` builder. See
[ChatClient Integration → Filtering what gets persisted](../chat-client/chat-client.md#filtering-what-gets-persisted-messagefilter).
