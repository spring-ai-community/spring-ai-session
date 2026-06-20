# Getting Started

## Requirements

- Java 17+
- Spring AI `2.0.0+`
- Spring Boot `4.0.7+`

---

## Quickstart (Spring Boot)

The fastest way to a running app is the **JDBC starter**. Add one dependency:

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-starter-session-jdbc</artifactId>
    <version>${spring-ai-session.version}</version>
</dependency>
```

With an embedded database (e.g. H2) on the classpath, that's it — no beans, no config.
The starter auto-configures a `JdbcSessionRepository`, a `DefaultSessionService`, detects
the SQL dialect, and initialises the schema. Wire the advisor into your `ChatClient` and
pass a session ID per call:

```java
@Bean
ChatClient chatClient(ChatModel chatModel, SessionService sessionService) {
    SessionMemoryAdvisor advisor = SessionMemoryAdvisor.builder(sessionService).build();
    return ChatClient.builder(chatModel).defaultAdvisors(advisor).build();
}

String answer = chatClient.prompt()
    .user("What is Spring AI?")
    .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, "session-abc"))
    .call()
    .content();
```

For a persistent database (PostgreSQL, MySQL) or other setups, see
[Choose a setup](#choose-a-setup) below.

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

    Spring Boot will automatically create:

    - a `JdbcSessionRepository` bean backed by the auto-configured `DataSource`
    - a `DefaultSessionService` bean wrapping the repository
    - SQL dialect detection from the DataSource URL
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

    No additional bean declarations are required. To override the auto-configured
    `SessionService`, declare your own `@Bean SessionService` and it will take precedence.

    The default session time-to-live (used when a `CreateSessionRequest` does not set
    its own `timeToLive`) defaults to 60 days and can be configured:

    ```yaml
    spring:
      ai:
        session:
          time-to-live: 30d   # ISO-8601 / Spring duration; defaults to 60d
    ```

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
        return new DefaultSessionService(repository);
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
        return new DefaultSessionService(InMemorySessionRepository.builder().build());
    }
    ```

    !!! warning
        `InMemorySessionRepository` is not suitable for production — sessions are lost on
        restart and not shared across instances. Use the [Spring Boot starter](#choose-a-setup)
        or the JDBC repository for persistence.

---

## Wire the ChatClient advisor

`SessionMemoryAdvisor` is the recommended way to use sessions with a `ChatClient`. It
automatically loads history before each request, appends the user and assistant messages
after each response, and runs compaction when a trigger fires.

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

## Git Repositories

Spring AI Session is available from the Spring snapshot repository:

```xml
<repositories>
    <repository>
        <id>spring-snapshots</id>
        <url>https://repo.spring.io/snapshot</url>
        <snapshots><enabled>true</enabled></snapshots>
        <releases><enabled>false</enabled></releases>
    </repository>
</repositories>
```

---

## Next steps

- [Session Concepts](session-management/concepts.md) — understand `Session`, `SessionEvent`, and turns
- [Context Compaction](session-management/compaction.md) — configure triggers and strategies
- [Multi-Agent Branch Isolation](session-management/multi-agent.md) — share sessions across agents safely
- [Session JDBC](session-jdbc/index.md) — persistent JDBC-backed repository
