# Auto-configuration

`spring-ai-autoconfigure-session-jdbc` is a Spring Boot auto-configuration that creates a
`JdbcSessionRepository` bean automatically when a `DataSource` bean is present.

---

## Dependency

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-autoconfigure-session-jdbc</artifactId>
    <version>${spring-ai-session.version}</version>
</dependency>
```

The auto-configuration creates:

- A `JdbcSessionRepository` bean backed by the auto-configured `DataSource`
- Dialect detection from the DataSource URL
- Schema initialisation (configurable)

You still need to declare a `SessionService` bean:

```java
@Bean
SessionService sessionService(JdbcSessionRepository repository) {
    return new DefaultSessionService(repository);
}
```

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

## Overriding the auto-configured bean

Define your own `JdbcSessionRepository` or `SessionRepository` bean to override the
auto-configured one:

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

Spring Boot's auto-configuration backs off automatically when a `SessionRepository` bean
is already present in the context.

---

## See also

- [Overview & Setup](index.md) — manual bean setup, schema scripts, supported databases
- [Getting Started](../getting-started.md) — side-by-side comparison of setup options
