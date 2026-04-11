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

// Keyword search — first page (default page size 10)
service.getEvents(id, EventFilter.keywordSearch("Spring AI"));

// Keyword search — explicit page and page size
service.getEvents(id, EventFilter.keywordSearch("Spring AI", 1, 5));

// Events visible to a specific agent branch (own + ancestor events only)
service.getEvents(id, EventFilter.forBranch("orch.researcher"));
```

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
    .branch("orch.researcher")                             // branch isolation
    .build();
```

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
| `page` | `Integer` | Zero-indexed page in chronological order (oldest first, page 0 = oldest) |
| `pageSize` | `Integer` | Results per page (default 10; must be > 0 if set) |
| `branch` | `String` | Restricts to events visible to this agent branch (own + ancestors only) |

---

## Constraints

The compact constructor enforces these rules at construction time:

- **`lastN` and `pageSize` are mutually exclusive** — setting both throws
  `IllegalArgumentException`.
- **Setting `page` without `pageSize`** throws `IllegalArgumentException`.
- **Setting `pageSize` without `page`** is allowed — `page` defaults to `0` (first page).
- **`keyword`** is normalised on construction: blank or empty strings become `null`;
  non-null values are lowercased for case-insensitive matching.
- **`messageTypes`** is normalised on construction: an empty set becomes `null`
  (equivalent to no type filter).

---

## Merging filters

`EventFilter.merge(other)` merges two filters: every non-null field from `other` replaces
the corresponding field from `this`; `excludeSynthetic` is OR-ed so either side can opt
in. This is used by `SessionMemoryAdvisor` to combine the advisor-level default filter
with an optional per-request override:

```java
EventFilter advisorDefault = EventFilter.lastN(50);
EventFilter requestOverride = EventFilter.lastN(5);

EventFilter merged = advisorDefault.merge(requestOverride);
// merged.lastN() == 5  (request-level wins)
```

See [ChatClient Integration → Per-request filter override](chat-client.md#per-request-filter-override)
for how this is used in practice.
