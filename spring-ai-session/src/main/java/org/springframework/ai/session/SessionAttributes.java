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

import org.jspecify.annotations.Nullable;

/**
 * The well-known metadata attributes of a {@link Session}, and the accessors that read
 * them.
 *
 * <p>
 * A session has no owner field. Who it belongs to is the {@link #PRINCIPAL_NAME} attribute,
 * in the shape of Spring Session's principal-name index: the store indexes it
 * ({@link SessionRepository#findByIndex}), a {@link SessionScopeResolver} fills it, and an
 * authorization layer decides what the caller may see. The {@link #AGENT_NAME} names the
 * agent a child runs. The links of a session tree are fields of {@link Session}
 * ({@link Session#parentSessionId()}, {@link Session#rootSessionId()}), not attributes.
 *
 * @author Christian Tzolov
 * @since 0.11.0
 */
public final class SessionAttributes {

	/**
	 * The name of the principal a session belongs to, as {@code Principal.getName()}
	 * returns it in Spring Security. Indexed by every repository.
	 */
	public static final String PRINCIPAL_NAME = "principalName";

	/** The name of the agent a child runs; absent unless set at creation. */
	public static final String AGENT_NAME = "agentName";

	private SessionAttributes() {
	}

	/**
	 * Returns the principal name stored in the session's metadata, or {@code null} when
	 * the session has none.
	 */
	public static @Nullable String principalName(Session session) {
		return text(session, PRINCIPAL_NAME);
	}

	/** The agent name of a child, or {@code null} when none was set. */
	public static @Nullable String agentName(Session session) {
		return text(session, AGENT_NAME);
	}

	private static @Nullable String text(Session session, String key) {
		Object value = session.metadata().get(key);
		return (value != null) ? String.valueOf(value) : null;
	}

}
