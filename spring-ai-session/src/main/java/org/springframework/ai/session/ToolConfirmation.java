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
 * A caller's decision on a tool call that waits for confirmation: allow it to run, or
 * deny it with a message the model gets as the tool's result. The counterpart of the
 * {@code user.tool_confirmation} event of an agent runtime.
 *
 * @param toolCallId the id of the tool call, as the assistant message names it
 * @param allowed whether the tool may run
 * @param denyMessage what the model is told when the call is denied, or {@code null} for
 * the default message
 * @author Christian Tzolov
 * @since 0.11.0
 */
public record ToolConfirmation(String toolCallId, boolean allowed, @Nullable String denyMessage) {

	/** What the model is told when a call is denied without a message. */
	public static final String DEFAULT_DENY_MESSAGE = "The user did not allow this tool call.";

	public ToolConfirmation {
		Assert.hasText(toolCallId, "toolCallId must not be null or empty");
	}

	/** Allows the tool call to run. */
	public static ToolConfirmation allow(String toolCallId) {
		return new ToolConfirmation(toolCallId, true, null);
	}

	/** Denies the tool call; the model is told why, or the default message. */
	public static ToolConfirmation deny(String toolCallId, @Nullable String denyMessage) {
		return new ToolConfirmation(toolCallId, false, denyMessage);
	}

	/** The message the model gets as the result of a denied call. */
	public String denyMessageOrDefault() {
		return (this.denyMessage != null && !this.denyMessage.isBlank()) ? this.denyMessage : DEFAULT_DENY_MESSAGE;
	}

}
