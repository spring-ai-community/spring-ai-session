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
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.compaction.SlidingWindowCompactionStrategy;
import org.springframework.ai.session.compaction.TurnCountTrigger;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Integration tests for {@link SessionMemoryAdvisor} exercised end-to-end through a
 * Spring Boot application context. No external LLM is required — the advisor's
 * {@code before()} and {@code after()} hooks are invoked directly to verify that the
 * session is populated correctly and that conversation history is re-injected into
 * subsequent prompts.
 *
 * @author Christian Tzolov
 */
@SpringBootTest(classes = SessionMemoryAdvisorIT.TestConfig.class)
class SessionMemoryAdvisorIT {

	@Autowired
	SessionService sessionService;

	@Autowired
	SessionMemoryAdvisor advisor;

	private String sessionId;

	@BeforeEach
	void createSession() {
		Session session = this.sessionService.create(CreateSessionRequest.builder().userId("test-user").build());
		this.sessionId = session.id();
	}

	// --- Before hook ---

	@Test
	void beforeAppendsUserMessageToSession() {
		ChatClientRequest request = buildRequest(this.sessionId, "Hello, what is Spring AI?");
		AdvisorChain chain = mock(AdvisorChain.class);

		this.advisor.before(request, chain);

		List<SessionEvent> events = this.sessionService.getEvents(this.sessionId);
		assertThat(events).hasSize(1);
		assertThat(events.get(0).getMessage().getText()).isEqualTo("Hello, what is Spring AI?");
		assertThat(events.get(0).getMessageType().getValue()).isEqualTo("user");
	}

	@Test
	void beforeInjectsExistingHistoryIntoPromptMessages() {
		// Pre-populate the session with a prior turn
		this.sessionService.appendMessage(this.sessionId, new UserMessage("What is Spring?"));
		this.sessionService.appendMessage(this.sessionId, new AssistantMessage("Spring is a framework."));

		ChatClientRequest request = buildRequest(this.sessionId, "Tell me more about Spring AI.");
		AdvisorChain chain = mock(AdvisorChain.class);

		ChatClientRequest modified = this.advisor.before(request, chain);

		// The modified prompt must include the history + the new user message
		List<Message> combined = modified.prompt().getInstructions();
		assertThat(combined).hasSizeGreaterThanOrEqualTo(3);
		assertThat(combined.get(0).getText()).isEqualTo("What is Spring?");
		assertThat(combined.get(1).getText()).isEqualTo("Spring is a framework.");
		assertThat(combined.get(combined.size() - 1).getText()).isEqualTo("Tell me more about Spring AI.");
	}

	@Test
	void beforeFallsBackToDefaultSessionIdWhenContextKeyAbsent() {
		// Request without SESSION_ID_CONTEXT_KEY in context — advisor falls back to
		// its configured defaultSessionId (configured in the test as this.sessionId)
		SessionMemoryAdvisor advisorWithDefault = SessionMemoryAdvisor.builder(this.sessionService)
			.defaultSessionId(this.sessionId)
			.build();

		Prompt prompt = new Prompt(List.of(new UserMessage("hello via default")));
		ChatClientRequest request = ChatClientRequest.builder().prompt(prompt).context(Map.of()).build();
		AdvisorChain chain = mock(AdvisorChain.class);

		advisorWithDefault.before(request, chain);

		// The user message was appended to the session resolved via defaultSessionId
		List<SessionEvent> events = this.sessionService.getEvents(this.sessionId);
		assertThat(events).hasSize(1);
		assertThat(events.get(0).getMessage().getText()).isEqualTo("hello via default");
	}

	// --- After hook ---

	@Test
	void afterAppendsAssistantMessageToSession() {
		// Simulate before() having appended the user message
		this.sessionService.appendMessage(this.sessionId, new UserMessage("What is Spring AI?"));

		ChatClientResponse response = buildResponse(this.sessionId, "Spring AI is a framework for AI apps.");
		AdvisorChain chain = mock(AdvisorChain.class);

		this.advisor.after(response, chain);

		List<SessionEvent> events = this.sessionService.getEvents(this.sessionId);
		assertThat(events).hasSize(2);
		assertThat(events.get(1).getMessage().getText()).isEqualTo("Spring AI is a framework for AI apps.");
		assertThat(events.get(1).getMessageType().getValue()).isEqualTo("assistant");
	}

	// --- Full round-trip ---

	@Test
	void multiTurnConversationBuildsUpHistory() {
		AdvisorChain chain = mock(AdvisorChain.class);

		// Turn 1
		ChatClientRequest req1 = buildRequest(this.sessionId, "What is Spring AI?");
		this.advisor.before(req1, chain);
		this.advisor.after(buildResponse(this.sessionId, "Spring AI is an AI framework."), chain);

		// Turn 2
		ChatClientRequest req2 = buildRequest(this.sessionId, "How do I use it?");
		ChatClientRequest modified2 = this.advisor.before(req2, chain);
		this.advisor.after(buildResponse(this.sessionId, "Use ChatClient."), chain);

		// After turn 2's before(), history from turn 1 should be in the prompt
		List<Message> instructions = modified2.prompt().getInstructions();
		assertThat(instructions).hasSizeGreaterThanOrEqualTo(3);
		assertThat(instructions.get(0).getText()).isEqualTo("What is Spring AI?");
		assertThat(instructions.get(1).getText()).isEqualTo("Spring AI is an AI framework.");

		// Session should now have 4 events total
		List<SessionEvent> events = this.sessionService.getEvents(this.sessionId);
		assertThat(events).hasSize(4);
	}

	@Test
	void compactionIsTriggeredAfterThreshold() {
		// Wire advisor with a very low compaction threshold (1 turn) and small window
		SessionMemoryAdvisor compactingAdvisor = SessionMemoryAdvisor.builder(this.sessionService)
			.defaultSessionId(this.sessionId)
			.compactionTrigger(new TurnCountTrigger(2))
			.compactionStrategy(SlidingWindowCompactionStrategy.builder().maxEvents(2).build())
			.build();

		AdvisorChain chain = mock(AdvisorChain.class);

		// Append 3 complete turns (user + assistant each)
		for (int i = 1; i <= 3; i++) {
			compactingAdvisor.before(buildRequest(this.sessionId, "question " + i), chain);
			compactingAdvisor.after(buildResponse(this.sessionId, "answer " + i), chain);
		}

		// Compaction runs synchronously after each turn, so by the time we get here
		// the sliding window (maxEvents=2) has already been applied.
		List<SessionEvent> events = this.sessionService.getEvents(this.sessionId);
		assertThat(events.size()).isLessThanOrEqualTo(2);
	}

	@Test
	void beforePromotesAllSystemMessagesToFront() {
		// Simulate a session whose history already contains a system message (e.g. from
		// a previous turn that was stored), plus the current request also carries one.
		this.sessionService.appendMessage(this.sessionId, new SystemMessage("You are a helpful assistant."));
		this.sessionService.appendMessage(this.sessionId, new UserMessage("Hello"));
		this.sessionService.appendMessage(this.sessionId, new AssistantMessage("Hi!"));

		// Current request has its own system message
		Prompt prompt = new Prompt(
				List.of(new SystemMessage("Always reply in English."), new UserMessage("How are you?")));
		ChatClientRequest request = ChatClientRequest.builder()
			.prompt(prompt)
			.context(Map.of(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId))
			.build();
		AdvisorChain chain = mock(AdvisorChain.class);

		ChatClientRequest modified = this.advisor.before(request, chain);

		List<Message> instructions = modified.prompt().getInstructions();

		// Both system messages must be at the front — neither stranded mid-list
		assertThat(instructions.get(0)).isInstanceOf(SystemMessage.class);
		assertThat(instructions.get(1)).isInstanceOf(SystemMessage.class);
		// Non-system messages follow
		instructions.subList(2, instructions.size()).forEach(m -> assertThat(m).isNotInstanceOf(SystemMessage.class));
	}

	// --- historyFilter ---

	@Test
	void historyFilterExcludesSiblingBranchEvents() {
		// Root event (null branch) + orch.writer event (sibling branch) +
		// orch.researcher event (target branch).
		// An advisor configured for orch.researcher must see root + orch.researcher
		// events only; orch.writer events must be excluded from the injected history.
		this.sessionService.appendEvent(
				SessionEvent.builder().sessionId(this.sessionId).message(new UserMessage("root question")).build()); // null
																														// branch
		this.sessionService.appendEvent(SessionEvent.builder()
			.sessionId(this.sessionId)
			.message(new AssistantMessage("writer output"))
			.branch("orch.writer")
			.build());
		this.sessionService.appendEvent(SessionEvent.builder()
			.sessionId(this.sessionId)
			.message(new AssistantMessage("researcher output"))
			.branch("orch.researcher")
			.build());

		SessionMemoryAdvisor branchAdvisor = SessionMemoryAdvisor.builder(this.sessionService)
			.defaultSessionId(this.sessionId)
			.eventFilter(EventFilter.forBranch("orch.researcher"))
			.build();

		ChatClientRequest request = buildRequest(this.sessionId, "follow-up");
		AdvisorChain chain = mock(AdvisorChain.class);

		ChatClientRequest modified = branchAdvisor.before(request, chain);

		List<Message> instructions = modified.prompt().getInstructions();
		List<String> texts = instructions.stream().map(Message::getText).toList();

		assertThat(texts).contains("root question");
		assertThat(texts).contains("researcher output");
		assertThat(texts).doesNotContain("writer output");
	}

	// --- Per-request EventFilter override ---

	@Test
	void requestEventFilterOverridesAdvisorDefault() {
		// Populate 4 messages
		this.sessionService.appendMessage(this.sessionId, new UserMessage("msg1"));
		this.sessionService.appendMessage(this.sessionId, new AssistantMessage("reply1"));
		this.sessionService.appendMessage(this.sessionId, new UserMessage("msg2"));
		this.sessionService.appendMessage(this.sessionId, new AssistantMessage("reply2"));

		// Advisor has no filter (all), but request narrows to lastN=2
		Prompt prompt = new Prompt(List.of(new UserMessage("new question")));
		ChatClientRequest request = ChatClientRequest.builder()
			.prompt(prompt)
			.context(Map.of(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId,
					SessionMemoryAdvisor.EVENT_FILTER_CONTEXT_KEY, EventFilter.lastN(2)))
			.build();

		ChatClientRequest modified = this.advisor.before(request, mock(AdvisorChain.class));

		List<String> texts = modified.prompt().getInstructions().stream().map(Message::getText).toList();
		assertThat(texts).contains("msg2", "reply2");
		assertThat(texts).doesNotContain("msg1", "reply1");
	}

	@Test
	void nullRequestEventFilterIsIgnoredAndAdvisorDefaultIsUsed() {
		this.sessionService.appendMessage(this.sessionId, new UserMessage("only message"));

		Prompt prompt = new Prompt(List.of(new UserMessage("follow-up")));
		Map<String, Object> ctx = new java.util.HashMap<>();
		ctx.put(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId);
		ctx.put(SessionMemoryAdvisor.EVENT_FILTER_CONTEXT_KEY, null);
		ChatClientRequest request = ChatClientRequest.builder().prompt(prompt).context(ctx).build();

		ChatClientRequest modified = this.advisor.before(request, mock(AdvisorChain.class));

		List<String> texts = modified.prompt().getInstructions().stream().map(Message::getText).toList();
		assertThat(texts).contains("only message");
	}

	// --- Builder validation ---

	@Test
	void builderRejectsOnlyTriggerWithoutStrategy() {
		assertThatThrownBy(() -> SessionMemoryAdvisor.builder(this.sessionService)
			.compactionTrigger(new TurnCountTrigger(5))
			.build()).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("compactionTrigger and compactionStrategy must be set together");
	}

	@Test
	void builderRejectsOnlyStrategyWithoutTrigger() {
		assertThatThrownBy(() -> SessionMemoryAdvisor.builder(this.sessionService)
			.compactionStrategy(SlidingWindowCompactionStrategy.builder().maxEvents(5).build())
			.build()).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("compactionTrigger and compactionStrategy must be set together");
	}

	// --- Helpers ---

	private static ChatClientRequest buildRequest(String sessionId, String userText) {
		Prompt prompt = new Prompt(List.of(new UserMessage(userText)));
		return ChatClientRequest.builder()
			.prompt(prompt)
			.context(Map.of(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId))
			.build();
	}

	private static ChatClientResponse buildResponse(String sessionId, String assistantText) {
		ChatResponse chatResponse = ChatResponse.builder()
			.generations(List.of(new Generation(new AssistantMessage(assistantText))))
			.build();
		return ChatClientResponse.builder()
			.chatResponse(chatResponse)
			.context(Map.of(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId))
			.build();
	}

	// --- Spring Boot configuration ---

	@SpringBootConfiguration
	static class TestConfig {

		@Bean
		SessionRepository sessionRepository() {
			return InMemorySessionRepository.builder().build();
		}

		@Bean
		SessionService sessionService(SessionRepository sessionRepository) {
			return DefaultSessionService.builder().sessionRepository(sessionRepository).build();
		}

		@Bean
		SessionMemoryAdvisor sessionMemoryAdvisor(SessionService sessionService) {
			return SessionMemoryAdvisor.builder(sessionService).build();
		}

	}

}
