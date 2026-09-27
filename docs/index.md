# Spring AI Session

**Spring AI Session** is a structured, event-sourced conversation memory layer for
[Spring AI](https://docs.spring.io/spring-ai/reference/) applications.

Most AI frameworks store conversation history as a flat list of messages. As sessions grow
they hit the model's context window, and truncating the oldest messages breaks tool-call
sequences and discards coherent turns mid-conversation.

Spring AI Session solves this with three ideas working together:

1. **Structured events** — every message is a `SessionEvent` with a unique id, timestamp,
   session ownership, and optional branch label for multi-agent hierarchies.
2. **Turn-aware compaction** — configurable triggers fire when the history grows too large,
   and pluggable strategies decide what to keep, always respecting turn boundaries so the
   model never sees an orphaned tool result or a half-finished exchange.
3. **Persistent repositories** — a clean SPI (`SessionRepository`) lets you swap the
   in-memory store for JDBC, Redis, or any other backend without changing application code.

See the [architecture overview](session-management/concepts.md#architecture-overview) for
how the main types fit together.

---

## Modules

| Module | Artifact | Description |
|--------|----------|-------------|
| **Session Management** | `spring-ai-session` | Core SPI: `Session`, `SessionEvent`, `SessionService`, `SessionRepository`, compaction framework, `SessionMemoryAdvisor` |
| **Session JDBC** | `spring-ai-session-jdbc` | JDBC-backed `SessionRepository` for PostgreSQL, MySQL, MariaDB, and H2 |
| **Session Auto-configuration** | `spring-ai-autoconfigure-session` | Spring Boot auto-configuration for `DefaultSessionService` (repository-agnostic) |
| **Session JDBC Auto-configuration** | `spring-ai-autoconfigure-session-jdbc` | Spring Boot auto-configuration for the JDBC repository |
| **Session JDBC Starter** | `spring-ai-starter-session-jdbc` | Spring Boot starter — one dependency for a fully wired JDBC session setup |
| **Session BOM** | `spring-ai-session-bom` | Bill of Materials for managing all module versions together |

---

## Key Features

- **Append-only history** — compaction only flips an event's `archived` flag, so the full
  history stays searchable
- **Composable event filtering** — by message type, time range, single or multi-term
  keyword (`ANY`/`ALL`), regular expression, branch, last-N, or pagination
- **Four compaction strategies** out of the box:
    - `SlidingWindowCompactionStrategy` — keep the last N real events
    - `TurnWindowCompactionStrategy` — keep the last N complete turns
    - `TokenCountCompactionStrategy` — keep a token-budget-bounded suffix
    - `RecursiveSummarizationCompactionStrategy` — LLM-powered rolling summary
- **Two compaction triggers** — turn count and token count (composable with OR semantics)
- **Optimistic concurrency** — compare-and-swap `compactEvents` makes compaction safe under
  concurrent requests without locking
- **Multi-agent branch isolation** — dot-separated branch labels let peer sub-agents share
  one session while hiding each other's events
- **Recall storage tools** — `conversation_search` keyword-searches the current session's
  full verbatim history even after compaction; `cross_session_search` lets a
  background agent mine *every* session a user has
- **System messages as configuration** — supplied per request by default; storing them is
  opt-in, and then the latest one per agent branch is kept first and never summarized
  (see [System Messages](session-management/system-messages.md))
- **Spring Boot auto-configuration** — schema init, dialect detection, and the
  `JdbcSessionRepository` and `SessionService` beans

---

## Quickstart (Spring Boot)

Add a single dependency — the JDBC starter — and Spring Boot auto-configures the
repository and `SessionService` (schema auto-initialised with an embedded database):

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-starter-session-jdbc</artifactId>
    <version>${spring-ai-session.version}</version>
</dependency>
```

```java
@Bean
ChatClient chatClient(ChatModel chatModel, SessionService sessionService) {
    SessionMemoryAdvisor advisor = SessionMemoryAdvisor.builder(sessionService)
        .compactionTrigger(new TurnCountTrigger(20))
        .compactionStrategy(SlidingWindowCompactionStrategy.builder().maxEvents(10).build())
        .build();
    return ChatClient.builder(chatModel).defaultAdvisors(advisor).build();
}

// Every call automatically loads history, appends messages, and compacts when needed
String answer = chatClient.prompt()
    .user("What is Spring AI?")
    .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, "session-abc"))
    .call()
    .content();
```

See [Getting Started](getting-started.md) for the full setup, including persistent
databases and a no-Boot programmatic option.

**Requirements:** Java 17+, Spring AI `2.0.1+`, Spring Boot `4.1.1+`.

---

## Links

- [GitHub](https://github.com/spring-ai-community/spring-ai-session)
- [Issues](https://github.com/spring-ai-community/spring-ai-session/issues)
- [Spring AI Reference](https://docs.spring.io/spring-ai/reference/)
- [Migration Guide](migration.md)
