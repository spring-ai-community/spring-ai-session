# Scope and Security

A `Session` has no owner field. Who a session belongs to, whether that is a user, a
tenant or a team, is an **attribute in its metadata**, resolved from the caller when the
session is created, indexed by the repository and enforced by an authorization layer in
front of the service. The session model stores and indexes; the application's security
setup decides.

This is the division Spring Session uses: its repository offers one generic lookup by an
indexed attribute, with a well-known name for the principal, and the Spring Security
integration fills and enforces it. The store itself knows nothing about users.

---

## The pieces

| Piece | Module | Role |
|---|---|---|
| `SessionAttributes.PRINCIPAL_NAME` | `spring-ai-session` | The well-known metadata key, `"principalName"`, holding the caller's `Authentication.getName()` |
| `SessionScopeResolver` | `spring-ai-session` | Returns the caller's scope attributes; `DefaultSessionService` merges them into every created session for the keys the request did not set |
| `SessionRepository.findByIndex(name, value)` | `spring-ai-session` | The one scoped lookup: the sessions whose metadata holds `value` under `name`. The principal is indexed by every store |
| `SessionService.findEvents(sessionIds, filter)` | `spring-ai-session` | A read across the given sessions; the caller chooses them, typically through `findByIndex` or a `SessionSelector` |
| `SessionSelector` | `spring-ai-session` | A function from the service to session ids: `principal("alice")`, `byIndex("tenant", "acme")`, `of(ids)` |
| `SecurityContextScopeResolver` | `spring-ai-session-security` | Reads `SecurityContextHolder` and stores the authenticated principal's name; nothing for an anonymous caller |
| `AuthorizingSessionService` | `spring-ai-session-security` | Decorates `SessionService`: every read, append, compaction and delete is checked by an `AuthorizationManager<Session>`; listings and cross-session reads are narrowed to what the caller is granted |
| `PrincipalSessionAuthorizationManager` | `spring-ai-session-security` | The default manager: a caller is granted a session whose `principalName` equals the caller's name |

Nothing in `spring-ai-session` imports Spring Security.

---

## Without Spring Security

Put the attribute into the metadata yourself, or supply a resolver that reads your own
request context:

```java
SessionService service = DefaultSessionService.builder()
    .sessionRepository(repository)
    .scopeResolver(() -> Map.of(SessionAttributes.PRINCIPAL_NAME, CurrentUser.name()))
    .build();

Session session = service.create(CreateSessionRequest.builder().build());
SessionAttributes.principalName(session);                              // "alice"
service.findByIndex(SessionAttributes.PRINCIPAL_NAME, "alice");         // alice's sessions
```

A request attribute wins over the resolver, so an administrator can create a session for
someone else by naming them in the request's metadata. Nothing checks who may do that
until an authorizing service is in front; see below.

A tenant works the same way. Resolve `Map.of("tenant", tenantId)` and look sessions up with
`findByIndex("tenant", tenantId)`. The JDBC repository indexes the keys it is told to,
`JdbcSessionRepository.builder().indexedAttributes(Set.of("tenant"))` or the
`spring.ai.session.repository.jdbc.indexed-attributes` property; the in-memory repository
answers for any key.

---

## With Spring Security

Add the security module:

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-session-security</artifactId>
</dependency>
```

With Spring Boot nothing else is needed. The auto-configuration registers a
`SecurityContextScopeResolver`, a `PrincipalSessionAuthorizationManager` and wraps the
`SessionService` bean in an `AuthorizingSessionService`:

- every session created through the service carries the caller's principal name;
- a caller who is not that principal gets `AccessDeniedException` from `findById`,
  `getEvents`, `appendEvent`, `compact` and `delete`;
- `findByIndex` and `findEvents(sessionIds, filter)` return only the caller's sessions;
- `create` with another principal in the request's metadata is denied;
- an anonymous caller is denied every session, and a session without a principal
  attribute is granted to nobody.

Switch it off with `spring.ai.session.security.enabled=false`. Replace the manager to
express tenants, teams or an administrator role:

```java
@Bean
AuthorizationManager<Session> sessionAuthorizationManager() {
    AuthenticationTrustResolver trust = new AuthenticationTrustResolverImpl();
    return (authentication, session) -> {
        Authentication caller = authentication.get();
        if (!trust.isAuthenticated(caller)) {          // null or anonymous
            return new AuthorizationDecision(false);
        }
        boolean admin = caller.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_ADMIN"));
        boolean owner = caller.getName().equals(SessionAttributes.principalName(session));
        return new AuthorizationDecision(admin || owner);
    };
}
```

Without Boot, build the pieces yourself:

```java
DefaultSessionService delegate = DefaultSessionService.builder()
    .sessionRepository(repository)
    .scopeResolver(new SecurityContextScopeResolver())
    .build();
SessionService service = new AuthorizingSessionService(delegate, new PrincipalSessionAuthorizationManager());
```

`SessionMemoryAdvisor` needs no configuration: it creates the session through the
service, so the session gets the caller's scope, and every read and append it makes is
authorized. The advisor's old per-request user id and its own ownership check are gone;
see the [migration guide](../migration.md#breaking-scope-and-ownership-leave-the-session-model).

**The trust boundary is the service.** Code that holds the `SessionRepository` bypasses the
authorizing decorator, as it would bypass Spring Security on any repository.
`deleteExpiredSessions` is maintenance and is not checked per session; guard its caller
with method security if needed. A session the caller is not granted is reported as
`AccessDeniedException`, not as absent, so a caller can learn that an id exists.

!!! warning "Streaming calls"
    `ChatClient.stream()` runs the advisor's `before()` on the advisor's scheduler
    (bounded-elastic by default) and its `after()` on the thread that emits the model's
    response. `SecurityContextHolder` is thread-local, so on those threads there is no
    authentication: the resolver stores no principal, and the authorizing service denies
    the advisor's reads and writes. Blocking calls (`.call()`) run both hooks on the
    caller's thread and are not affected.

    Until the security context is propagated (a follow-up), stream through the
    unauthorized delegate and authorize at the application layer, where the session id
    a request may use is chosen:

    ```java
    SessionService delegate = ((AuthorizingSessionService) sessionService).getDelegate();
    SessionMemoryAdvisor advisor = SessionMemoryAdvisor.builder(delegate).build();
    // on the caller's thread, so the principal is known:
    Map<String, Object> scope = Map.of(SessionAttributes.PRINCIPAL_NAME, authentication.getName());
    client.prompt().user(text)
        .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId)
                        .param(SessionMemoryAdvisor.SESSION_METADATA_CONTEXT_KEY, scope))
        .stream().content();
    ```

    `Schedulers.immediate()` on the advisor makes `before()` see the context, but
    `after()` still runs on the response thread, so it is not enough on its own.

---

## Cross-session recall

[`CrossSessionRecallTools`](../recall-memory/cross-session-recall.md) is bound to a
`SessionSelector` instead of a user id. The selector for one principal:

```java
CrossSessionRecallTools tools = CrossSessionRecallTools
    .builder(sessionService, SessionSelector.principal("alice"))   // or .builder(sessionService, "alice")
    .build();
```

Behind an `AuthorizingSessionService` the selection is narrowed to the caller's sessions
whatever the selector asks for.

---

## Sessions written before 0.11.0

Their owner was the `userId` field, the `user_id` column in JDBC. On read, a row whose
metadata has no `principalName` is given `user_id` as its principal, so existing sessions
keep their owner and the default authorization manager grants them to the same caller. The
schema script copies `user_id` into `AI_SESSION_INDEX` once, so `findByIndex` finds them.
The column is still written with the principal name so a database that declares it
`NOT NULL` keeps working. See the [JDBC page](../session-jdbc/index.md).
