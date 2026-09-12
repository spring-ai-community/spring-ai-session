# Auto-configuration

`spring-ai-autoconfigure-session-redis` is a Spring Boot auto-configuration that creates a
`RedisSessionRepository` bean. It depends on `spring-ai-autoconfigure-session`, which
creates a `DefaultSessionService` bean on top of any available `SessionRepository`.

---

## Dependency

The recommended way to pull in the full auto-configured stack is via the starter:

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-starter-session-redis</artifactId>
    <version>${spring-ai-session.version}</version>
</dependency>
```

This single dependency gives you:

| Bean | Created by |
|---|---|
| `RedisClient` | `spring-ai-autoconfigure-session-redis` (only if none is declared) |
| `RedisSessionRepository` | `spring-ai-autoconfigure-session-redis` |
| `DefaultSessionService` | `spring-ai-autoconfigure-session` |

No additional bean declarations are required.

---

## Sharing an existing client

The `RedisClient` bean is `@ConditionalOnMissingBean`. If the application already declares
one — for instance the client auto-configured for Redis chat memory — it is reused as-is
and the `host` / `port` / `username` / `password` properties below are ignored.

---

## Configuration properties

All properties are under the prefix `spring.ai.session.repository.redis`:

| Property | Default | Description |
|---|---|---|
| `host` | `localhost` | Redis server host. Ignored when a `RedisClient` bean is already present. |
| `port` | `6379` | Redis server port. Ignored when a `RedisClient` bean is already present. |
| `username` | _(none)_ | Redis ACL username. Ignored when a `RedisClient` bean is already present. |
| `password` | _(none)_ | Redis server password. Ignored when a `RedisClient` bean is already present. |
| `key-prefix` | `spring-ai:session:` | Prefix every session key is built under |
| `key-ttl-grace` | `30d` | Grace added to a session's `expiresAt` to form the crash-backstop deadline |

```yaml
spring:
  ai:
    session:
      repository:
        redis:
          host: redis.example.com
          port: 6379
          key-prefix: my-app:sessions:
          key-ttl-grace: 7d
```

!!! note "`key-ttl-grace` is a backstop, not the expiry mechanism"
    Sessions are removed by an application-driven sweep over
    `findExpiredSessionIds(...)`. The grace only bounds the keyspace if that sweep never
    runs, so it must comfortably dwarf the sweep interval — see
    [Expiry](index.md#expiry).

---

## Overriding the auto-configured beans

Declare your own bean of the relevant type and the auto-configuration backs off
automatically.

**Override the client** (e.g. to configure TLS, pooling or a cluster):

```java
@Bean
RedisClient redisClient() {
    return RedisClient.builder()
        .hostAndPort("redis.example.com", 6379)
        .clientConfig(DefaultJedisClientConfig.builder().ssl(true).build())
        .build();
}
```

**Override the repository** (e.g. to supply a custom `JsonMapper`):

```java
@Bean
RedisSessionRepository sessionRepository(RedisClient redisClient) {
    return RedisSessionRepository.builder()
        .jedisClient(redisClient)
        .jsonMapper(myJsonMapper)
        .build();
}
```

**Override the service** (e.g. to wrap it with custom behaviour):

```java
@Bean
SessionService sessionService(SessionRepository repository) {
    return new MyCustomSessionService(repository);
}
```

All three auto-configured beans use `@ConditionalOnMissingBean`, so any of them can be
overridden independently.

---

## See also

- [Overview & Setup](index.md) — key layout, manual bean setup, expiry sweep
- [Getting Started](../getting-started.md) — side-by-side comparison of setup options
