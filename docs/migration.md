# Migration Guide

## Upgrading to 0.3.0

### Breaking: `SessionMemoryAdvisor.Builder.defaultSessionId()` removed

**What changed**

`defaultSessionId(String)` has been removed from `SessionMemoryAdvisor.Builder`. The
advisor no longer falls back to a shared session — `SESSION_ID_CONTEXT_KEY` is now
**required** on every request. Omitting it throws `IllegalStateException`.

**Why**

A single default session ID was shared across all requests to the same advisor instance,
silently merging conversation history from different users or threads. This is a
correctness and security issue in any multi-user deployment.

**How to migrate**

Before (0.2.x):

```java
SessionMemoryAdvisor advisor = SessionMemoryAdvisor.builder(sessionService)
    .defaultSessionId("my-session-id")   // ← removed
    .build();

// Session ID came from the advisor default — no per-request param needed
client.prompt().user("Hello").call().content();
```

After (0.3.0):

```java
SessionMemoryAdvisor advisor = SessionMemoryAdvisor.builder(sessionService)
    .build();

// Session ID must be passed on every request
client.prompt()
    .user("Hello")
    .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId))
    .call()
    .content();
```

In a typical web application, resolve the session ID from the authenticated principal or
HTTP session in the controller, then pass it per call:

```java
@PostMapping("/chat")
String chat(@AuthenticationPrincipal UserDetails user, @RequestBody String message) {
    String sessionId = resolveSessionId(user.getUsername());
    return chatClient.prompt()
        .user(message)
        .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId))
        .call()
        .content();
}
```
