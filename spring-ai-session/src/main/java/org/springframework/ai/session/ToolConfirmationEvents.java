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
 * The {@link EventType#TOOL_CONFIRMATION} events that record a caller's decision on a
 * tool call that waited for confirmation, so a parked turn can be resumed from the log
 * on any node and the log tells who allowed what.
 *
 * @author Christian Tzolov
 * @since 0.11.0
 */
public final class ToolConfirmationEvents {

	/** Payload key: the id of the tool call the decision is about. */
	public static final String PAYLOAD_TOOL_CALL_ID = "toolCallId";

	/** Payload key: {@value #RESULT_ALLOW} or {@value #RESULT_DENY}. */
	public static final String PAYLOAD_RESULT = "result";

	/** Payload key: the message the model gets for a denied call, when one was given. */
	public static final String PAYLOAD_DENY_MESSAGE = "denyMessage";

	/** The {@link #PAYLOAD_RESULT} of an allowed call. */
	public static final String RESULT_ALLOW = "allow";

	/** The {@link #PAYLOAD_RESULT} of a denied call. */
	public static final String RESULT_DENY = "deny";

	private ToolConfirmationEvents() {
	}

	/** The event that records one decision. */
	public static SessionEvent of(String sessionId, ToolConfirmation confirmation) {
		Assert.notNull(confirmation, "confirmation must not be null");
		SessionEvent.Builder event = SessionEvent.builder()
			.sessionId(sessionId)
			.type(EventType.TOOL_CONFIRMATION)
			.payload(PAYLOAD_TOOL_CALL_ID, confirmation.toolCallId())
			.payload(PAYLOAD_RESULT, confirmation.allowed() ? RESULT_ALLOW : RESULT_DENY);
		if (confirmation.denyMessage() != null) {
			event.payload(PAYLOAD_DENY_MESSAGE, confirmation.denyMessage());
		}
		return event.build();
	}

	/**
	 * The decision a {@link EventType#TOOL_CONFIRMATION} event records, or {@code null} for
	 * another kind of event.
	 * @throws IllegalArgumentException if the event names no tool call
	 */
	public static @Nullable ToolConfirmation confirmation(SessionEvent event) {
		if (event.getType() != EventType.TOOL_CONFIRMATION) {
			return null;
		}
		Object id = event.getPayload().get(PAYLOAD_TOOL_CALL_ID);
		Assert.notNull(id, () -> "TOOL_CONFIRMATION event '" + event.getId() + "' has no " + PAYLOAD_TOOL_CALL_ID);
		Object denyMessage = event.getPayload().get(PAYLOAD_DENY_MESSAGE);
		boolean allowed = RESULT_ALLOW.equals(event.getPayload().get(PAYLOAD_RESULT));
		return new ToolConfirmation(String.valueOf(id), allowed, denyMessage != null ? String.valueOf(denyMessage) : null);
	}

}
