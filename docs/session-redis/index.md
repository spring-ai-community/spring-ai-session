# Session Redis — Overview & Setup

`spring-ai-session-redis` provides a Redis-backed implementation of `SessionRepository`.
It needs no schema and no Redis modules — plain Redis 6.2 or newer is enough — and runs
its two mutating operations as Lua scripts so their compare-and-swap is atomic.

---

## Key layout

Five keys are owned exclusively by a single session, and two shared index structures keep
`findByUserId` and `findExpiredSessionIds` off `SCAN`:

```
<prefix><sessionId>                — HASH, session metadata including eventVersion
<prefix><sessionId>:events         — LIST, the active event window
<prefix><sessionId>:archive        — LIST, the archived events
<prefix><sessionId>:active-ids     — SET of active event ids
<prefix><sessionId>:archived-ids   — SET of archived event ids
<prefix>by-user:<userId>           — shared SET, the by-user index
<prefix>expiry                     — shared ZSET scored by expiresAt epoch-milli
```

The default prefix is `spring-ai:session:`.

!!! warning "Reserved suffixes"
    A session id must not end in `:events`, `:archive`, `:active-ids` or `:archived-ids`.
    Keys are built by concatenation, so such an id would make one session's metadata hash
    collide with another session's event list. Ids may otherwise contain any character,
    including `:`.

---

## Dependency

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-session-redis</artifactId>
    <version>${spring-ai-session.version}</version>
</dependency>
```

---

## Manual bean setup

```java
@Bean
RedisClient redisClient() {
    return RedisClient.builder().hostAndPort("localhost", 6379).build();
}

@Bean
SessionRepository sessionRepository(RedisClient redisClient) {
    return RedisSessionRepository.builder()
        .jedisClient(redisClient)
        .build();
}

@Bean
SessionService sessionService(SessionRepository sessionRepository) {
    return DefaultSessionService.builder().sessionRepository(sessionRepository).build();
}
```

The builder accepts optional overrides:

```java
RedisSessionRepository.builder()
    .jedisClient(redisClient)
    .keyPrefix("my-app:sessions:")     // default: spring-ai:session:
    .keyTtlGrace(Duration.ofDays(7))   // default: 30 days — see "Expiry" below
    .jsonMapper(customJsonMapper)      // custom JSON serialization
    .build();
```

---

## Using the repository

```java
@Autowired SessionRepository sessions;

// Create a session
Session session = sessions.save(Session.builder()
    .id(UUID.randomUUID().toString())
    .userId("alice")
    .build());

// Append a message event
sessions.appendEvent(SessionEvent.builder()
    .id(UUID.randomUUID().toString())
    .sessionId(session.id())
    .timestamp(Instant.now())
    .message(new UserMessage("Hello"))
    .build());

// Query events
List<SessionEvent> events = sessions.findEvents(session.id(), EventFilter.builder().build());
```

---

## Expiry

Session lifetime is an **application-driven sweep**: read `findExpiredSessionIds(now)` on
a schedule and call `delete(id)` for each returned id.

```java
@Scheduled(fixedDelay = 1, timeUnit = TimeUnit.HOURS)
void sweepExpiredSessions() {
    sessions.findExpiredSessionIds(Instant.now()).forEach(sessions::delete);
}
```

The expiry ZSET, not the Redis keyspace, is the source of truth. Keys deliberately do
*not* carry a native TTL matched to `Session.expiresAt()`: they would evaporate the moment
they expired, and the sweep would never observe them in the window where deletion is
meaningful.

A **crash backstop** still bounds the keyspace when no sweeper ever runs: `save()` records
`expiresAt + keyTtlGrace` as an absolute deadline, and every write path `EXPIREAT`s the
session-scoped keys it touches to exactly that instant. Pick a grace that comfortably
dwarfs the sweep interval, so the backstop can never pre-empt the sweep. The two shared
index keys never get a deadline.

---

## Design notes

**Event ordering** — events are returned in LIST insertion order, never sorted by
`SessionEvent.getTimestamp()`. Insertion order is the Redis equivalent of the monotonic
`seq` column the JDBC implementation orders by, and the distinction matters: a synthetic
compaction summary carries the compaction wall-clock time but must sit *ahead* of the
older active-window events it introduces.

**Optimistic concurrency** — the `eventVersion` hash field is incremented on every
`appendEvent` and `compactEvents`. Compaction compares and swaps inside a single Lua
script, so two concurrent compactions cannot both win and double the active window.

**Idempotent append** — the two event-id SETs play the role of the JDBC primary key.
Appending an id already present in either set is a no-op and does not bump the version.
An id that compaction *dropped* (present in neither list) becomes free for re-use, exactly
as the JDBC row would be.

**Atomic reads** — when the archive is in scope, both lists are read in one `EVAL` so they
observe the same instant. Two separate `LRANGE` round trips would let a compaction commit
in the gap and silently drop a durably stored event from the returned history.

**Filtering is in-process.** Redis has no query language for the event payloads, so every
`EventFilter` criterion — `messageTypes`, `keyword`, `keywords`, `pattern`, `branch`, time
range, `excludeSynthetic` — is evaluated in Java via `EventFilter.matches()`, then
`lastN`/`page`/`pageSize` is applied, mirroring `InMemorySessionRepository`. Only
`excludeArchived` is pushed down: it is what lets the hot path read the active list alone.

**Archive reads are unbounded by contract.** With `excludeArchived=false`, the whole
archive list is read. The SPI requires *all* matching events, and both the compaction input
and the prompt window are built from this output, so truncating would silently produce a
wrong context window. The live chat path is unaffected — `SessionMemoryAdvisor` force-merges
`EventFilter.active()`, so the archive is never read there. Any caller that *does* search
the archive should pass `lastN` or `pageSize`.

**Corrupt records degrade one event, not the session.** A stored record that cannot be
parsed or converted is logged at WARN and skipped. `findEvents` converts every record in a
loop, so propagating would make an entire session permanently unreadable over one bad
record.

---

## See also

- [Auto-configuration](auto-configuration.md) — let Spring Boot wire everything automatically
- [Session Concepts](../session-management/concepts.md) — `SessionRepository` SPI details
- [Context Compaction](../session-management/compaction.md) — optimistic-lock compaction
