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

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Tests {@link SessionMemoryAdvisor} when it loses the race to create a new session: a
 * concurrent request created the same session ID between this request's
 * {@code findById} and {@code create}.
 */
class SessionMemoryAdvisorCreateRaceTests {

	private static final String SESSION_ID = "contended-session";

	@Test
	void ownershipIsCheckedAgainstTheConcurrentlyCreatedSession() {
		SessionService sessionService = sessionServiceWhereCreateLosesTheRaceTo("bob");
		SessionMemoryAdvisor advisor = SessionMemoryAdvisor.builder(sessionService).build();

		assertThatIllegalStateException()
			.isThrownBy(() -> advisor.before(request("alice"), mock(AdvisorChain.class)))
			.withMessageContaining("does not belong to user 'alice'");
	}

	@Test
	void sameUserLosingTheRaceContinuesWithTheStoredSession() {
		SessionService sessionService = sessionServiceWhereCreateLosesTheRaceTo("alice");
		SessionMemoryAdvisor advisor = SessionMemoryAdvisor.builder(sessionService).build();

		ChatClientRequest result = advisor.before(request("alice"), mock(AdvisorChain.class));

		assertThat(result.prompt().getInstructions()).extracting(m -> m.getText()).containsExactly("hello");
	}

	private static SessionService sessionServiceWhereCreateLosesTheRaceTo(String winnerUserId) {
		Session stored = Session.builder().id(SESSION_ID).userId(winnerUserId).build();
		SessionService sessionService = mock(SessionService.class);
		// Not found on the first lookup, created by the concurrent winner right after.
		given(sessionService.findById(SESSION_ID)).willReturn(null, stored);
		given(sessionService.create(any(CreateSessionRequest.class)))
			.willThrow(new IllegalStateException("Session already exists: " + SESSION_ID));
		return sessionService;
	}

	private static ChatClientRequest request(String userId) {
		return ChatClientRequest.builder()
			.prompt(new Prompt(List.of(new UserMessage("hello"))))
			.context(Map.of(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, SESSION_ID,
					SessionMemoryAdvisor.USER_ID_CONTEXT_KEY, userId))
			.build();
	}

}
