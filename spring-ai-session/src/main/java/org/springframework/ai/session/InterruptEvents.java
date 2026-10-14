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
 * The {@link EventType#INTERRUPT} event a caller's interruption of a running turn leaves in
 * the log, before the turn ends with {@link StopReason#INTERRUPTED}: the counterpart of an
 * agent runtime's {@code user.interrupt}. Recorded by {@link SessionService#interrupt}.
 *
 * @author Christian Tzolov
 * @since 0.11.0
 */
public final class InterruptEvents {

	/** Payload key: why the turn was interrupted, when the caller said. */
	public static final String PAYLOAD_REASON = "reason";

	private InterruptEvents() {
	}

	/** The event that records an interruption, with its reason when given. */
	public static SessionEvent of(String sessionId, @Nullable String reason) {
		SessionEvent.Builder event = SessionEvent.builder().sessionId(sessionId).type(EventType.INTERRUPT);
		if (reason != null && !reason.isBlank()) {
			event.payload(PAYLOAD_REASON, reason);
		}
		return event.build();
	}

	/** The reason an {@link EventType#INTERRUPT} event records, or {@code null} when none was given. */
	public static @Nullable String reason(SessionEvent event) {
		Object value = event.getPayload().get(PAYLOAD_REASON);
		return (event.getType().equals(EventType.INTERRUPT) && value != null) ? String.valueOf(value) : null;
	}

}
