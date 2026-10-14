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

import java.util.Collection;
import java.util.List;

/**
 * Chooses the sessions a cross-session operation spans, such as the recall search of
 * {@link org.springframework.ai.session.tool.CrossSessionRecallTools}.
 *
 * <p>
 * Replaces a fixed user id: the sessions of a principal, of a tenant, or any other set
 * are expressed the same way, as a function from the service to the session ids.
 *
 * @author Christian Tzolov
 * @since 0.11.0
 */
@FunctionalInterface
public interface SessionSelector {

	/**
	 * Returns the ids of the selected sessions, possibly empty.
	 */
	List<String> select(SessionService sessionService);

	/**
	 * The sessions whose metadata holds {@code value} under {@code name}, children and
	 * archived sessions included.
	 */
	static SessionSelector byIndex(String name, String value) {
		return sessionService -> sessionService.findByIndex(name, value).stream().map(Session::id).toList();
	}

	/**
	 * The root sessions of the given principal ({@link SessionAttributes#PRINCIPAL_NAME})
	 * that are not archived. Children are left out: a sub-agent's log is not the
	 * principal's conversation. Use {@link #principalWithChildren} to include them, and
	 * {@link #byIndex} to include archived sessions.
	 */
	static SessionSelector principal(String principalName) {
		return sessionService -> sessionService.findByIndex(SessionAttributes.PRINCIPAL_NAME, principalName)
			.stream()
			.filter(Session::isRoot)
			.filter(s -> !s.isArchived())
			.map(Session::id)
			.toList();
	}

	/** Every session of the given principal that is not archived, children included. */
	static SessionSelector principalWithChildren(String principalName) {
		return sessionService -> sessionService.findByIndex(SessionAttributes.PRINCIPAL_NAME, principalName)
			.stream()
			.filter(s -> !s.isArchived())
			.map(Session::id)
			.toList();
	}

	/** A fixed set of sessions. */
	static SessionSelector of(Collection<String> sessionIds) {
		List<String> ids = List.copyOf(sessionIds);
		return sessionService -> ids;
	}

}
