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

package org.springframework.ai.session.advisor;

/**
 * Thrown in a running {@code ChatClient} call whose turn was interrupted with
 * {@link org.springframework.ai.session.SessionService#interrupt}: the
 * {@link SessionMemoryAdvisor} stops the tool-calling loop at its next step, before a
 * model call or before the tools of a reply run. The turn has already ended with
 * {@link org.springframework.ai.session.StopReason#INTERRUPTED}.
 *
 * @author Christian Tzolov
 * @since 0.11.0
 */
public class TurnInterruptedException extends RuntimeException {

	private final String sessionId;

	public TurnInterruptedException(String sessionId) {
		super("The turn of session '" + sessionId + "' was interrupted");
		this.sessionId = sessionId;
	}

	/** The session whose turn was interrupted. */
	public String getSessionId() {
		return this.sessionId;
	}

}
