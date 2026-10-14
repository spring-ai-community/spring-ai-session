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

import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The turn a {@link SessionTurnAdvisor} runs, on the context of every request it forwards,
 * so every round of a tool loop carries it. It is held once a turn was started for it: by
 * the turn advisor itself, or by the {@link ToolConfirmationAdvisor} when it resumes a
 * parked turn. The turn advisor ends only a turn it holds.
 *
 * @author Christian Tzolov
 * @since 0.11.0
 */
final class SessionTurn {

	static final String CONTEXT_KEY = "chat_memory_session_turn";

	private volatile boolean held;

	void hold() {
		this.held = true;
	}

	boolean isHeld() {
		return this.held;
	}

	/** The turn on the context, or {@code null} when no turn advisor runs the request. */
	static @Nullable SessionTurn of(Map<String, @Nullable Object> context) {
		return (context.get(CONTEXT_KEY) instanceof SessionTurn turn) ? turn : null;
	}

}
