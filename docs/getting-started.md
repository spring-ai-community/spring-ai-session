# Getting Started

## Requirements

- Java 17+
- Spring AI `2.0.0-SNAPSHOT`
- Spring Boot `4.0.2+`

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

=== "In-memory (no persistence)"

    Add the core module:

    ```xml
    <dependency>
        <groupId>org.springaicommunity</groupId>
        <artifactId>spring-ai-session-core</artifactId>
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
        restart and not shared across instances. Use the JDBC repository for persistence.

=== "JDBC with auto-configuration (Spring Boot)"

    Add the auto-configuration starter:

    ```xml
    <dependency>
        <groupId>org.springaicommunity</groupId>
        <artifactId>spring-ai-autoconfigure-session-jdbc</artifactId>
    </dependency>
    ```

    Spring Boot will:

    - create a `JdbcSessionRepository` bean backed by the auto-configured `DataSource`
    - detect the SQL dialect from the DataSource URL
    - initialise the schema automatically for embedded databases (H2)

    To initialise the schema for PostgreSQL or MySQL, set:

    ```yaml
    spring:
      ai:
        session:
          repository:
            jdbc:
              initialize-schema: always
    ```

    You still need to declare a `SessionService` bean:

    ```java
    @Bean
    SessionService sessionService(JdbcSessionRepository repository) {
        return new DefaultSessionService(repository);
    }
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

## Repositories

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

- [Core Concepts](session-core/concepts.md) — understand `Session`, `SessionEvent`, and turns
- [Context Compaction](session-core/compaction.md) — configure triggers and strategies
- [Multi-Agent Branch Isolation](session-core/multi-agent.md) — share sessions across agents safely
- [Session JDBC](session-jdbc/index.md) — persistent JDBC-backed repository
