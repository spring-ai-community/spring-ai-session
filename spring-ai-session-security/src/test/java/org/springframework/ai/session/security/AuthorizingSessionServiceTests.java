/*
 * Copyright 2023-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.ai.session.security;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionSelector;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.compaction.SlidingWindowCompactionStrategy;
import org.springframework.ai.session.tool.CrossSessionRecallTools;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link AuthorizingSessionService} with the default principal manager and the
 * security-context scope resolver, over an in-memory store.
 *
 * @author Christian Tzolov
 */
class AuthorizingSessionServiceTests {

	private final DefaultSessionService delegate = DefaultSessionService.builder()
		.sessionRepository(InMemorySessionRepository.builder().build())
		.scopeResolver(new SecurityContextScopeResolver())
		.build();

	private final SessionService service = new AuthorizingSessionService(this.delegate,
			new PrincipalSessionAuthorizationManager());

	private Session alices;

	private Session bobs;

	@BeforeEach
	void sessions() {
		login("alice");
		this.alices = this.service.create(CreateSessionRequest.builder().build());
		this.service.appendMessage(this.alices.id(), new UserMessage("alice secret"));
		login("bob");
		this.bobs = this.service.create(CreateSessionRequest.builder().build());
		this.service.appendMessage(this.bobs.id(), new UserMessage("bob secret"));
	}

	@AfterEach
	void logout() {
		SecurityContextHolder.clearContext();
	}

	private static void login(String name) {
		SecurityContextHolder.getContext()
			.setAuthentication(new TestingAuthenticationToken(name, "n/a", "ROLE_USER"));
	}

	@Test
	void createScopesTheSessionToTheCaller() {
		assertThat(SessionAttributes.principalName(this.alices)).isEqualTo("alice");
		assertThat(SessionAttributes.principalName(this.bobs)).isEqualTo("bob");
	}

	@Test
	void theOwnerCanReadAppendCompactAndDelete() {
		login("alice");

		assertThat(this.service.findById(this.alices.id())).isNotNull();
		assertThat(this.service.getMessages(this.alices.id())).extracting(Message::getText)
			.containsExactly("alice secret");
		this.service.appendMessage(this.alices.id(), new UserMessage("more"));
		assertThat(this.service.compact(this.alices.id(), r -> true,
				SlidingWindowCompactionStrategy.builder().maxEvents(1).build()).isUnchanged()).isFalse();
		this.service.delete(this.alices.id());
		assertThat(this.delegate.findById(this.alices.id())).isNull();
	}

	@Test
	void anotherPrincipalIsDeniedEverySessionOperation() {
		login("bob");
		String id = this.alices.id();
		SessionEvent event = SessionEvent.builder().sessionId(id).message(new UserMessage("intrusion")).build();

		assertThatThrownBy(() -> this.service.findById(id)).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> this.service.getEvents(id, EventFilter.all()))
			.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> this.service.appendEvent(event)).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> this.service.compact(id, r -> true,
				SlidingWindowCompactionStrategy.builder().maxEvents(1).build()))
			.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> this.service.delete(id)).isInstanceOf(AccessDeniedException.class);
		// The interface defaults route through the checked methods
		assertThatThrownBy(() -> this.service.appendMessage(id, new UserMessage("intrusion")))
			.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> this.service.getEvents(id)).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> this.service.getMessages(id)).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> this.service.getActiveMessages(id)).isInstanceOf(AccessDeniedException.class);
		assertThat(this.delegate.getEvents(id)).hasSize(1);
	}

	@Test
	void aRequestNamingTheCallersOwnPrincipalIsAllowedWithOrWithoutAnId() {
		login("alice");

		Session withId = this.service.create(CreateSessionRequest.builder()
			.id("alice-" + System.nanoTime())
			.metadata(SessionAttributes.PRINCIPAL_NAME, "alice")
			.build());
		Session withoutId = this.service
			.create(CreateSessionRequest.builder().metadata(SessionAttributes.PRINCIPAL_NAME, "alice").build());

		assertThat(SessionAttributes.principalName(withId)).isEqualTo("alice");
		assertThat(SessionAttributes.principalName(withoutId)).isEqualTo("alice");
	}

	@Test
	void deleteExpiredSessionsIsMaintenanceAndNotCheckedPerSession() {
		login("bob");
		Session expired = this.delegate.create(CreateSessionRequest.builder()
			.metadata(SessionAttributes.PRINCIPAL_NAME, "alice")
			.timeToLive(java.time.Duration.ofSeconds(-60))
			.build());

		assertThat(this.service.deleteExpiredSessions(java.time.Instant.now())).isGreaterThanOrEqualTo(1);
		assertThat(this.delegate.findById(expired.id())).isNull();
	}

	@Test
	void anAnonymousCallerIsDeniedAndCreatesUnscopedSessions() {
		SecurityContextHolder.getContext()
			.setAuthentication(new AnonymousAuthenticationToken("key", "anonymousUser",
					AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

		assertThatThrownBy(() -> this.service.findById(this.alices.id())).isInstanceOf(AccessDeniedException.class);
		Session unscoped = this.service.create(CreateSessionRequest.builder().build());
		assertThat(SessionAttributes.principalName(unscoped)).isNull();
		// ...which nobody is granted by the default manager
		login("alice");
		assertThatThrownBy(() -> this.service.findById(unscoped.id())).isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void creatingASessionForSomeoneElseIsDeniedUnlessTheManagerAllowsIt() {
		login("bob");
		CreateSessionRequest forAlice = CreateSessionRequest.builder()
			.metadata(SessionAttributes.PRINCIPAL_NAME, "alice")
			.build();

		assertThatThrownBy(() -> this.service.create(forAlice)).isInstanceOf(AccessDeniedException.class);

		// An admin manager: everything goes
		SessionService admin = new AuthorizingSessionService(this.delegate,
				(authentication, session) -> new org.springframework.security.authorization.AuthorizationDecision(
						true));
		assertThat(SessionAttributes.principalName(admin.create(forAlice))).isEqualTo("alice");
	}

	@Test
	void listingsAndCrossSessionReadsAreNarrowedToTheCaller() {
		login("alice");

		assertThat(this.service.findByIndex(SessionAttributes.PRINCIPAL_NAME, "bob")).isEmpty();
		assertThat(this.service.findByIndex(SessionAttributes.PRINCIPAL_NAME, "alice")).extracting(Session::id)
			.containsExactly(this.alices.id());
		assertThat(this.service.findEvents(List.of(this.alices.id(), this.bobs.id()), EventFilter.all()))
			.extracting(e -> e.getMessage().getText())
			.containsExactly("alice secret");
		assertThat(this.service.findEvents(List.of(this.bobs.id()), EventFilter.all())).isEmpty();
	}

	@Test
	void unknownSessionsKeepTheDelegatesBehaviour() {
		login("alice");

		assertThat(this.service.findById("missing")).isNull();
		assertThat(this.service.findEvents(List.of("missing"), EventFilter.all())).isEmpty();
	}

	@Test
	void recallToolsBoundToTheCallerSeeOnlyTheCallersSessions() {
		login("bob");
		CrossSessionRecallTools tools = CrossSessionRecallTools
			.builder(this.service, SessionSelector.principal("alice"))
			.build();

		// bob asks for alice's sessions: the listing is narrowed, nothing is found
		assertThat(tools.crossSessionSearch("", "secret", null, null, null)).isEqualTo("No results found.");
		login("alice");
		assertThat(tools.crossSessionSearch("", "secret", null, null, null)).contains("alice secret")
			.doesNotContain("bob secret");
	}

	@Test
	void theResolverFillsOnlyWhatTheRequestLeftOut() {
		login("alice");
		Session session = this.service.create(CreateSessionRequest.builder().metadata(Map.of("agent", "support")).build());

		assertThat(session.metadata()).containsEntry("agent", "support")
			.containsEntry(SessionAttributes.PRINCIPAL_NAME, "alice");
	}

}
