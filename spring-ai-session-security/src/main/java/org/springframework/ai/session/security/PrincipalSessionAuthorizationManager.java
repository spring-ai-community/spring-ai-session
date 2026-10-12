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

import java.util.function.Supplier;

import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;

/**
 * The default {@link AuthorizationManager} for sessions: a caller may use a session whose
 * {@link SessionAttributes#PRINCIPAL_NAME} equals the caller's
 * {@link Authentication#getName()}. An anonymous caller is denied, and so is a session
 * without a principal attribute.
 *
 * <p>
 * Replace it to express tenants, teams or administrator roles; the session's metadata
 * carries whatever attributes the application's {@code SessionScopeResolver} stores.
 *
 * @author Christian Tzolov
 * @since 0.11.0
 */
public class PrincipalSessionAuthorizationManager implements AuthorizationManager<Session> {

	private final AuthenticationTrustResolver trustResolver = new AuthenticationTrustResolverImpl();

	@Override
	public AuthorizationResult authorize(Supplier<? extends Authentication> authentication, Session session) {
		Authentication caller = authentication.get();
		if (!this.trustResolver.isAuthenticated(caller)) {
			return new AuthorizationDecision(false);
		}
		String principal = SessionAttributes.principalName(session);
		return new AuthorizationDecision(principal != null && principal.equals(caller.getName()));
	}

}
