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

import java.util.Map;

import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionScopeResolver;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.util.Assert;

/**
 * A {@link SessionScopeResolver} that scopes a session to the authenticated caller:
 * {@link Authentication#getName()} becomes {@link SessionAttributes#PRINCIPAL_NAME}.
 * Nothing is resolved for an anonymous or missing authentication.
 *
 * @author Christian Tzolov
 * @since 0.11.0
 */
public class SecurityContextScopeResolver implements SessionScopeResolver {

	private final SecurityContextHolderStrategy securityContextHolderStrategy;

	private final AuthenticationTrustResolver trustResolver = new AuthenticationTrustResolverImpl();

	public SecurityContextScopeResolver() {
		this(SecurityContextHolder.getContextHolderStrategy());
	}

	public SecurityContextScopeResolver(SecurityContextHolderStrategy securityContextHolderStrategy) {
		Assert.notNull(securityContextHolderStrategy, "securityContextHolderStrategy must not be null");
		this.securityContextHolderStrategy = securityContextHolderStrategy;
	}

	@Override
	public Map<String, Object> resolve() {
		Authentication authentication = this.securityContextHolderStrategy.getContext().getAuthentication();
		if (!this.trustResolver.isAuthenticated(authentication)) {
			return Map.of();
		}
		return Map.of(SessionAttributes.PRINCIPAL_NAME, authentication.getName());
	}

}
