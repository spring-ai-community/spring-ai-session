# Auto-configuration

`spring-ai-autoconfigure-session-jdbc` is a Spring Boot auto-configuration that creates a
`JdbcSessionRepository` bean automatically when a `DataSource` bean is present. It
depends on `spring-ai-autoconfigure-session`, which creates a `DefaultSessionService`
bean on top of any available `SessionRepository`.

---

## Dependency

The recommended way to pull in the full auto-configured stack is via the starter:

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-starter-session-jdbc</artifactId>
    <version>${spring-ai-session.version}</version>
</dependency>
```

This single dependency gives you:

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

`JdbcSessionRepositoryProperties` extends Spring Boot's `DatabaseInitializationProperties`,
so it also inherits these general-purpose properties:

| Property | Default | Description |
|---|---|---|
| `initialize-schema` | `embedded` | When to run the bundled DDL script |
| `schema` | `classpath:org/springframework/ai/session/jdbc/schema-@@platform@@.sql` | DDL script location; `@@platform@@` is resolved to the detected database platform |
| `platform` | _(auto-detected)_ | Overrides platform detection used to resolve `@@platform@@` in `schema` |
| `continue-on-error` | `false` | Whether to continue startup if the DDL script fails |

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
