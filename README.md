# Spring AI Session

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java Version](https://img.shields.io/badge/Java-17%2B-orange)](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0.0--SNAPSHOT-green)](https://docs.spring.io/spring-ai/reference/)

A [Spring AI](https://docs.spring.io/spring-ai/reference/) library that provides structured, event-sourced session management with context compaction for AI applications.

## Overview

Most AI frameworks store conversation history as a flat list of messages. That works for short conversations — but as sessions grow, you hit the model's context window. Truncating the oldest messages breaks tool-call sequences and discards coherent turns mid-conversation.

**Spring AI Session** solves this with:

- **Structured events** — every message is a `SessionEvent` with identity, timestamp, session ownership, and an optional branch label for multi-agent hierarchies
- **Turn-aware compaction** — configurable triggers fire when history grows too large; pluggable strategies decide what to keep, always respecting turn boundaries
- **Persistent repositories** — a clean SPI (`SessionRepository`) makes it trivial to swap the in-memory store for JDBC, Redis, or any other backend

## Project Structure

```
spring-ai-session/
├── spring-ai-session-core/                              # Core SPI, compaction framework, SessionMemoryAdvisor
├── spring-ai-session-jdbc/                              # JDBC-backed SessionRepository (PostgreSQL, MySQL, H2)
├── spring-ai-session-bom/                               # Bill of Materials for version management
└── auto-configurations/
    └── session/
        └── spring-ai-autoconfigure-session-jdbc/        # Spring Boot auto-configuration for the JDBC repository
```

## Modules

| Module | Artifact | Description |
|--------|----------|-------------|
| **Session Core** | `spring-ai-session-core` | `Session`, `SessionEvent`, `SessionService`, `SessionRepository` SPI, compaction framework, `SessionMemoryAdvisor` |
| **Session JDBC** | `spring-ai-session-jdbc` | JDBC-backed `SessionRepository` for PostgreSQL, MySQL, MariaDB, and H2 |
| **Session JDBC Auto-configuration** | `spring-ai-autoconfigure-session-jdbc` | Spring Boot auto-configuration for the JDBC repository |
| **Session BOM** | `spring-ai-session-bom` | Bill of Materials for managing all module versions together |

## Quick Start

**1. Add the BOM:**

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springaicommunity</groupId>
            <artifactId>spring-ai-session-bom</artifactId>
            <version>0.1.0-SNAPSHOT</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

**2. Add a module dependency:**

```xml
<!-- Core only (in-memory repository) -->
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-session-core</artifactId>
</dependency>

<!-- Or JDBC auto-configuration (Spring Boot) -->
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-autoconfigure-session-jdbc</artifactId>
</dependency>
```

**3. Wire a `SessionMemoryAdvisor` into your `ChatClient`:**

```java
SessionService sessionService = new DefaultSessionService(InMemorySessionRepository.builder().build());

SessionMemoryAdvisor advisor = SessionMemoryAdvisor.builder(sessionService)
    .defaultUserId("alice")
    .compactionTrigger(new TurnCountTrigger(20))
    .compactionStrategy(new SlidingWindowCompactionStrategy(10))
    .build();

ChatClient client = ChatClient.builder(chatModel)
    .defaultAdvisors(advisor)
    .build();

// Every call automatically loads history, appends messages, and compacts when needed
String answer = client.prompt()
    .user("What is Spring AI?")
    .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, "session-abc"))
    .call()
    .content();
```

## Documentation

Full reference documentation is available at:

**[https://spring-ai-community.github.io/spring-ai-session/](https://spring-ai-community.github.io/spring-ai-session/)**

Topics covered:

- [Getting Started](https://spring-ai-community.github.io/spring-ai-session/latest-snapshot/getting-started/) — setup options (in-memory, JDBC auto-config, JDBC manual)
- [Core Concepts](https://spring-ai-community.github.io/spring-ai-session/latest-snapshot/session-core/concepts/) — `Session`, `SessionEvent`, turns, and architecture
- [Event Filtering](https://spring-ai-community.github.io/spring-ai-session/latest-snapshot/session-core/event-filtering/) — composable `EventFilter` API
- [Context Compaction](https://spring-ai-community.github.io/spring-ai-session/latest-snapshot/session-core/compaction/) — triggers, strategies, turn-boundary safety
- [ChatClient Integration](https://spring-ai-community.github.io/spring-ai-session/latest-snapshot/session-core/chat-client/) — `SessionMemoryAdvisor` setup and options
- [Multi-Agent Branch Isolation](https://spring-ai-community.github.io/spring-ai-session/latest-snapshot/session-core/multi-agent/) — sharing sessions across parallel agents
- [Recall Storage](https://spring-ai-community.github.io/spring-ai-session/latest-snapshot/session-core/recall-storage/) — keyword search over the full verbatim history
- [Session JDBC](https://spring-ai-community.github.io/spring-ai-session/latest-snapshot/session-jdbc/) — JDBC repository setup, schema, and design notes

## Requirements

- Java 17+
- Spring AI `2.0.0-SNAPSHOT`
- Spring Boot `4.0.2+`
- Maven 3.6+

## Building

```bash
./mvnw clean install
```

To skip tests:

```bash
./mvnw clean install -DskipTests
```

## Snapshot Repository

Snapshot artifacts are published to the Spring snapshot repository:

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

## License

Apache License 2.0

## Links

- [Documentation](https://spring-ai-community.github.io/spring-ai-session/)
- [Issue Tracker](https://github.com/spring-ai-community/spring-ai-session/issues)
- [Spring AI Documentation](https://docs.spring.io/spring-ai/reference/)
- [Spring AI Community](https://github.com/spring-ai-community)

## Contributing

Contributions are welcome! Please open an issue or submit a pull request.
