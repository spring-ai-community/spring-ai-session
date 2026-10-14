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

import org.springframework.util.Assert;

/**
 * A question a turn parked on: what kind of question, where in the run it was asked, what
 * was asked, and the type of the answer that resumes the run. The counterpart of an agent
 * runtime's interrupt; a tool confirmation is one kind. Recorded with
 * {@link InteractionEvents}, parked with {@link SessionService#park} and answered with
 * {@link SessionService#resume}.
 *
 * @param kind what kind of question: {@link #KIND_TOOL_CONFIRMATION}, {@link #KIND_INTERRUPT},
 * or an application's own
 * @param path the place in the run the question came from, like a node's path; empty for
 * the run itself
 * @param payload the question, as the runtime raised it; stored as JSON, so a value read
 * back from a store is a map or a scalar
 * @param resumeType the name of the type the answer must have, or {@code null} when any
 * value does
 * @author Christian Tzolov
 * @since 0.11.0
 */
public record Interaction(String kind, String path, @Nullable Object payload, @Nullable String resumeType) {

	/**
	 * Tool calls that wait for a person's decision; the payload names the calls
	 * ({@link ToolConfirmations}).
	 */
	public static final String KIND_TOOL_CONFIRMATION = "toolConfirmation";

	/** A runtime's interrupt node asking a human; the payload is its question. */
	public static final String KIND_INTERRUPT = "interrupt";

	public Interaction {
		Assert.hasText(kind, "kind must not be null or empty");
		Assert.notNull(path, "path must not be null");
	}

}
