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
import java.util.List;

/**
 * The {@link EventType#INTERACTION} events of a human-in-the-loop interaction: the
 * question a turn parked on, and the answer that resumed it. A turn parks with
 * {@link SessionService#endTurn(String, StopReason, java.util.List)} and
 * {@link StopReason#REQUIRES_ACTION} naming the {@linkplain #required required} event;
 * the {@linkplain #answered answer} is recorded before the turn starts again, so a run
 * can be resumed from the session on any node.
 *
 * @author Christian Tzolov
 * @since 0.11.0
 */
public final class InteractionEvents {

	/** Payload key: {@value #PHASE_REQUIRED} or {@value #PHASE_ANSWERED}. */
	public static final String PAYLOAD_PHASE = "phase";

	/** Payload key: what kind of question, see {@link Interaction#kind()}. */
	public static final String PAYLOAD_KIND = "kind";

	/** Payload key: the place in the run the question came from. */
	public static final String PAYLOAD_PATH = "path";

	/** Payload key: the question, or the answer. */
	public static final String PAYLOAD_VALUE = "value";

	/** Payload key: the name of the type the answer must have. */
	public static final String PAYLOAD_RESUME_TYPE = "resumeType";

	/** Payload key of an answer: the id of the event that asked. */
	public static final String PAYLOAD_REQUIRED_EVENT_ID = "requiredEventId";

	/** The {@link #PAYLOAD_PHASE} of a question. */
	public static final String PHASE_REQUIRED = "required";

	/** The {@link #PAYLOAD_PHASE} of an answer. */
	public static final String PHASE_ANSWERED = "answered";

	private InteractionEvents() {
	}

	/** The event that records the question a turn parks on. */
	public static SessionEvent required(String sessionId, Interaction interaction) {
		Assert.notNull(interaction, "interaction must not be null");
		SessionEvent.Builder event = SessionEvent.builder()
			.sessionId(sessionId)
			.type(EventType.INTERACTION)
			.payload(PAYLOAD_PHASE, PHASE_REQUIRED)
			.payload(PAYLOAD_KIND, interaction.kind())
			.payload(PAYLOAD_PATH, interaction.path());
		if (interaction.payload() != null) {
			event.payload(PAYLOAD_VALUE, interaction.payload());
		}
		if (interaction.resumeType() != null) {
			event.payload(PAYLOAD_RESUME_TYPE, interaction.resumeType());
		}
		return event.build();
	}

	/** The event that records the answer to a question, before the turn resumes. */
	public static SessionEvent answered(String sessionId, String requiredEventId, @Nullable Object value) {
		Assert.hasText(requiredEventId, "requiredEventId must not be null or empty");
		SessionEvent.Builder event = SessionEvent.builder()
			.sessionId(sessionId)
			.type(EventType.INTERACTION)
			.payload(PAYLOAD_PHASE, PHASE_ANSWERED)
			.payload(PAYLOAD_REQUIRED_EVENT_ID, requiredEventId);
		if (value != null) {
			event.payload(PAYLOAD_VALUE, value);
		}
		return event.build();
	}

	/**
	 * The newest question among the given {@link EventType#INTERACTION} events that no
	 * answer follows, or {@code null}: what a parked session waits on.
	 * @param interactions the session's interaction events, oldest first
	 */
	public static @Nullable SessionEvent pending(List<SessionEvent> interactions) {
		SessionEvent pending = null;
		for (SessionEvent event : interactions) {
			if (isRequired(event)) {
				pending = event;
			}
			else if (pending != null && pending.getId().equals(requiredEventId(event))) {
				pending = null;
			}
		}
		return pending;
	}

	/** Whether the event records a question. */
	public static boolean isRequired(SessionEvent event) {
		return event.getType().equals(EventType.INTERACTION)
				&& PHASE_REQUIRED.equals(event.getPayload().get(PAYLOAD_PHASE));
	}

	/** Whether the event records an answer. */
	public static boolean isAnswered(SessionEvent event) {
		return event.getType().equals(EventType.INTERACTION)
				&& PHASE_ANSWERED.equals(event.getPayload().get(PAYLOAD_PHASE));
	}

	/**
	 * The question a {@linkplain #isRequired required} event records, or {@code null} for
	 * another event.
	 */
	public static @Nullable Interaction interaction(SessionEvent event) {
		if (!isRequired(event)) {
			return null;
		}
		Object kind = event.getPayload().get(PAYLOAD_KIND);
		Object path = event.getPayload().get(PAYLOAD_PATH);
		Object resumeType = event.getPayload().get(PAYLOAD_RESUME_TYPE);
		return new Interaction(kind != null ? String.valueOf(kind) : Interaction.KIND_INTERRUPT,
				path != null ? String.valueOf(path) : "", event.getPayload().get(PAYLOAD_VALUE),
				resumeType != null ? String.valueOf(resumeType) : null);
	}

	/**
	 * The id of the question an {@linkplain #isAnswered answer} answers, or {@code null}
	 * for another event.
	 */
	public static @Nullable String requiredEventId(SessionEvent event) {
		Object id = event.getPayload().get(PAYLOAD_REQUIRED_EVENT_ID);
		return (isAnswered(event) && id != null) ? String.valueOf(id) : null;
	}

	/**
	 * The answer an {@linkplain #isAnswered answer} event records; {@code null} when none
	 * was given.
	 */
	public static @Nullable Object answer(SessionEvent event) {
		return isAnswered(event) ? event.getPayload().get(PAYLOAD_VALUE) : null;
	}

}
