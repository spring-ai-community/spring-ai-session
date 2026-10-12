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
 * Well-known {@link Session#metadata() metadata} keys.
 *
 * <p>
 * The session model has no notion of ownership or scope. Who a session belongs to is an
 * attribute the application puts into the metadata, typically through a
 * {@link SessionScopeResolver}, and reads back through an index lookup
 * ({@link SessionRepository#findByIndex}). This class names the attributes the library
 * itself understands, the way Spring Session names its principal index.
 *
 * @author Christian Tzolov
 * @since 0.11.0
 */
public final class SessionAttributes {

	/**
	 * The name of the principal a session belongs to, as {@code Authentication.getName()}
	 * returns it in Spring Security. Indexed by every repository.
	 */
	public static final String PRINCIPAL_NAME = "principalName";

	private SessionAttributes() {
	}

	/**
	 * Returns the principal name stored in the session's metadata, or {@code null} when
	 * the session has none.
	 */
	public static @Nullable String principalName(Session session) {
		Object value = session.metadata().get(PRINCIPAL_NAME);
		return (value != null) ? String.valueOf(value) : null;
	}

}
