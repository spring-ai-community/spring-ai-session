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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link IdempotentSessionEventIdGenerator}, exercised through a real
 * {@link SessionMemoryAdvisor} + {@link InMemorySessionRepository} so the assertions
 * cover the actual persisted event log, not just the derived id strings in isolation.
 */
class IdempotentSessionEventIdGeneratorTests {

	private static final String RUN_ID_KEY = "run-id";

	private SessionService sessionService;

	private String sessionId;

	@BeforeEach
	void setUp() {
		this.sessionService = DefaultSessionService.builder()
			.sessionRepository(InMemorySessionRepository.builder().build())
			.build();
		Session session = this.sessionService.create(CreateSessionRequest.builder().userId("test-user").build());
		this.sessionId = session.id();
	}

	@Test
	void retriedCallWithSameContentDoesNotDuplicateSessionEvents() {
		SessionMemoryAdvisor advisor = advisorWith(new IdempotentSessionEventIdGenerator());
		AdvisorChain chain = mock(AdvisorChain.class);

		advisor.before(buildRequest("hello"), chain);
		advisor.after(buildResponse("the answer"), chain);
		advisor.before(buildRequest("hello"), chain);
		advisor.after(buildResponse("the answer"), chain);

		assertThat(this.sessionService.getEvents(this.sessionId, EventFilter.all())).hasSize(2);
	}

	@Test
	void turnsWithDifferentContentAreBothRecorded() {
		SessionMemoryAdvisor advisor = advisorWith(new IdempotentSessionEventIdGenerator());
		AdvisorChain chain = mock(AdvisorChain.class);

		advisor.before(buildRequest("question one"), chain);
		advisor.after(buildResponse("answer one"), chain);
		advisor.before(buildRequest("question two"), chain);
		advisor.after(buildResponse("answer two"), chain);

		assertThat(this.sessionService.getEvents(this.sessionId, EventFilter.all())).hasSize(4);
	}

	@Test
	void sameRunIdRetryStillDedupesWhenConfiguredWithARunIdKey() {
		SessionMemoryAdvisor advisor = advisorWith(
				new IdempotentSessionEventIdGenerator(RUN_ID_KEY, SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY));
		AdvisorChain chain = mock(AdvisorChain.class);

		advisor.before(buildRequestWithRunId("hello", "run-1"), chain);
		advisor.after(buildResponseWithRunId("the answer", "run-1"), chain);
		advisor.before(buildRequestWithRunId("hello", "run-1"), chain);
		advisor.after(buildResponseWithRunId("the answer", "run-1"), chain);

		assertThat(this.sessionService.getEvents(this.sessionId, EventFilter.all())).hasSize(2);
	}

	@Test
	void differentRunIdWithIdenticalContentIsNotTreatedAsACollision() {
		SessionMemoryAdvisor advisor = advisorWith(
				new IdempotentSessionEventIdGenerator(RUN_ID_KEY, SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY));
		AdvisorChain chain = mock(AdvisorChain.class);

		advisor.before(buildRequestWithRunId("hello", "run-1"), chain);
		advisor.after(buildResponseWithRunId("the answer", "run-1"), chain);
		advisor.before(buildRequestWithRunId("hello", "run-2"), chain);
		advisor.after(buildResponseWithRunId("the answer", "run-2"), chain);

		assertThat(this.sessionService.getEvents(this.sessionId, EventFilter.all())).hasSize(4);
	}

	@Test
	void replayedToolCallResponseReusesTheModelAssignedIdAndDoesNotDuplicate() {
		SessionMemoryAdvisor advisor = advisorWith(new IdempotentSessionEventIdGenerator());
		AdvisorChain chain = mock(AdvisorChain.class);

		AssistantMessage toolCallMessage = AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "get_weather", "{}")))
			.build();
		ChatClientResponse response = ChatClientResponse.builder()
			.chatResponse(ChatResponse.builder().generations(List.of(new Generation(toolCallMessage))).build())
			.context(Map.of(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId))
			.build();

		// Replaying the exact same tool-call response (e.g. a resumed process re-deriving
		// the same model turn) must not duplicate the tool-call event.
		advisor.after(response, chain);
		advisor.after(response, chain);

		assertThat(this.sessionService.getEvents(this.sessionId, EventFilter.all())).hasSize(1);
	}

	private SessionMemoryAdvisor advisorWith(IdempotentSessionEventIdGenerator generator) {
		return SessionMemoryAdvisor.builder(this.sessionService)
			.requestEventIdGenerator(generator)
			.responseEventIdGenerator(generator)
			.build();
	}

	private ChatClientRequest buildRequest(String userText) {
		Prompt prompt = new Prompt(List.of(new UserMessage(userText)));
		return ChatClientRequest.builder()
			.prompt(prompt)
			.context(Map.of(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId))
			.build();
	}

	private ChatClientRequest buildRequestWithRunId(String userText, String runId) {
		Prompt prompt = new Prompt(List.of(new UserMessage(userText)));
		return ChatClientRequest.builder()
			.prompt(prompt)
			.context(Map.of(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId, RUN_ID_KEY, runId))
			.build();
	}

	private ChatClientResponse buildResponse(String assistantText) {
		ChatResponse chatResponse = ChatResponse.builder()
			.generations(List.of(new Generation(new AssistantMessage(assistantText))))
			.build();
		return ChatClientResponse.builder()
			.chatResponse(chatResponse)
			.context(Map.of(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId))
			.build();
	}

	private ChatClientResponse buildResponseWithRunId(String assistantText, String runId) {
		ChatResponse chatResponse = ChatResponse.builder()
			.generations(List.of(new Generation(new AssistantMessage(assistantText))))
			.build();
		return ChatClientResponse.builder()
			.chatResponse(chatResponse)
			.context(Map.of(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId, RUN_ID_KEY, runId))
			.build();
	}

}
