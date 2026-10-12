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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.session.SessionAttributes;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SecurityContextScopeResolver}.
 *
 * @author Christian Tzolov
 */
class SecurityContextScopeResolverTests {

	private final SecurityContextScopeResolver resolver = new SecurityContextScopeResolver();

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void anAuthenticatedCallerIsThePrincipal() {
		SecurityContextHolder.getContext()
			.setAuthentication(new TestingAuthenticationToken("alice", "n/a", "ROLE_USER"));

		assertThat(this.resolver.resolve()).containsExactly(java.util.Map.entry(SessionAttributes.PRINCIPAL_NAME, "alice"));
	}

	@Test
	void noAuthenticationResolvesNothing() {
		assertThat(this.resolver.resolve()).isEmpty();
	}

	@Test
	void anAnonymousCallerResolvesNothing() {
		SecurityContextHolder.getContext()
			.setAuthentication(new AnonymousAuthenticationToken("key", "anonymousUser",
					AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

		assertThat(this.resolver.resolve()).isEmpty();
	}

	@Test
	void anUnauthenticatedTokenResolvesNothing() {
		TestingAuthenticationToken token = new TestingAuthenticationToken("alice", "n/a");
		token.setAuthenticated(false);
		SecurityContextHolder.getContext().setAuthentication(token);

		assertThat(this.resolver.resolve()).isEmpty();
	}

}
