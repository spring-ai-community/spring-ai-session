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

package org.springaicommunity.session.autoconfigure;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionScopeResolver;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.security.AuthorizingSessionService;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link SessionSecurityAutoConfiguration}.
 *
 * @author Christian Tzolov
 */
class SessionSecurityAutoConfigurationTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(
				AutoConfigurations.of(SessionSecurityAutoConfiguration.class, SessionServiceAutoConfiguration.class))
		.withBean(SessionRepository.class, () -> InMemorySessionRepository.builder().build());

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void withSpringSecurityTheServiceIsScopedAndAuthorized() {
		this.contextRunner.run(context -> {
			assertThat(context).hasSingleBean(SessionScopeResolver.class);
			SessionService service = context.getBean(SessionService.class);
			assertThat(service).isInstanceOf(AuthorizingSessionService.class);

			SecurityContextHolder.getContext()
				.setAuthentication(new TestingAuthenticationToken("alice", "n/a", "ROLE_USER"));
			Session session = service.create(CreateSessionRequest.builder().build());
			assertThat(SessionAttributes.principalName(session)).isEqualTo("alice");

			SecurityContextHolder.getContext()
				.setAuthentication(new TestingAuthenticationToken("bob", "n/a", "ROLE_USER"));
			assertThatThrownBy(() -> service.findById(session.id())).isInstanceOf(AccessDeniedException.class);
		});
	}

	@Test
	void thePropertySwitchesTheIntegrationOff() {
		this.contextRunner.withPropertyValues("spring.ai.session.security.enabled=false").run(context -> {
			assertThat(context).doesNotHaveBean(SessionScopeResolver.class);
			assertThat(context.getBean(SessionService.class)).isInstanceOf(DefaultSessionService.class);
		});
	}

	@Test
	void withoutTheSecurityModuleNothingIsWrapped() {
		this.contextRunner.withClassLoader(new FilteredClassLoader(AuthorizingSessionService.class)).run(context -> {
			assertThat(context).doesNotHaveBean(SessionScopeResolver.class);
			assertThat(context.getBean(SessionService.class)).isInstanceOf(DefaultSessionService.class);
		});
	}

	@Test
	void aUserDefinedSessionServiceIsLeftAlone() {
		SessionService mine = DefaultSessionService.builder()
			.sessionRepository(InMemorySessionRepository.builder().build())
			.build();
		this.contextRunner.withBean("mySessionService", SessionService.class, () -> mine).run(context -> {
			assertThat(context).hasSingleBean(SessionService.class);
			assertThat(context.getBean(SessionService.class)).isSameAs(mine);
			assertThat(context).hasSingleBean(SessionServiceDecorator.class);
		});
	}

	@Test
	void aUserDefinedResolverWins() {
		this.contextRunner
			.withBean("tenantResolver", SessionScopeResolver.class,
					() -> SessionScopeResolver.fixed(java.util.Map.of("tenant", "acme")))
			.run(context -> {
				assertThat(context).hasSingleBean(SessionScopeResolver.class);
				assertThat(context.getBean(SessionScopeResolver.class).resolve()).containsEntry("tenant", "acme");
			});
	}

	@Test
	void aUserDefinedAuthorizationManagerWins() {
		this.contextRunner.withUserConfiguration(GrantEverythingConfiguration.class).run(context -> {
			SessionService service = context.getBean(SessionService.class);
			assertThat(service).isInstanceOf(AuthorizingSessionService.class);
			SecurityContextHolder.getContext()
				.setAuthentication(new TestingAuthenticationToken("alice", "n/a", "ROLE_USER"));
			Session session = service.create(CreateSessionRequest.builder().build());
			SecurityContextHolder.getContext()
				.setAuthentication(new TestingAuthenticationToken("bob", "n/a", "ROLE_USER"));
			// The default manager would deny bob; the user's manager grants everything
			assertThat(service.findById(session.id())).isNotNull();
		});
	}

	@Configuration(proxyBeanMethods = false)
	static class GrantEverythingConfiguration {

		@Bean
		AuthorizationManager<Session> sessionAuthorizationManager() {
			return (authentication, session) -> new AuthorizationDecision(true);
		}

	}

}
