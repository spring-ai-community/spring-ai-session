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

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Tests for the {@link SessionStatusEvents} factory and readers.
 *
 * @author Christian Tzolov
 */
class SessionStatusEventsTests {

	@Test
	void theThreeTransitionsAreStatusEventsWithTheirStopReason() {
		SessionEvent running = SessionStatusEvents.running("s");
		SessionEvent idle = SessionStatusEvents.idle("s", StopReason.REQUIRES_ACTION);
		SessionEvent terminated = SessionStatusEvents.terminated("s");

		assertThat(running.getType()).isEqualTo(EventType.STATUS);
		assertThat(running.hasMessage()).isFalse();
		assertThat(SessionStatusEvents.status(running)).isEqualTo(SessionStatus.RUNNING);
		assertThat(SessionStatusEvents.stopReason(running)).isNull();
		assertThat(SessionStatusEvents.status(idle)).isEqualTo(SessionStatus.IDLE);
		assertThat(SessionStatusEvents.stopReason(idle)).isEqualTo(StopReason.REQUIRES_ACTION);
		assertThat(idle.getPayload()).containsEntry(SessionStatusEvents.PAYLOAD_STATUS, "IDLE")
			.containsEntry(SessionStatusEvents.PAYLOAD_STOP_REASON, "REQUIRES_ACTION");
		assertThat(SessionStatusEvents.status(terminated)).isEqualTo(SessionStatus.TERMINATED);
		assertThatIllegalArgumentException().isThrownBy(() -> SessionStatusEvents.idle("s", null));
	}

	@Test
	void theReadersAnswerNullForAnEventThatIsNotAStatus() {
		SessionEvent message = SessionEvent.builder().sessionId("s").message(new UserMessage("hi")).build();

		assertThat(SessionStatusEvents.status(message)).isNull();
		assertThat(SessionStatusEvents.stopReason(message)).isNull();
	}

	@Test
	void aParkedTurnNamesTheEventsItWaitsOn() {
		SessionEvent parked = SessionStatusEvents.idle("s", StopReason.REQUIRES_ACTION, List.of("e1", "e2"));
		SessionEvent plain = SessionStatusEvents.idle("s", StopReason.END_TURN);

		assertThat(SessionStatusEvents.eventIds(parked)).containsExactly("e1", "e2");
		assertThat(parked.getPayload()).containsEntry(SessionStatusEvents.PAYLOAD_EVENT_IDS, List.of("e1", "e2"));
		assertThat(SessionStatusEvents.eventIds(plain)).isEmpty();
		assertThat(plain.getPayload()).doesNotContainKey(SessionStatusEvents.PAYLOAD_EVENT_IDS);
	}

}
