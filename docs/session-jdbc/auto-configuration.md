# Auto-configuration

`spring-ai-autoconfigure-session-jdbc` is a Spring Boot auto-configuration that creates a
`JdbcSessionRepository` bean automatically when a `DataSource` bean is present. It
depends on `spring-ai-autoconfigure-session`, which creates a `DefaultSessionService`
bean on top of any available `SessionRepository`.

---

## Dependency

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-autoconfigure-session-jdbc</artifactId>
    <version>${spring-ai-session.version}</version>
</dependency>
```

Adding this single dependency gives you:

| Bean | Created by |
|---|---|
| `JdbcSessionRepository` | `spring-ai-autoconfigure-session-jdbc` |
| `DefaultSessionService` | `spring-ai-autoconfigure-session` |

No additional bean declarations are required.

---

## Schema initialisation

By default, schema initialisation only runs for embedded databases (H2). Control this
with:

```yaml
spring:
  ai:
    session:
      repository:
        jdbc:
          initialize-schema: always    # always | embedded (default) | never
```

| Value | Behaviour |
|---|---|
| `embedded` | Initialise schema only for embedded databases (H2). Default. |
| `always` | Always run the DDL script on startup. Useful for PostgreSQL/MySQL in dev. |
| `never` | Never run the DDL script. Manage schema externally (Flyway, Liquibase, etc.). |

---

## Configuration properties

All properties are under the prefix `spring.ai.session.repository.jdbc`:

| Property | Default | Description |
|---|---|---|
| `initialize-schema` | `embedded` | When to run the bundled DDL script |

---

## Overriding the auto-configured beans

Declare your own bean of the relevant type and the auto-configuration backs off
automatically.

**Override the repository** (e.g. to supply a custom dialect or transaction manager):

```java
@Bean
SessionRepository sessionRepository(DataSource dataSource,
                                    PlatformTransactionManager txManager) {
    return JdbcSessionRepository.builder()
        .dataSource(dataSource)
        .transactionManager(txManager)
        .dialect(new PostgresJdbcSessionRepositoryDialect())
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

Both auto-configurations use `@ConditionalOnMissingBean`, so either or both can be
overridden independently.

---

## See also

- [Overview & Setup](index.md) — manual bean setup, schema scripts, supported databases
- [Getting Started](../getting-started.md) — side-by-side comparison of setup options
