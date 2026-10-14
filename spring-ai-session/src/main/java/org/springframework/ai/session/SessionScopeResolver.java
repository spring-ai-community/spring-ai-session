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

package org.springframework.ai.session;

import java.util.Map;

/**
 * Resolves the attributes that scope a session to its caller, such as the principal name,
 * from the ambient context of the current request.
 *
 * <p>
 * {@link DefaultSessionService} calls the resolver on every {@link SessionService#create}
 * and merges the result into the metadata of the new session, for the keys the request
 * did not set itself. The session model stays free of any notion of ownership: the
 * resolver decides what the scope is (a user, a tenant, a team), the repository indexes
 * it, and an authorization layer in front of the service enforces it.
 *
 * <p>
 * The Spring Security implementation reads {@code SecurityContextHolder} and returns
 * {@link SessionAttributes#PRINCIPAL_NAME}. An application without Spring Security
 * supplies its own, for example from a request-scoped tenant.
 *
 * @author Christian Tzolov
 * @since 0.11.0
 */
@FunctionalInterface
public interface SessionScopeResolver {

	/**
	 * Returns the scope attributes of the current caller, possibly empty. Values should be
	 * strings or other scalars so they can be indexed.
	 */
	Map<String, Object> resolve();

	/** A resolver that adds nothing. */
	static SessionScopeResolver none() {
		return Map::of;
	}

	/** A resolver that always returns the given attributes. */
	static SessionScopeResolver fixed(Map<String, Object> attributes) {
		Map<String, Object> copy = Map.copyOf(attributes);
		return () -> copy;
	}

}
