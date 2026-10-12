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

import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionScopeResolver;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.security.AuthorizingSessionService;
import org.springframework.ai.session.security.PrincipalSessionAuthorizationManager;
import org.springframework.ai.session.security.SecurityContextScopeResolver;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Scopes and authorizes sessions with Spring Security when it is on the classpath:
 * registers a {@link SecurityContextScopeResolver} so every created session carries the
 * caller's principal name, a {@link PrincipalSessionAuthorizationManager}, and a
 * {@link SessionServiceDecorator} that wraps the auto-configured {@link SessionService} in
 * an {@link AuthorizingSessionService}. A {@code SessionService} bean the application
 * defines itself is not wrapped. Disable with
 * {@code spring.ai.session.security.enabled=false}; replace the
 * {@code AuthorizationManager<Session>} bean to change who may use which session.
 *
 * @author Christian Tzolov
 * @since 0.11.0
 */
@AutoConfiguration(before = SessionServiceAutoConfiguration.class)
@ConditionalOnClass({ AuthorizingSessionService.class, SecurityContextHolder.class })
@ConditionalOnBooleanProperty(name = SessionSecurityAutoConfiguration.ENABLED_PROPERTY, matchIfMissing = true)
public class SessionSecurityAutoConfiguration {

	/** The property that switches the Spring Security integration off. */
	public static final String ENABLED_PROPERTY = SessionServiceProperties.CONFIG_PREFIX + ".security.enabled";

	@Bean
	@ConditionalOnMissingBean
	SessionScopeResolver sessionScopeResolver() {
		return new SecurityContextScopeResolver();
	}

	@Bean
	@ConditionalOnMissingBean
	AuthorizationManager<Session> sessionAuthorizationManager() {
		return new PrincipalSessionAuthorizationManager();
	}

	@Bean
	SessionServiceDecorator authorizingSessionServiceDecorator(AuthorizationManager<Session> authorizationManager) {
		return service -> new AuthorizingSessionService(service, authorizationManager);
	}

}
