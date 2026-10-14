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

import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import org.springframework.util.Assert;

/**
 * The {@link EventType#STATUS} events a session records when its status changes, so the
 * log tells when each turn started and how it ended. {@link SessionService#startTurn},
 * {@link SessionService#endTurn} and {@link SessionService#archive} append them after
 * updating the session; an agent runtime may append its own for a status it manages.
 *
 * @author Christian Tzolov
 * @since 0.11.0
 */
public final class SessionStatusEvents {

	/** Payload key: the new {@link SessionStatus}, by name. */
	public static final String PAYLOAD_STATUS = "status";

	/** Payload key: the {@link StopReason} of a turn that ended, by name. */
	public static final String PAYLOAD_STOP_REASON = "stopReason";

	/**
	 * Payload key: the ids of the events a {@link StopReason#REQUIRES_ACTION} turn waits
	 * on, typically the assistant message whose tool calls need confirmation.
	 */
	public static final String PAYLOAD_EVENT_IDS = "eventIds";

	/** Payload key: the id of the run the turn belongs to, when the caller named one. */
	public static final String PAYLOAD_RUN_ID = "runId";

	private SessionStatusEvents() {
	}

	/** The event recorded when a turn starts. */
	public static SessionEvent running(String sessionId) {
		return running(sessionId, null);
	}

	/** The event recorded when a turn starts, naming the run it belongs to. */
	public static SessionEvent running(String sessionId, @Nullable String runId) {
		SessionEvent.Builder event = SessionEvent.builder()
			.sessionId(sessionId)
			.type(EventType.STATUS)
			.payload(PAYLOAD_STATUS, SessionStatus.RUNNING.name());
		if (runId != null && !runId.isBlank()) {
			event.payload(PAYLOAD_RUN_ID, runId);
		}
		return event.build();
	}

	/** The event recorded when a turn ends, with why it ended. */
	public static SessionEvent idle(String sessionId, StopReason stopReason) {
		return idle(sessionId, stopReason, List.of());
	}

	/**
	 * The event recorded when a turn ends, with why it ended and the events it waits on
	 * ({@link StopReason#REQUIRES_ACTION}).
	 */
	public static SessionEvent idle(String sessionId, StopReason stopReason, List<String> eventIds) {
		return idle(sessionId, stopReason, eventIds, null);
	}

	/**
	 * The event recorded when a turn ends, with why it ended, the events it waits on and
	 * the run it belonged to.
	 */
	public static SessionEvent idle(String sessionId, StopReason stopReason, List<String> eventIds,
			@Nullable String runId) {
		Assert.notNull(stopReason, "stopReason must not be null");
		Assert.notNull(eventIds, "eventIds must not be null");
		SessionEvent.Builder event = SessionEvent.builder()
			.sessionId(sessionId)
			.type(EventType.STATUS)
			.payload(PAYLOAD_STATUS, SessionStatus.IDLE.name())
			.payload(PAYLOAD_STOP_REASON, stopReason.name());
		if (!eventIds.isEmpty()) {
			event.payload(PAYLOAD_EVENT_IDS, List.copyOf(eventIds));
		}
		if (runId != null && !runId.isBlank()) {
			event.payload(PAYLOAD_RUN_ID, runId);
		}
		return event.build();
	}

	/** The event recorded when a session is archived. */
	public static SessionEvent terminated(String sessionId) {
		return changed(sessionId, SessionStatus.TERMINATED, null);
	}

	/** A status event for any status and optional stop reason. */
	public static SessionEvent changed(String sessionId, SessionStatus status, @Nullable StopReason stopReason) {
		Assert.notNull(status, "status must not be null");
		SessionEvent.Builder event = SessionEvent.builder()
			.sessionId(sessionId)
			.type(EventType.STATUS)
			.payload(PAYLOAD_STATUS, status.name());
		if (stopReason != null) {
			event.payload(PAYLOAD_STOP_REASON, stopReason.name());
		}
		return event.build();
	}

	/** The status a {@link EventType#STATUS} event records, or {@code null} for another kind of event. */
	public static @Nullable SessionStatus status(SessionEvent event) {
		Object value = event.getPayload().get(PAYLOAD_STATUS);
		return (event.getType() == EventType.STATUS && value != null) ? SessionStatus.valueOf(String.valueOf(value))
				: null;
	}

	/** The run a {@link EventType#STATUS} event belongs to, or {@code null} when none was named. */
	public static @Nullable String runId(SessionEvent event) {
		Object value = event.getPayload().get(PAYLOAD_RUN_ID);
		return (event.getType().equals(EventType.STATUS) && value != null) ? String.valueOf(value) : null;
	}

	/** The ids of the events a {@link EventType#STATUS} event says the turn waits on; empty when none. */
	public static List<String> eventIds(SessionEvent event) {
		Object value = event.getPayload().get(PAYLOAD_EVENT_IDS);
		if (!event.getType().equals(EventType.STATUS) || !(value instanceof List<?> list)) {
			return List.of();
		}
		return list.stream().filter(Objects::nonNull).map(String::valueOf).toList();
	}

	/** The stop reason a {@link EventType#STATUS} event records, or {@code null} when it has none. */
	public static @Nullable StopReason stopReason(SessionEvent event) {
		Object value = event.getPayload().get(PAYLOAD_STOP_REASON);
		return (event.getType() == EventType.STATUS && value != null) ? StopReason.valueOf(String.valueOf(value))
				: null;
	}

}
