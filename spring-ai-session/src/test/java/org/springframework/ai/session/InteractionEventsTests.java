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
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link InteractionEvents}: a parked turn's question and answer.
 *
 * @author Christian Tzolov
 */
class InteractionEventsTests {

	@Test
	void aQuestionAndItsAnswerRoundTrip() {
		SessionEvent required = InteractionEvents.required("s",
				new Interaction("approve/", Map.of("question", "Publish?"), "com.acme.Approval"));
		SessionEvent answered = InteractionEvents.answered("s", required.getId(), Map.of("approved", true));
		SessionEvent plain = SessionEvent.builder().sessionId("s").type(EventType.STATUS).build();

		assertThat(required.getType()).isEqualTo(EventType.INTERACTION);
		assertThat(InteractionEvents.isRequired(required)).isTrue();
		assertThat(InteractionEvents.isAnswered(required)).isFalse();
		assertThat(InteractionEvents.interaction(required))
			.isEqualTo(new Interaction("approve/", Map.of("question", "Publish?"), "com.acme.Approval"));
		assertThat(InteractionEvents.isAnswered(answered)).isTrue();
		assertThat(InteractionEvents.requiredEventId(answered)).isEqualTo(required.getId());
		assertThat(InteractionEvents.answer(answered)).isEqualTo(Map.of("approved", true));
		assertThat(InteractionEvents.interaction(answered)).isNull();
		assertThat(InteractionEvents.interaction(plain)).isNull();
		assertThat(InteractionEvents.requiredEventId(plain)).isNull();
	}

	@Test
	void aParkedTurnNamesTheQuestionAndResumesFromIt() {
		SessionService service = DefaultSessionService.builder()
			.sessionRepository(InMemorySessionRepository.builder().build())
			.build();
		Session session = service.create(CreateSessionRequest.builder().build());
		service.startTurn(session.id(), "run-1");
		SessionEvent question = InteractionEvents.required(session.id(), new Interaction("approve/", "Publish?", null));
		service.appendEvent(question);
		service.endTurn(session.id(), StopReason.REQUIRES_ACTION, List.of(question.getId()));

		Session parked = service.findById(session.id());
		assertThat(parked.stopReason()).isEqualTo(StopReason.REQUIRES_ACTION);
		SessionEvent status = service.getEvents(session.id()).get(service.getEvents(session.id()).size() - 1);
		assertThat(SessionStatusEvents.eventIds(status)).containsExactly(question.getId());
		assertThat(SessionStatusEvents.runId(status)).isEqualTo("run-1");
		// On any node: find the pending question, record the answer, start the turn again
		SessionEvent pending = service.getEvents(session.id()).stream().filter(InteractionEvents::isRequired).reduce((a, b) -> b).orElseThrow();
		assertThat(InteractionEvents.interaction(pending).payload()).isEqualTo("Publish?");
		service.appendEvent(InteractionEvents.answered(session.id(), pending.getId(), true));
		service.startTurn(session.id(), "run-1");
		assertThat(service.findById(session.id()).status()).isEqualTo(SessionStatus.RUNNING);
	}

}
