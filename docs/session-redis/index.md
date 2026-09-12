# Session Redis — Overview & Setup

`spring-ai-session-redis` provides a Redis-backed implementation of `SessionRepository`.
It needs no schema and no Redis modules — plain Redis 6.2 or newer is enough — and runs
every mutation as a Lua script, so its checks and writes are atomic.

---

## Key layout

Five keys are owned exclusively by a single session, and two shared index structures keep
`findByUserId` and `deleteExpiredSessions` off `SCAN`:

```
<prefix><sessionId>                — HASH, session metadata including eventVersion
<prefix><sessionId>:log            — LIST, every event id in log order
<prefix><sessionId>:active         — LIST, the non-archived event ids in log order
<prefix><sessionId>:event-data     — HASH, event id → event JSON
<prefix><sessionId>:archived       — SET of archived event ids
<prefix>by-user:<userId>           — shared SET, the by-user index
<prefix>expiry                     — shared ZSET scored by expiresAt epoch-milli
```

The default prefix is `spring-ai:session:`.

!!! warning "Reserved suffixes"
    A session id must not end in `:log`, `:active`, `:event-data` or `:archived`.
    Keys are built by concatenation, so such an id would make one session's metadata hash
    collide with another session's event key. Ids may otherwise contain any character,
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

Session lifetime is an **application-driven sweep**: call `deleteExpiredSessions(now)` on
a schedule. Each candidate is re-checked and deleted in one Lua script, so a session whose
TTL was extended concurrently is left alone.

```java
@Scheduled(fixedDelay = 1, timeUnit = TimeUnit.HOURS)
void sweepExpiredSessions() {
    sessionService.deleteExpiredSessions(Instant.now());
}
```

The expiry ZSET, not the Redis keyspace, is the source of truth. Keys deliberately do
*not* carry a native TTL matched to `Session.expiresAt()`: they would evaporate the moment
they expired, and the sweep would never observe them in the window where deletion is
meaningful.

A **crash backstop** still bounds the keyspace when no sweeper ever runs: `save()` records
`expiresAt + keyTtlGrace` as an absolute deadline, and every write `EXPIREAT`s the five
per-session keys to exactly that instant. Pick a grace that comfortably
dwarfs the sweep interval, so the backstop can never pre-empt the sweep. The two shared
index keys never get a deadline.

---

## Design notes

**Event ordering** — events are returned in `:log` order, never sorted by
`SessionEvent.getTimestamp()`. A compaction summary carries the compaction wall-clock time
but must sit *ahead* of the older events it introduces. `applyCompaction` archives events in
place and inserts new ones before their anchor; since the log holds ids, compaction never
re-encodes event JSON, and the archived flag is membership of the `:archived` SET.

**Optimistic concurrency** — the `eventVersion` hash field is incremented on every
`appendEvent` and `applyCompaction`. Compaction compares and swaps inside a single Lua
script and validates the whole plan before writing, so two concurrent compactions cannot
both win, and a rejected plan changes nothing.

**Idempotent append** — an id already in `:event-data` is a replay: no duplicate, no version
bump.

**Atomic reads** — the ids and the events they name are read in one `EVAL`, so no
compaction can commit in between.

**Filtering** — `excludeArchived` is pushed down: the hot path reads `:active` alone and
never touches archived history. Everything else goes through `EventFilter.apply`, the
reference implementation of the read contract, including the turn-aware `lastN` window.

**Archived reads are unbounded by contract.** With `excludeArchived=false` the whole log is
read; the SPI requires *all* matching events, so it is not truncated. `SessionMemoryAdvisor`
reads `EventFilter.active()`, so the chat path never pays for it. Callers that search
archived history should pass `lastN` or `pageSize`.

**Corrupt records degrade one event, not the session.** A stored record that cannot be
parsed or converted is logged at WARN and skipped. `findEvents` converts every record in a
loop, so propagating would make an entire session permanently unreadable over one bad
record.

---

## See also

- [Auto-configuration](auto-configuration.md) — let Spring Boot wire everything automatically
- [Session Concepts](../session-management/concepts.md) — `SessionRepository` SPI details
- [Context Compaction](../session-management/compaction.md) — optimistic-lock compaction
