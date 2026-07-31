# Event Filtering

`EventFilter` controls which events are returned by `SessionService.getEvents()`. All
non-null conditions must match for an event to be included. Criteria are composable via
the builder.

---

## Static factory shortcuts

For the most common cases, `EventFilter` exposes static factories:

```java
// All events (default — no filtering applied)
service.getEvents(id, EventFilter.all());

// Most recent N events
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

// Events visible to a specific agent branch (own + ancestor events only)
service.getEvents(id, EventFilter.forBranch("orch.researcher"));
```

!!! danger "Never compile an untrusted string into the `pattern` argument"
    `patternSearch(Pattern)` and `Builder.pattern(Pattern)` take an already-compiled
    `java.util.regex.Pattern` — a **Java-level API**, not a tool-call parameter. Never call
    `Pattern.compile(...)` on a string sourced from a user, an LLM tool-call argument, or
    any other untrusted input and pass the result here: an attacker-chosen expression can
    exhibit catastrophic backtracking (ReDoS) when evaluated against attacker-influenced
    message text. Only pass patterns compiled from a fixed or developer-authored
    expression. This is exactly why no `@Tool`-annotated method in this library accepts a
    raw regex string — see [Cross-Session Recall](cross-session-recall.md), which
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
    .lastN(50)                                             // keep newest 50 matches
    .keyword("Spring AI")                                  // case-insensitive substring
    .keywords(List.of("no,", "actually"))                  // multi-term substring match
    .matchMode(MatchMode.ANY)                               // ANY (default) or ALL of keywords
    .pattern(Pattern.compile("\\bwe decided\\b"))          // compiled regex — developer-authored only
    .branch("orch.researcher")                             // branch isolation
    .build();
```

`keyword`, `keywords`, and `pattern` are independent criteria that all compose with
AND-together semantics in `matches()` — set more than one and an event must satisfy every
one of them, not just one. In practice, callers set exactly one of the three.

---

## Fields reference

| Field | Type | Description |
|-------|------|-------------|
| `from` | `Instant` | Exclude events before this instant |
| `to` | `Instant` | Exclude events after this instant |
| `messageTypes` | `Set<MessageType>` | Keep only events of these message types |
| `excludeSynthetic` | `boolean` | When `true`, synthetic summary events are excluded |
| `lastN` | `Integer` | Keep only the most recent N matching events (must be > 0) |
| `keyword` | `String` | Case-insensitive substring match on `message.getText()` |
| `keywords` | `List<String>` | Multiple case-insensitive substring terms, combined per `matchMode` |
| `matchMode` | `MatchMode` | `ANY` (at least one term present) or `ALL` (every term present) — only meaningful when `keywords` is set |
| `pattern` | `Pattern` | Compiled regular expression matched against `message.getText()` via `Matcher.find()`. **Only ever pass a developer-authored `Pattern`** — see the ReDoS warning above |
| `page` | `Integer` | Zero-indexed page in chronological order (oldest first, page 0 = oldest) |
| `pageSize` | `Integer` | Results per page (default 10; must be > 0 if set) |
| `branch` | `String` | Restricts to events visible to this agent branch (own + ancestors only) |
| `excludeArchived` | `boolean` | When `true`, archived (compacted-out) events are excluded — used by `EventFilter.active()` |

---

## Constraints

The compact constructor enforces these rules at construction time:

- **`lastN` and `pageSize` are mutually exclusive** — setting both throws
  `IllegalArgumentException`.
- **Setting `page` without `pageSize`** throws `IllegalArgumentException`.
- **Setting `pageSize` without `page`** is allowed — `page` defaults to `0` (first page).
- **`keyword`** is normalised on construction: blank or empty strings become `null`;
  non-null values are lowercased for case-insensitive matching.
- **`keywords`** is normalised on construction the same way, term by term: `null`/blank
  entries are dropped and survivors are lowercased. An empty or all-blank list normalises
  to `null` (equivalent to no multi-term filter).
- **`matchMode`** defaults to `ANY` when `keywords` is set and `matchMode` is left `null`.
  If `keywords` is `null`, `matchMode` is forced to `null` too — even if you set one
  explicitly — since it has nothing to apply to.
- **`messageTypes`** is normalised on construction: an empty set becomes `null`
  (equivalent to no type filter).

---

## Merging filters

`EventFilter.merge(other)` merges two filters: every non-null field from `other` replaces
the corresponding field from `this`; the two boolean flags, `excludeSynthetic` and
`excludeArchived`, are OR-ed so either side can opt in. This is used by
`SessionMemoryAdvisor` to combine the advisor-level default filter with an optional
per-request override — and, unconditionally, to force `excludeArchived = true` onto every
history read via `EventFilter.active()` so the prompt never sees compacted-out events
regardless of the configured or per-request filter (see
[ChatClient Integration → What the advisor does](chat-client.md#what-the-advisor-does)):

```java
EventFilter advisorDefault = EventFilter.lastN(50);
EventFilter requestOverride = EventFilter.lastN(5);

EventFilter merged = advisorDefault.merge(requestOverride);
// merged.lastN() == 5  (request-level wins)
```

See [ChatClient Integration → Per-request filter override](chat-client.md#per-request-filter-override)
for how this is used in practice.

---

## Write-side filtering

`EventFilter` is a **read-side** filter: events it excludes remain in storage — they are
only hidden from the retrieved history. To control which messages get **persisted** in
the first place, use `MessageFilter` on the `SessionMemoryAdvisor` builder. See
[ChatClient Integration → Filtering what gets persisted](chat-client.md#filtering-what-gets-persisted-messagefilter).
