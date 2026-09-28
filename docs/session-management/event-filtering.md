# Event Filtering

`EventFilter` controls which events are returned by `SessionService.getEvents()`. All
non-null conditions must match for an event to be included.

---

## Static factory shortcuts

```java
// All events (default — no filtering applied)
service.getEvents(id, EventFilter.all());

// Most recent N events, extended to the start of the turn the window lands in
service.getEvents(id, EventFilter.lastN(20));

// Exclude synthetic summary events — only real conversation turns
service.getEvents(id, EventFilter.realOnly());

// Exclude archived events — the active context window only
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
    .lastN(50)                                             // newest 50 matches, whole turns
    .keyword("Spring AI")                                  // case-insensitive substring
    .keywords(List.of("no,", "actually"))                  // multi-term substring match
    .matchMode(MatchMode.ANY)                               // ANY (default) or ALL of keywords
    .pattern(Pattern.compile("\\bwe decided\\b"))          // compiled regex — developer-authored only
    .build();
```

`keyword`, `keywords` and `pattern` are independent criteria combined with AND: set more
than one and an event must satisfy all of them. In practice, callers set exactly one.

---

## Fields reference

| Field | Type | Description |
|-------|------|-------------|
| `from` | `Instant` | Exclude events before this instant |
| `to` | `Instant` | Exclude events after this instant |
| `messageTypes` | `Set<MessageType>` | Keep only events of these message types |
| `excludeSynthetic` | `boolean` | When `true`, synthetic summary events are excluded |
| `lastN` | `Integer` | The most recent N matching events (must be > 0), extended back to the start of the turn the window lands in; see [Windows keep turns whole](#windows-keep-turns-whole) |
| `keyword` | `String` | Case-insensitive substring match on `message.getText()` |
| `keywords` | `List<String>` | Multiple case-insensitive substring terms, combined per `matchMode` |
| `matchMode` | `EventFilter.MatchMode` | `ANY` (at least one term present) or `ALL` (every term present) — only meaningful when `keywords` is set. Import `org.springframework.ai.session.EventFilter.MatchMode` |
| `pattern` | `Pattern` | Compiled regular expression matched against `message.getText()` via `Matcher.find()`. **Only ever pass a developer-authored `Pattern`** — see the ReDoS warning above |
| `page` | `Integer` | Zero-indexed page in chronological order (oldest first, page 0 = oldest) |
| `pageSize` | `Integer` | Results per page. Unset means no pagination; the `*Search` factories use 10. Must be > 0 if set |
| `excludeArchived` | `boolean` | When `true`, archived (compacted-out) events are excluded — used by `EventFilter.active()` |

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
- **Pages are plain**, and so are cross-session reads (`findEventsByUserId`), where turns
  do not exist.

`EventFilter.apply(List)` is the reference implementation; the JDBC repository pushes the
same rule down as one bounded extra query when the window lands mid-turn.

---

## Constraints

The compact constructor enforces these rules at construction time:

- **`lastN` and `pageSize` are mutually exclusive** — setting both throws
  `IllegalArgumentException`.
- **Setting `page` without `pageSize`** throws `IllegalArgumentException`.
- **Setting `pageSize` without `page`** is allowed — `page` defaults to `0` (first page).
- **`keyword` and `keywords`** are normalised: blank values (or list entries) are dropped
  and the rest lowercased. A blank `keyword`, or an empty or all-blank `keywords` list,
  becomes `null` (no filter).
- **`matchMode`** defaults to `ANY` when `keywords` is set. If `keywords` is `null`,
  `matchMode` is forced to `null` too, even if you set one.
- **`messageTypes`** is normalised on construction: an empty set becomes `null`
  (equivalent to no type filter).

---

## Merging filters

`EventFilter.merge(other)` merges two filters: every non-null field from `other` replaces
the corresponding field from `this`; the two boolean flags, `excludeSynthetic` and
`excludeArchived`, are OR-ed so either side can opt in. `SessionMemoryAdvisor` uses it to
combine its default filter with a
[per-request override](../chat-client/chat-client.md#per-request-filter-override), and always merges in
`EventFilter.active()` so the prompt never sees archived events:

```java
EventFilter advisorDefault = EventFilter.lastN(50);
EventFilter requestOverride = EventFilter.lastN(5);

EventFilter merged = advisorDefault.merge(requestOverride);
// merged.lastN() == 5  (request-level wins)
```

The retrieval modifier — `lastN` or `page`/`pageSize` — is merged as a single unit. If
`other` sets either form, it replaces the base filter's modifier completely, so a
per-request paginated search can override an advisor-level `lastN` window (and the other
way round) without tripping the "`lastN` and `page`/`pageSize` are mutually exclusive"
check:

```java
EventFilter merged = EventFilter.lastN(20).merge(EventFilter.keywordSearch("spring", 1, 5));
// merged.lastN() == null, merged.page() == 1, merged.pageSize() == 5
```

---

## Write-side filtering

`EventFilter` is a **read-side** filter: events it excludes remain in storage — they are
only hidden from the retrieved history. To control which messages get **persisted** in
the first place, use `MessageFilter` on the `SessionMemoryAdvisor` builder. See
[ChatClient Integration → Filtering what gets persisted](../chat-client/chat-client.md#filtering-what-gets-persisted-messagefilter).
