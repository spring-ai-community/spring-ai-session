# Getting Started

## Requirements

- Java 17+
- Spring AI `2.0.1+`
- Spring Boot `4.1.1+`

---

## Add the BOM (recommended)

Import the BOM so all module versions stay in sync:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springaicommunity</groupId>
            <artifactId>spring-ai-session-bom</artifactId>
            <version>${spring-ai-session.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

---

## Choose a setup

=== "Spring Boot starter (recommended)"

    Add the starter — one dependency for a fully wired JDBC session setup:

    ```xml
    <dependency>
        <groupId>org.springaicommunity</groupId>
        <artifactId>spring-ai-starter-session-jdbc</artifactId>
    </dependency>
    ```

    With an embedded database (e.g. H2) on the classpath, that's it — no beans, no config.
    Spring Boot automatically creates:

    - a `JdbcSessionRepository` bean backed by the auto-configured `DataSource`
    - a `DefaultSessionService` bean wrapping the repository
    - SQL dialect detection from the database metadata (PostgreSQL, MySQL, MariaDB, H2)
    - schema initialisation for embedded databases (H2)

    To initialise the schema for PostgreSQL or MySQL, set:

    ```yaml
    spring:
      ai:
        session:
          repository:
            jdbc:
              initialize-schema: always
    ```

    Declare your own `@Bean SessionService` to override the auto-configured one. See
    [JDBC Auto-configuration](session-jdbc/auto-configuration.md#configuration-properties)
    for all properties, including the default session time-to-live (60 days) and
    `allow-system-messages` (system prompts are supplied per request by default; see
    [System Messages](session-management/system-messages.md)).

=== "JDBC (manual)"

    Add the JDBC module:

    ```xml
    <dependency>
        <groupId>org.springaicommunity</groupId>
        <artifactId>spring-ai-session-jdbc</artifactId>
    </dependency>
    ```

    Apply the DDL script for your database (see
    [Session JDBC → Overview & Setup](session-jdbc/index.md#schema)), then define the bean:

    ```java
    @Bean
    SessionRepository sessionRepository(DataSource dataSource) {
        return JdbcSessionRepository.builder()
            .dataSource(dataSource)   // dialect auto-detected
            .build();
    }

    @Bean
    SessionService sessionService(SessionRepository repository) {
        return DefaultSessionService.builder().sessionRepository(repository).build();
    }
    ```

=== "In-memory (testing only)"

    Add the session management module:

    ```xml
    <dependency>
        <groupId>org.springaicommunity</groupId>
        <artifactId>spring-ai-session</artifactId>
    </dependency>
    ```

    Create the service in your application:

    ```java
    @Bean
    SessionService sessionService() {
        return DefaultSessionService.builder()
            .sessionRepository(InMemorySessionRepository.builder().build())
            .build();
    }
    ```

    !!! warning
        `InMemorySessionRepository` is not suitable for production — sessions are lost on
        restart and not shared across instances. Use the [Spring Boot starter](#choose-a-setup)
        or the JDBC repository for persistence.

---

## Wire the ChatClient advisor

`SessionMemoryAdvisor` loads history before each request, appends the user and assistant
messages after each response, and compacts when a trigger fires.

```java
@Bean
SessionMemoryAdvisor sessionMemoryAdvisor(SessionService sessionService) {
    return SessionMemoryAdvisor.builder(sessionService)
        .defaultUserId("alice")
        // Compact when 20 turns accumulate, keeping the last 10 events
        .compactionTrigger(new TurnCountTrigger(20))
        .compactionStrategy(SlidingWindowCompactionStrategy.builder().maxEvents(10).build())
        .build();
}

@Bean
ChatClient chatClient(ChatModel chatModel, SessionMemoryAdvisor advisor) {
    return ChatClient.builder(chatModel)
        .defaultAdvisors(advisor)
        .build();
}
```

Pass a session ID at call time via the advisor context:

```java
String response = chatClient.prompt()
    .user("Hello!")
    .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, "session-abc"))
    .call()
    .content();
```

If no session exists for the given ID, the advisor creates one automatically.

---

## Maven Repositories

Released versions are published to **Maven Central**, so no extra repository
configuration is needed.

To use a `-SNAPSHOT` version, add the Central Portal snapshot repository:

```xml
<repositories>
    <repository>
        <id>central-portal-snapshots</id>
        <url>https://central.sonatype.com/repository/maven-snapshots/</url>
        <snapshots><enabled>true</enabled></snapshots>
        <releases><enabled>false</enabled></releases>
    </repository>
</repositories>
```

---

## Next steps

- [Session Concepts](session-management/concepts.md) — understand `Session`, `SessionEvent`, and turns
- [Context Compaction](session-management/compaction.md) — configure triggers and strategies
- [System Messages](session-management/system-messages.md) — supply system prompts per request, and the opt-in to store them
- [Multi-Agent Branch Isolation](session-management/multi-agent.md) — share sessions across agents safely
- [Session JDBC](session-jdbc/index.md) — persistent JDBC-backed repository
- [Migration Guide](migration.md) — upgrade notes between versions
