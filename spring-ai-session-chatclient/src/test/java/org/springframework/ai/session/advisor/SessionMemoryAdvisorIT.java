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

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.content.Media;
import org.springframework.ai.session.AgentSnapshot;
import org.springframework.ai.session.Budget;
import org.springframework.ai.session.BudgetPolicy;
import org.springframework.ai.session.BudgetReachedException;
import org.springframework.ai.session.ToolConfirmationPolicy;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.EventType;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.MessageFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.SessionStatus;
import org.springframework.ai.session.SessionStatusEvents;
import org.springframework.ai.session.StopReason;
import org.springframework.ai.session.TokenUsage;
import org.springframework.ai.session.ToolConfirmation;
import org.springframework.ai.session.ToolConfirmationEvents;
import org.springframework.ai.session.UsageEvents;
import org.springframework.ai.session.compaction.RecursiveSummarizationCompactionStrategy;
import org.springframework.ai.session.compaction.SlidingWindowCompactionStrategy;
import org.springframework.ai.session.compaction.TurnCountTrigger;
import org.springframework.ai.session.tool.SessionEventTools;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.util.MimeTypeUtils;

import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
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
		Session session = this.sessionService.create(CreateSessionRequest.builder().metadata(SessionAttributes.PRINCIPAL_NAME, "test-user").build());
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
	void beforeThrowsWhenSessionIdContextKeyAbsent() {
		Prompt prompt = new Prompt(List.of(new UserMessage("hello")));
		ChatClientRequest request = ChatClientRequest.builder().prompt(prompt).context(Map.of()).build();
		AdvisorChain chain = mock(AdvisorChain.class);

		assertThatThrownBy(() -> this.advisor.before(request, chain)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("SESSION_ID_CONTEXT_KEY");
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
	void beforeSkipsDuplicatingHistoryInToolLoopWhenSessionStoresASystemMessage() {
		// A stored system message plus the request's own system prompt: moving system
		// messages to the front leaves the request's prompt between the stored system
		// message and the rest of the history, which must not defeat the loop check.
		this.sessionService.appendMessage(this.sessionId, new SystemMessage("Answer in French."));
		this.sessionService.appendMessage(this.sessionId, new UserMessage("Hello"));
		this.sessionService.appendMessage(this.sessionId, new AssistantMessage("Bonjour !"));
		AdvisorChain chain = mock(AdvisorChain.class);
		SystemMessage requestSystem = new SystemMessage("Be brief.");
		UserMessage userMessage = new UserMessage("What is the weather in Paris?");
		AssistantMessage toolCallMessage = AssistantMessage.builder()
			.toolCalls(
					List.of(new AssistantMessage.ToolCall("call-1", "function", "get_weather", "{\"city\":\"Paris\"}")))
			.build();

		// Round 1
		List<Message> round1 = this.advisor
			.before(ChatClientRequest.builder()
				.prompt(new Prompt(List.of(requestSystem, userMessage)))
				.context(Map.of(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId))
				.build(), chain)
			.prompt()
			.getInstructions();
		this.advisor.after(buildResponseFromMessages(this.sessionId, toolCallMessage), chain);

		// Round 2: the looping advisor re-sends round 1's prompt plus the tool exchange.
		ToolResponseMessage toolResponse = ToolResponseMessage.builder()
			.responses(
					List.of(new ToolResponseMessage.ToolResponse("call-1", "get_weather", "15 degrees and sunny")))
			.build();
		List<Message> round2Input = new ArrayList<>(round1);
		round2Input.add(toolCallMessage);
		round2Input.add(toolResponse);
		List<Message> round2 = this.advisor
			.before(ChatClientRequest.builder()
				.prompt(new Prompt(round2Input))
				.context(Map.of(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId))
				.build(), chain)
			.prompt()
			.getInstructions();

		assertThat(round1).extracting(Message::getText)
			.containsExactly("Answer in French.", "Be brief.", "Hello", "Bonjour !", "What is the weather in Paris?");
		assertThat(round2).hasSize(7);
		assertThat(round2.subList(0, 5)).extracting(Message::getText)
			.containsExactly("Answer in French.", "Be brief.", "Hello", "Bonjour !", "What is the weather in Paris?");
		assertThat(round2.get(5)).isEqualTo(toolCallMessage);
		assertThat(round2.get(6)).isEqualTo(toolResponse);
	}

	@Test
	void beforePrependsHistoryWhenPromptOnlyMatchesItsText() {
		// The loop check compares messages by content, not equals(): a prompt message with
		// the same text but a different tool call is not the stored history. Both tool
		// calls are answered, so the tool-call guard leaves them alone.
		this.sessionService.appendMessage(this.sessionId, new UserMessage("What is the weather?"));
		this.sessionService.appendMessage(this.sessionId, AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "get_weather", "{\"city\":\"Paris\"}")))
			.build());
		this.sessionService.appendMessage(this.sessionId, toolResponse("call-1"));
		AssistantMessage otherToolCall = AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall("call-2", "function", "get_weather", "{\"city\":\"Rome\"}")))
			.build();

		List<Message> instructions = beforeWithPrompt(new UserMessage("What is the weather?"), otherToolCall,
				toolResponse("call-2"), new UserMessage("And tomorrow?"));

		assertThat(instructions).hasSize(7);
		assertThat(((AssistantMessage) instructions.get(1)).getToolCalls()).extracting(AssistantMessage.ToolCall::id)
			.containsExactly("call-1");
	}

	@Test
	void beforePrependsHistoryWhenPromptHasSameTextButDifferentMedia() {
		Media stored = Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(new byte[] { 1 }).build();
		Media other = Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(new byte[] { 2 }).build();
		this.sessionService.appendMessage(this.sessionId, UserMessage.builder().text("Describe").media(stored).build());
		this.sessionService.appendMessage(this.sessionId, new AssistantMessage("A cat."));

		List<Message> instructions = beforeWithPrompt(UserMessage.builder().text("Describe").media(other).build(),
				new AssistantMessage("A cat."), new UserMessage("And this one?"));

		assertThat(instructions).hasSize(5);
	}

	@Test
	void beforeAddsAStoredSystemMessageWhenTheSessionHasNoConversationYet() {
		// Only a system message is stored: the loop check sees no conversation to skip,
		// but the stored system message must still reach the prompt.
		this.sessionService.appendMessage(this.sessionId, new SystemMessage("Answer in French."));

		List<Message> instructions = beforeWithPrompt(new UserMessage("Hi"));

		assertThat(instructions).extracting(Message::getText).containsExactly("Answer in French.", "Hi");
	}

	@Test
	void beforeSkipsDuplicatingHistoryWhenNestedInsideToolCallingLoop() {
		// Simulates a looping advisor such as ToolCallingAdvisor re-entering the chain
		// once per tool-call round (see the class Javadoc "Nesting inside a
		// tool-calling loop" section). From round 2 onward, the incoming prompt
		// already carries this turn's messages -- the same messages round 1's
		// before()/after() just persisted -- so before() must not prepend them again.
		AdvisorChain chain = mock(AdvisorChain.class);

		UserMessage userMessage = new UserMessage("What is the weather in Paris?");
		AssistantMessage toolCallMessage = AssistantMessage.builder()
			.toolCalls(
					List.of(new AssistantMessage.ToolCall("call-1", "function", "get_weather", "{\"city\":\"Paris\"}")))
			.build();

		// Round 1: only the user message is in the prompt.
		this.advisor.before(buildRequest(this.sessionId, userMessage.getText()), chain);
		this.advisor.after(buildResponseFromMessages(this.sessionId, toolCallMessage), chain);

		// Round 2: the prompt already contains this turn's user message and tool-call
		// message, plus the fresh tool response.
		ToolResponseMessage toolResponse = ToolResponseMessage.builder()
			.responses(
					List.of(new ToolResponseMessage.ToolResponse("call-1", "get_weather", "15 degrees and sunny")))
			.build();
		Prompt round2Prompt = new Prompt(List.of(userMessage, toolCallMessage, toolResponse));
		ChatClientRequest round2Request = ChatClientRequest.builder()
			.prompt(round2Prompt)
			.context(Map.of(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId))
			.build();

		ChatClientRequest modified = this.advisor.before(round2Request, chain);

		// No duplication: the user message and tool-call message must not appear twice.
		List<Message> instructions = modified.prompt().getInstructions();
		assertThat(instructions).containsExactly(userMessage, toolCallMessage, toolResponse);

		// The tool response is persisted for the first time; the user/assistant pair
		// from round 1 is not persisted again.
		List<SessionEvent> events = this.sessionService.getEvents(this.sessionId);
		assertThat(events).hasSize(3);
		assertThat(events.get(2).getMessage()).isEqualTo(toolResponse);
	}

	@Test
	void compactionIsTriggeredAfterThreshold() {
		// Wire advisor with a very low compaction threshold (1 turn) and small window
		SessionMemoryAdvisor compactingAdvisor = SessionMemoryAdvisor.builder(this.sessionService).trackStatus(false)
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
		// the sliding window (maxEvents=2) has already been applied to the active
		// context.
		// (The compacted events are archived, not deleted, so the full log is larger.)
		List<SessionEvent> active = this.sessionService.getEvents(this.sessionId, EventFilter.active());
		assertThat(active.size()).isLessThanOrEqualTo(2);
	}

	@Test
	void compactionWaitsUntilTheTurnIsComplete() {
		AtomicInteger triggerCalls = new AtomicInteger();
		SessionMemoryAdvisor compactingAdvisor = SessionMemoryAdvisor.builder(this.sessionService).trackStatus(false)
			.compactionTrigger(request -> triggerCalls.incrementAndGet() > 0)
			.compactionStrategy(SlidingWindowCompactionStrategy.builder().maxEvents(2).build())
			.build();
		AdvisorChain chain = mock(AdvisorChain.class);
		AssistantMessage toolCall = AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "get_weather", "{}")))
			.build();

		compactingAdvisor.before(buildRequest(this.sessionId, "What is the weather?"), chain);
		compactingAdvisor.after(buildResponseFromMessages(this.sessionId, toolCall), chain);
		assertThat(triggerCalls).hasValue(0);

		compactingAdvisor.after(buildResponse(this.sessionId, "Sunny."), chain);
		assertThat(triggerCalls).hasValue(1);
	}

	@Test
	void summarizingCompactionInsideAToolLoopDoesNotResendHistory() {
		// A summarizing strategy that always fires: if it ran in the middle of the turn,
		// round 2's history would start with a summary the prompt doesn't carry and the
		// whole conversation would be sent twice.
		ChatClient summarizer = mock(ChatClient.class, Answers.RETURNS_DEEP_STUBS);
		given(summarizer.prompt().system(anyString()).user(anyString()).call().content()).willReturn("summary");
		SessionMemoryAdvisor compactingAdvisor = SessionMemoryAdvisor.builder(this.sessionService).trackStatus(false)
			.compactionTrigger(request -> true)
			.compactionStrategy(RecursiveSummarizationCompactionStrategy.builder(summarizer).maxEvents(4).build())
			.build();
		for (int i = 1; i <= 3; i++) {
			this.sessionService.appendMessage(this.sessionId, new UserMessage("question " + i));
			this.sessionService.appendMessage(this.sessionId, new AssistantMessage("answer " + i));
		}
		AdvisorChain chain = mock(AdvisorChain.class);
		AssistantMessage toolCall = AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "get_weather", "{}")))
			.build();
		ToolResponseMessage toolResponse = ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "get_weather", "Sunny")))
			.build();

		// Round 1
		List<Message> round1 = compactingAdvisor.before(buildRequest(this.sessionId, "Weather?"), chain)
			.prompt()
			.getInstructions();
		compactingAdvisor.after(buildResponseFromMessages(this.sessionId, toolCall), chain);

		// Round 2: the looping advisor re-sends round 1's prompt plus the tool exchange
		List<Message> round2Input = new ArrayList<>(round1);
		round2Input.add(toolCall);
		round2Input.add(toolResponse);
		List<Message> round2 = compactingAdvisor.before(ChatClientRequest.builder()
			.prompt(new Prompt(round2Input))
			.context(Map.of(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId))
			.build(), chain).prompt().getInstructions();

		assertThat(round2).containsExactlyElementsOf(round2Input);
	}

	@Test
	void compactionFailureDoesNotFailTheCall() {
		SessionMemoryAdvisor compactingAdvisor = SessionMemoryAdvisor.builder(this.sessionService).trackStatus(false)
			.compactionTrigger(request -> true)
			.compactionStrategy(request -> {
				throw new IllegalStateException("summarizer unavailable");
			})
			.build();
		AdvisorChain chain = mock(AdvisorChain.class);

		compactingAdvisor.before(buildRequest(this.sessionId, "question"), chain);
		ChatClientResponse response = buildResponse(this.sessionId, "answer");

		// The reply is returned as produced (with the stored event ids added to its context)
		assertThat(compactingAdvisor.after(response, chain).chatResponse()).isSameAs(response.chatResponse());
		assertThat(this.sessionService.getEvents(this.sessionId, EventFilter.active()))
			.extracting(e -> e.getMessage().getText())
			.containsExactly("question", "answer");
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

	@Test
	void beforeSendsAStoredSystemMessageIdenticalToTheRequestOneOnlyOnce() {
		this.sessionService.appendMessage(this.sessionId, new SystemMessage("Answer in French."));
		this.sessionService.appendMessage(this.sessionId, new UserMessage("Hello"));
		this.sessionService.appendMessage(this.sessionId, new AssistantMessage("Bonjour !"));

		List<Message> instructions = beforeWithPrompt(new SystemMessage("Answer in French."),
				new SystemMessage("Be brief."), new UserMessage("How are you?"));

		assertThat(instructions).filteredOn(SystemMessage.class::isInstance)
			.extracting(Message::getText)
			.containsExactly("Answer in French.", "Be brief.");
		assertThat(instructions.subList(0, 2)).allMatch(SystemMessage.class::isInstance);
		assertThat(instructions).extracting(Message::getText)
			.containsExactly("Answer in French.", "Be brief.", "Hello", "Bonjour !", "How are you?");
	}

	@Test
	void beforeSendsOnlyTheLatestStoredSystemMessage() {
		this.sessionService.appendMessage(this.sessionId, new SystemMessage("Answer in French."));
		this.sessionService.appendMessage(this.sessionId, new UserMessage("Hello"));
		this.sessionService.appendMessage(this.sessionId, new AssistantMessage("Bonjour !"));
		this.sessionService.appendMessage(this.sessionId, new SystemMessage("Answer in German."));

		List<Message> instructions = beforeWithPrompt(new UserMessage("How are you?"));

		assertThat(instructions).extracting(Message::getText)
			.containsExactly("Answer in German.", "Hello", "Bonjour !", "How are you?");
	}

	@Test
	void storedSystemPromptStillAppliesAfterCompaction() {
		// The system prompt was stored in turn 1; after later turns have been compacted it
		// must still apply.
		this.sessionService.appendMessage(this.sessionId, new UserMessage("Research Paris."));
		this.sessionService.appendMessage(this.sessionId, new SystemMessage("Researcher rules."));
		this.sessionService.appendMessage(this.sessionId, new AssistantMessage("Paris findings."));
		for (int i = 2; i <= 4; i++) {
			this.sessionService.appendMessage(this.sessionId, new UserMessage("question " + i));
			this.sessionService.appendMessage(this.sessionId, new AssistantMessage("answer " + i));
		}
		this.sessionService.compact(this.sessionId, request -> true,
				SlidingWindowCompactionStrategy.builder().maxEvents(2).build());
		List<Message> instructions = beforeWithPrompt(new UserMessage("Research Rome."));

		assertThat(instructions).filteredOn(SystemMessage.class::isInstance)
			.extracting(Message::getText)
			.containsExactly("Researcher rules.");
	}

	@Test
	void onlyTheLatestStoredSystemMessageSurvivesACompactionThatKeepsAnOlderOneInItsWindow() {
		// sys-old sits inside the window the compaction keeps; it is superseded by sys-new,
		// so the compaction covers it and the advisor sends sys-new alone
		this.sessionService.appendMessage(this.sessionId, new UserMessage("q1"));
		this.sessionService.appendMessage(this.sessionId, new AssistantMessage("a1"));
		this.sessionService.appendMessage(this.sessionId, new UserMessage("q2"));
		this.sessionService.appendMessage(this.sessionId, new SystemMessage("sys-old"));
		this.sessionService.appendMessage(this.sessionId, new AssistantMessage("a2"));
		this.sessionService.appendMessage(this.sessionId, new UserMessage("q3"));
		this.sessionService.appendMessage(this.sessionId, new SystemMessage("sys-new"));
		this.sessionService.appendMessage(this.sessionId, new AssistantMessage("a3"));
		this.sessionService.compact(this.sessionId, request -> true,
				SlidingWindowCompactionStrategy.builder().maxEvents(4).build());

		List<Message> instructions = beforeWithPrompt(new UserMessage("q4"));

		assertThat(instructions).extracting(Message::getText)
			.containsExactly("sys-new", "q2", "a2", "q3", "a3", "q4");
	}

	@Test
	void systemMessageInsideTheActiveWindowIsSentFirst() {
		// Compaction keeps the system message where it was stored, in the middle of the
		// active window; the advisor still sends it first.
		this.sessionService.appendMessage(this.sessionId, new UserMessage("question 1"));
		this.sessionService.appendMessage(this.sessionId, new AssistantMessage("answer 1"));
		this.sessionService.appendMessage(this.sessionId, new UserMessage("question 2"));
		this.sessionService.appendMessage(this.sessionId, new SystemMessage("Answer in French."));
		this.sessionService.appendMessage(this.sessionId, new AssistantMessage("answer 2"));
		this.sessionService.compact(this.sessionId, request -> true,
				SlidingWindowCompactionStrategy.builder().maxEvents(2).build());

		List<Message> instructions = beforeWithPrompt(new UserMessage("question 3"));

		assertThat(instructions).extracting(Message::getText)
			.containsExactly("Answer in French.", "question 2", "answer 2", "question 3");
	}

	@Test
	void beforeKeepsDistinctSystemMessagesInOrder() {
		this.sessionService.appendMessage(this.sessionId, new SystemMessage("Stored rule."));

		List<Message> instructions = beforeWithPrompt(new SystemMessage("Request rule."), new UserMessage("Hi"));

		assertThat(instructions).extracting(Message::getText).containsExactly("Stored rule.", "Request rule.", "Hi");
	}

	private List<Message> beforeWithPrompt(Message... messages) {
		ChatClientRequest request = ChatClientRequest.builder()
			.prompt(new Prompt(List.of(messages)))
			.context(Map.of(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId))
			.build();
		return this.advisor.before(request, mock(AdvisorChain.class)).prompt().getInstructions();
	}

	// --- Session metadata on first use ---

	@Test
	void beforeCreatesTheSessionWithTheContextMetadata() {
		String newId = "created-with-metadata-" + System.nanoTime();
		ChatClientRequest request = buildRequestWithMetadata(newId, "hello", Map.of("agent", "support"));

		this.advisor.before(request, mock(AdvisorChain.class));

		Session created = this.sessionService.findById(newId);
		assertThat(created).isNotNull();
		assertThat(created.metadata()).containsEntry("agent", "support");
	}

	@Test
	void beforeIgnoresTheContextMetadataForAnExistingSession() {
		ChatClientRequest request = buildRequestWithMetadata(this.sessionId, "hello", Map.of("agent", "support"));

		this.advisor.before(request, mock(AdvisorChain.class));

		assertThat(this.sessionService.findById(this.sessionId).metadata()).doesNotContainKey("agent");
	}

	@Test
	void beforeRejectsAContextMetadataValueThatIsNotAMap() {
		ChatClientRequest request = ChatClientRequest.builder()
			.prompt(new Prompt(List.of(new UserMessage("hello"))))
			.context(Map.of(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, "new-" + System.nanoTime(),
					SessionMemoryAdvisor.SESSION_METADATA_CONTEXT_KEY, "not a map"))
			.build();

		assertThatThrownBy(() -> this.advisor.before(request, mock(AdvisorChain.class)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining(SessionMemoryAdvisor.SESSION_METADATA_CONTEXT_KEY);
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

	// --- Empty assistant message filtering ---

	@Test
	void afterDoesNotStoreAssistantMessageWithBlankText() {
		// Bedrock Converse emits AssistantMessage("") to signal end_turn after tool use.
		// It must not be stored; replaying it on the next request causes a
		// ValidationException on Bedrock.
		ChatClientResponse response = buildResponseFromMessages(this.sessionId, new AssistantMessage(""));
		this.advisor.after(response, mock(AdvisorChain.class));

		assertThat(this.sessionService.getEvents(this.sessionId)).isEmpty();
	}

	@Test
	void afterDoesNotStoreAssistantMessageWithWhitespaceOnlyText() {
		ChatClientResponse response = buildResponseFromMessages(this.sessionId, new AssistantMessage("   "));
		this.advisor.after(response, mock(AdvisorChain.class));

		assertThat(this.sessionService.getEvents(this.sessionId)).isEmpty();
	}

	@Test
	void afterStoresAssistantMessageWithText() {
		// Regression guard: normal assistant messages must still be stored.
		ChatClientResponse response = buildResponse(this.sessionId, "Paris is the capital of France.");
		this.advisor.after(response, mock(AdvisorChain.class));

		List<SessionEvent> events = this.sessionService.getEvents(this.sessionId);
		assertThat(events).hasSize(1);
		assertThat(events.get(0).getMessage().getText()).isEqualTo("Paris is the capital of France.");
	}

	@Test
	void afterStoresAssistantMessageWithToolCallsEvenWhenTextIsBlank() {
		// An AssistantMessage with tool calls but no text is a real tool-invocation
		// event — it must not be filtered out.
		AssistantMessage withToolCalls = AssistantMessage.builder()
			.toolCalls(
					List.of(new AssistantMessage.ToolCall("call-1", "function", "get_weather", "{\"city\":\"Paris\"}")))
			.build();
		ChatClientResponse response = buildResponseFromMessages(this.sessionId, withToolCalls);
		this.advisor.after(response, mock(AdvisorChain.class));

		List<SessionEvent> events = this.sessionService.getEvents(this.sessionId);
		assertThat(events).hasSize(1);
		assertThat(events.get(0).hasToolCalls()).isTrue();
	}

	@Test
	void afterStoresOnlyNonEmptyMessagesFromMultiGenerationResponse() {
		// Bedrock scenario: the model response contains two generations — the real
		// tool-call invocation followed by an empty end_turn frame. Only the tool-call
		// generation must be persisted.
		AssistantMessage toolCallMessage = AssistantMessage.builder()
			.toolCalls(
					List.of(new AssistantMessage.ToolCall("call-1", "function", "get_weather", "{\"city\":\"Paris\"}")))
			.build();
		AssistantMessage emptyEndTurn = new AssistantMessage("");

		ChatClientResponse response = buildResponseFromMessages(this.sessionId, toolCallMessage, emptyEndTurn);
		this.advisor.after(response, mock(AdvisorChain.class));

		List<SessionEvent> events = this.sessionService.getEvents(this.sessionId);
		assertThat(events).hasSize(1);
		assertThat(events.get(0).hasToolCalls()).isTrue();
	}

	@Test
	void emptyEndTurnMessageIsNotReplayedOnNextTurn() {
		// Full Bedrock tool-call round-trip:
		// turn 1 before() → user message stored
		// turn 1 after() → ASSISTANT[tool calls] stored, ASSISTANT[""] skipped
		// turn 2 before() → history injected into prompt must not contain the empty frame
		AdvisorChain chain = mock(AdvisorChain.class);

		// Turn 1
		this.advisor.before(buildRequest(this.sessionId, "What is the weather in Paris?"), chain);

		AssistantMessage toolCallMessage = AssistantMessage.builder()
			.toolCalls(
					List.of(new AssistantMessage.ToolCall("call-1", "function", "get_weather", "{\"city\":\"Paris\"}")))
			.build();
		AssistantMessage emptyEndTurn = new AssistantMessage("");
		this.advisor.after(buildResponseFromMessages(this.sessionId, toolCallMessage, emptyEndTurn), chain);

		// Turn 2 — inspect the prompt that the advisor builds
		ChatClientRequest modified = this.advisor.before(buildRequest(this.sessionId, "Thanks!"), chain);

		List<String> texts = modified.prompt().getInstructions().stream().map(Message::getText).toList();
		// The empty end_turn frame must not appear in the injected history
		assertThat(texts).doesNotContain("");
		assertThat(texts).doesNotContain("   ");
		// Real events are still present
		assertThat(texts).contains("What is the weather in Paris?");
	}

	// --- Multimodal assistant message filtering ---

	@Test
	void afterStoresAssistantMessageWithMediaEvenWhenTextIsBlank() {
		// A multimodal model may respond with an image (or other media) and no text.
		// That is a valid, content-bearing message and must NOT be dropped.
		Media image = new Media(MimeTypeUtils.IMAGE_PNG, URI.create("https://example.com/image.png"));
		AssistantMessage withMedia = AssistantMessage.builder().media(List.of(image)).build();
		ChatClientResponse response = buildResponseFromMessages(this.sessionId, withMedia);
		this.advisor.after(response, mock(AdvisorChain.class));

		List<SessionEvent> events = this.sessionService.getEvents(this.sessionId);
		assertThat(events).hasSize(1);
		assertThat(events.get(0).getMessage()).isInstanceOf(AssistantMessage.class);
		assertThat(((AssistantMessage) events.get(0).getMessage()).getMedia()).hasSize(1);
	}

	@Test
	void afterStoresAssistantMessageWithMediaAndToolCallsEvenWhenTextIsBlank() {
		Media image = new Media(MimeTypeUtils.IMAGE_PNG, URI.create("https://example.com/image.png"));
		AssistantMessage withBoth = AssistantMessage.builder()
			.media(List.of(image))
			.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "describe_image", "{}")))
			.build();
		ChatClientResponse response = buildResponseFromMessages(this.sessionId, withBoth);
		this.advisor.after(response, mock(AdvisorChain.class));

		List<SessionEvent> events = this.sessionService.getEvents(this.sessionId);
		assertThat(events).hasSize(1);
		assertThat(events.get(0).hasToolCalls()).isTrue();
	}

	@Test
	void afterDropsAssistantMessageWithNullTextNoToolCallsAndNoMedia() {
		// Explicit null text (not just blank) with no other content — must be dropped.
		AssistantMessage nullText = AssistantMessage.builder().build();
		ChatClientResponse response = buildResponseFromMessages(this.sessionId, nullText);
		this.advisor.after(response, mock(AdvisorChain.class));

		assertThat(this.sessionService.getEvents(this.sessionId)).isEmpty();
	}

	// --- MessageFilter ---

	@Test
	void customMessageFilterBlocksUserMessagePersistenceInBefore() {
		// A filter rejecting USER messages must prevent persistence in before(), while
		// the outgoing prompt still carries the user message.
		SessionMemoryAdvisor filteringAdvisor = SessionMemoryAdvisor.builder(this.sessionService).trackStatus(false)
			.messageFilter(msg -> msg.getMessageType() != MessageType.USER)
			.build();

		ChatClientRequest request = buildRequest(this.sessionId, "do not store me");
		ChatClientRequest modified = filteringAdvisor.before(request, mock(AdvisorChain.class));

		assertThat(this.sessionService.getEvents(this.sessionId)).isEmpty();
		List<String> texts = modified.prompt().getInstructions().stream().map(Message::getText).toList();
		assertThat(texts).contains("do not store me");
	}

	@Test
	void customMessageFilterExcludesToolResponseMessages() {
		// Store user + assistant turns but keep verbose tool responses out of the
		// session log.
		SessionMemoryAdvisor filteringAdvisor = SessionMemoryAdvisor.builder(this.sessionService).trackStatus(false)
			.messageFilter(MessageFilter.byMessageType(MessageType.USER, MessageType.ASSISTANT)
				.and(MessageFilter.skipEmptyMessages()))
			.build();
		AdvisorChain chain = mock(AdvisorChain.class);

		// Regular user turn is persisted
		filteringAdvisor.before(buildRequest(this.sessionId, "What is the weather?"), chain);
		assertThat(this.sessionService.getEvents(this.sessionId)).hasSize(1);

		// A tool-response turn (prompt whose last message is a ToolResponseMessage) is
		// not persisted
		ToolResponseMessage toolResponse = ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "get_weather", "{\"temp\":21}")))
			.build();
		Prompt prompt = new Prompt(List.of(new UserMessage("What is the weather?"), toolResponse));
		ChatClientRequest toolRequest = ChatClientRequest.builder()
			.prompt(prompt)
			.context(Map.of(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId))
			.build();
		filteringAdvisor.before(toolRequest, chain);

		List<SessionEvent> events = this.sessionService.getEvents(this.sessionId);
		assertThat(events).hasSize(1);
		assertThat(events.get(0).getMessageType()).isEqualTo(MessageType.USER);
	}

	@Test
	void customMessageFilterAppliedInAfter() {
		// A content-based filter must gate which generations get persisted.
		SessionMemoryAdvisor filteringAdvisor = SessionMemoryAdvisor.builder(this.sessionService).trackStatus(false)
			.messageFilter(MessageFilter.containsText("confidential").negate())
			.build();

		ChatClientResponse response = buildResponseFromMessages(this.sessionId,
				new AssistantMessage("this is confidential data"), new AssistantMessage("public answer"));
		filteringAdvisor.after(response, mock(AdvisorChain.class));

		List<SessionEvent> events = this.sessionService.getEvents(this.sessionId);
		assertThat(events).hasSize(1);
		assertThat(events.get(0).getMessage().getText()).isEqualTo("public answer");
	}

	@Test
	void composedCustomFilterStillSkipsEmptyAssistantMessages() {
		// Composing a custom filter with skipEmptyMessages() preserves the
		// empty end_turn protection (compose, don't replace).
		SessionMemoryAdvisor filteringAdvisor = SessionMemoryAdvisor.builder(this.sessionService).trackStatus(false)
			.messageFilter(MessageFilter.byMessageType(MessageType.USER, MessageType.ASSISTANT)
				.and(MessageFilter.skipEmptyMessages()))
			.build();

		ChatClientResponse response = buildResponseFromMessages(this.sessionId, new AssistantMessage(""));
		filteringAdvisor.after(response, mock(AdvisorChain.class));

		assertThat(this.sessionService.getEvents(this.sessionId)).isEmpty();
	}

	// --- Pluggable event id generation ---

	@Test
	void requestEventIdGeneratorMakesRetriedBeforeCallIdempotent() {
		// A fixed id simulates a caller supplying a deterministic id for retry safety
		// (e.g. derived from an upstream durability layer's own idempotency key).
		SessionMemoryAdvisor deterministicAdvisor = SessionMemoryAdvisor.builder(this.sessionService).trackStatus(false)
			.requestEventIdGenerator((request, message) -> "fixed-request-id")
			.build();

		ChatClientRequest request = buildRequest(this.sessionId, "hello");
		AdvisorChain chain = mock(AdvisorChain.class);

		deterministicAdvisor.before(request, chain);
		deterministicAdvisor.before(request, chain);

		assertThat(this.sessionService.getEvents(this.sessionId)).hasSize(1);
	}

	@Test
	void responseEventIdGeneratorMakesRetriedAfterCallIdempotent() {
		SessionMemoryAdvisor deterministicAdvisor = SessionMemoryAdvisor.builder(this.sessionService).trackStatus(false)
			.responseEventIdGenerator((response, message) -> "fixed-response-id")
			.build();

		ChatClientResponse response = buildResponse(this.sessionId, "answer");
		AdvisorChain chain = mock(AdvisorChain.class);

		deterministicAdvisor.after(response, chain);
		deterministicAdvisor.after(response, chain);

		assertThat(this.sessionService.getEvents(this.sessionId)).hasSize(1);
	}

	@Test
	void defaultEventIdGeneratorsProduceDistinctIdsAcrossRepeatedCalls() {
		// Regression guard: without a custom generator, retried calls with identical
		// content must NOT be deduped -- the random default must keep producing fresh
		// ids, matching the pre-existing appendMessage() behaviour.
		ChatClientRequest request = buildRequest(this.sessionId, "hello");
		AdvisorChain chain = mock(AdvisorChain.class);

		this.advisor.before(request, chain);
		this.advisor.before(request, chain);

		assertThat(this.sessionService.getEvents(this.sessionId)).hasSize(2);
	}

	// --- Builder validation ---

	@Test
	void builderRejectsNullMessageFilter() {
		assertThatThrownBy(() -> SessionMemoryAdvisor.builder(this.sessionService).trackStatus(false).messageFilter(null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("messageFilter must not be null");
	}

	@Test
	void builderRejectsOnlyTriggerWithoutStrategy() {
		assertThatThrownBy(() -> SessionMemoryAdvisor.builder(this.sessionService).trackStatus(false)
			.compactionTrigger(new TurnCountTrigger(5))
			.build()).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("compactionTrigger and compactionStrategy must be set together");
	}

	@Test
	void builderRejectsOnlyStrategyWithoutTrigger() {
		assertThatThrownBy(() -> SessionMemoryAdvisor.builder(this.sessionService).trackStatus(false)
			.compactionStrategy(SlidingWindowCompactionStrategy.builder().maxEvents(5).build())
			.build()).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("compactionTrigger and compactionStrategy must be set together");
	}

	// --- Helpers ---

	// --- Tool-call integrity ---

	@Test
	void lastNWindowNeverSplitsAToolCallFromItsResult() {
		// #44: a lastN window that landed on the tool result used to send an assistant
		// tool call without the tool message answering it, which providers reject.
		storeToolTurn(this.sessionId, "Weather in Paris?", "call-1", "Sunny.");
		SessionMemoryAdvisor windowed = SessionMemoryAdvisor.builder(this.sessionService).trackStatus(false)
			.eventFilter(EventFilter.lastN(2))
			.build();

		ChatClientRequest modified = windowed.before(buildRequest(this.sessionId, "And tomorrow?"),
				mock(AdvisorChain.class));

		assertThat(describe(modified.prompt().getInstructions())).containsExactly("Weather in Paris?",
				"call:call-1", "result:call-1", "Sunny.", "And tomorrow?");
	}

	@Test
	void messageTypesFilterHidingToolResultsDropsTheStrandedToolCall() {
		storeToolTurn(this.sessionId, "Weather in Paris?", "call-1", "Sunny.");
		SessionMemoryAdvisor noToolMessages = SessionMemoryAdvisor.builder(this.sessionService).trackStatus(false)
			.eventFilter(EventFilter.builder().messageTypes(Set.of(MessageType.USER, MessageType.ASSISTANT)).build())
			.build();

		ChatClientRequest modified = noToolMessages.before(buildRequest(this.sessionId, "And tomorrow?"),
				mock(AdvisorChain.class));

		assertThat(describe(modified.prompt().getInstructions())).containsExactly("Weather in Paris?", "Sunny.",
				"And tomorrow?");
	}

	@Test
	void aToolCallLeftByAnInterruptedTurnIsDroppedFromThePrompt() {
		// The process died between the tool call and its result
		this.sessionService.appendMessage(this.sessionId, new UserMessage("Weather in Paris?"));
		this.sessionService.appendMessage(this.sessionId, toolCall("call-1"));

		ChatClientRequest modified = this.advisor.before(buildRequest(this.sessionId, "Are you there?"),
				mock(AdvisorChain.class));

		assertThat(describe(modified.prompt().getInstructions())).containsExactly("Weather in Paris?",
				"Are you there?");
	}

	@Test
	void aToolCallAnsweredByThePromptItselfIsKept() {
		// Round 2 of a tool loop with the looping advisor's history disabled: the prompt
		// carries only the tool result, the call it answers is the last stored event
		this.sessionService.appendMessage(this.sessionId, new UserMessage("Weather in Paris?"));
		this.sessionService.appendMessage(this.sessionId, toolCall("call-1"));
		ChatClientRequest round2 = ChatClientRequest.builder()
			.prompt(new Prompt(List.of(toolResponse("call-1"))))
			.context(Map.of(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId))
			.build();

		ChatClientRequest modified = this.advisor.before(round2, mock(AdvisorChain.class));

		assertThat(describe(modified.prompt().getInstructions())).containsExactly("Weather in Paris?", "call:call-1",
				"result:call-1");
		assertThat(this.sessionService.getEvents(this.sessionId)).hasSize(3);
	}

	@Test
	void parallelToolCallsAreKeptWhenFullyAnsweredAndDroppedWhenPartiallyAnswered() {
		AssistantMessage twoCalls = AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "get_weather", "{}"),
					new AssistantMessage.ToolCall("call-2", "function", "get_time", "{}")))
			.build();
		this.sessionService.appendMessage(this.sessionId, new UserMessage("Weather and time?"));
		this.sessionService.appendMessage(this.sessionId, twoCalls);
		this.sessionService.appendMessage(this.sessionId,
				ToolResponseMessage.builder()
					.responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "get_weather", "Sunny"),
							new ToolResponseMessage.ToolResponse("call-2", "get_time", "noon")))
					.build());
		this.sessionService.appendMessage(this.sessionId, new AssistantMessage("Sunny, at noon."));

		ChatClientRequest full = this.advisor.before(buildRequest(this.sessionId, "Thanks"), mock(AdvisorChain.class));
		assertThat(describe(full.prompt().getInstructions())).containsExactly("Weather and time?", "call:call-1",
				"result:call-1", "Sunny, at noon.", "Thanks");

		String partial = this.sessionService.create(CreateSessionRequest.builder().metadata(SessionAttributes.PRINCIPAL_NAME, "test-user").build()).id();
		this.sessionService.appendMessage(partial, new UserMessage("Weather and time?"));
		this.sessionService.appendMessage(partial, twoCalls);
		this.sessionService.appendMessage(partial, toolResponse("call-1"));
		this.sessionService.appendMessage(partial, new AssistantMessage("Sunny."));

		ChatClientRequest halved = this.advisor.before(buildRequest(partial, "Thanks"), mock(AdvisorChain.class));
		assertThat(describe(halved.prompt().getInstructions())).containsExactly("Weather and time?", "Sunny.",
				"Thanks");
	}

	@Test
	void aToolResponseWhoseCallWasStoredIsPersistedDespiteTheMessageFilter() {
		SessionMemoryAdvisor filteringAdvisor = SessionMemoryAdvisor.builder(this.sessionService).trackStatus(false)
			.messageFilter(MessageFilter.byMessageType(MessageType.USER, MessageType.ASSISTANT))
			.build();
		AdvisorChain chain = mock(AdvisorChain.class);
		UserMessage user = new UserMessage("Weather in Paris?");
		AssistantMessage call = toolCall("call-1");
		filteringAdvisor.before(buildRequest(this.sessionId, user.getText()), chain);
		filteringAdvisor.after(buildResponseFromMessages(this.sessionId, call), chain);

		ChatClientRequest round2 = ChatClientRequest.builder()
			.prompt(new Prompt(List.of(user, call, toolResponse("call-1"))))
			.context(Map.of(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId))
			.build();
		filteringAdvisor.before(round2, chain);

		// The filter would drop the TOOL message, but its call is in the log
		assertThat(this.sessionService.getEvents(this.sessionId)).extracting(SessionEvent::getMessageType)
			.containsExactly(MessageType.USER, MessageType.ASSISTANT, MessageType.TOOL);
	}

	@Test
	void aToolResponseWhoseCallWasNotStoredIsSkippedDespiteTheMessageFilter() {
		SessionMemoryAdvisor filteringAdvisor = SessionMemoryAdvisor.builder(this.sessionService).trackStatus(false)
			.messageFilter(message -> !(message instanceof AssistantMessage assistant && assistant.hasToolCalls()))
			.build();
		AdvisorChain chain = mock(AdvisorChain.class);
		UserMessage user = new UserMessage("Weather in Paris?");
		AssistantMessage call = toolCall("call-1");
		filteringAdvisor.before(buildRequest(this.sessionId, user.getText()), chain);
		filteringAdvisor.after(buildResponseFromMessages(this.sessionId, call), chain);

		ChatClientRequest round2 = ChatClientRequest.builder()
			.prompt(new Prompt(List.of(user, call, toolResponse("call-1"))))
			.context(Map.of(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId))
			.build();
		filteringAdvisor.before(round2, chain);

		// The filter accepts the TOOL message, but the call it answers was never stored
		assertThat(this.sessionService.getEvents(this.sessionId)).extracting(SessionEvent::getMessageType)
			.containsExactly(MessageType.USER);
	}

	@Test
	void storedSystemPromptIsKeptUnderALastNWindow() {
		this.sessionService.appendMessage(this.sessionId, new SystemMessage("Be brief."));
		for (int i = 1; i <= 3; i++) {
			this.sessionService.appendMessage(this.sessionId, new UserMessage("q" + i));
			this.sessionService.appendMessage(this.sessionId, new AssistantMessage("a" + i));
		}
		SessionMemoryAdvisor windowed = SessionMemoryAdvisor.builder(this.sessionService).trackStatus(false)
			.eventFilter(EventFilter.lastN(2))
			.build();

		ChatClientRequest modified = windowed.before(buildRequest(this.sessionId, "q4"), mock(AdvisorChain.class));

		assertThat(describe(modified.prompt().getInstructions())).containsExactly("Be brief.", "q3", "a3", "q4");
	}

	private void storeToolTurn(String sessionId, String question, String callId, String answer) {
		this.sessionService.appendMessage(sessionId, new UserMessage(question));
		this.sessionService.appendMessage(sessionId, toolCall(callId));
		this.sessionService.appendMessage(sessionId, toolResponse(callId));
		this.sessionService.appendMessage(sessionId, new AssistantMessage(answer));
	}

	private static AssistantMessage toolCall(String callId) {
		return AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall(callId, "function", "get_weather", "{\"city\":\"Paris\"}")))
			.build();
	}

	private static ToolResponseMessage toolResponse(String callId) {
		return ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse(callId, "get_weather", "Sunny")))
			.build();
	}

	/** The text, "call:id" for an assistant tool call, "result:id" for a tool result. */
	private static List<String> describe(List<Message> messages) {
		return messages.stream().map(m -> {
			if (m instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
				return "call:" + assistant.getToolCalls().get(0).id();
			}
			if (m instanceof ToolResponseMessage responses) {
				return "result:" + responses.getResponses().get(0).id();
			}
			return m.getText();
		}).toList();
	}

	private static ChatClientRequest buildRequest(String sessionId, String userText) {
		Prompt prompt = new Prompt(List.of(new UserMessage(userText)));
		return ChatClientRequest.builder()
			.prompt(prompt)
			.context(Map.of(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId))
			.build();
	}

	private static ChatClientRequest buildRequestWithMetadata(String sessionId, String userText,
			Map<String, Object> metadata) {
		Prompt prompt = new Prompt(List.of(new UserMessage(userText)));
		return ChatClientRequest.builder()
			.prompt(prompt)
			.context(Map.of(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId,
					SessionMemoryAdvisor.SESSION_METADATA_CONTEXT_KEY, metadata))
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

	/**
	 * Builds a response whose {@code ChatResponse} contains one {@code Generation} per
	 * supplied {@link AssistantMessage}. Use this to simulate multi-generation responses
	 * such as the Bedrock Converse end_turn pattern (tool-call frame + empty frame).
	 */
	private static ChatClientResponse buildResponseFromMessages(String sessionId, AssistantMessage... messages) {
		List<Generation> generations = List.of(messages).stream().map(Generation::new).toList();
		ChatResponse chatResponse = ChatResponse.builder().generations(generations).build();
		return ChatClientResponse.builder()
			.chatResponse(chatResponse)
			.context(Map.of(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId))
			.build();
	}

	// --- Turn status ---

	private SessionMemoryAdvisor tracking() {
		return SessionMemoryAdvisor.builder(this.sessionService).build();
	}

	@Test
	void aCallRunsATurnAndRecordsItInTheStatusAndTheLog() {
		SessionMemoryAdvisor tracking = tracking();
		CallAdvisorChain chain = mock(CallAdvisorChain.class);
		given(chain.nextCall(any())).willAnswer(invocation -> {
			assertThat(this.sessionService.findById(this.sessionId).status()).isEqualTo(SessionStatus.RUNNING);
			return buildResponse(this.sessionId, "Hi!");
		});

		tracking.adviseCall(buildRequest(this.sessionId, "Hello"), chain);

		Session session = this.sessionService.findById(this.sessionId);
		assertThat(session.status()).isEqualTo(SessionStatus.IDLE);
		assertThat(session.stopReason()).isEqualTo(StopReason.END_TURN);
		assertThat(this.sessionService.getEvents(this.sessionId, EventFilter.builder().eventTypes(Set.of(EventType.STATUS)).build()))
			.extracting(SessionStatusEvents::status)
			.containsExactly(SessionStatus.RUNNING, SessionStatus.IDLE);
		// The conversation is untouched by the status events
		assertThat(describe(this.sessionService.getActiveMessages(this.sessionId))).containsExactly("Hello", "Hi!");
	}

	@Test
	void aToolLoopKeepsTheSessionRunningUntilTheFinalReply() {
		SessionMemoryAdvisor tracking = tracking();
		AdvisorChain chain = mock(AdvisorChain.class);

		tracking.before(buildRequest(this.sessionId, "Weather?"), chain);
		tracking.after(buildResponseFromMessages(this.sessionId, toolCall("c1")), chain);
		assertThat(this.sessionService.findById(this.sessionId).status()).isEqualTo(SessionStatus.RUNNING);

		// Round 2: the prompt ends with the tool result, so the running turn continues
		ChatClientRequest round2 = ChatClientRequest.builder()
			.prompt(new Prompt(List.of(new UserMessage("Weather?"), toolCall("c1"), toolResponse("c1"))))
			.context(Map.of(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId))
			.build();
		tracking.before(round2, chain);
		assertThat(this.sessionService.findById(this.sessionId).status()).isEqualTo(SessionStatus.RUNNING);
		tracking.after(buildResponse(this.sessionId, "Sunny."), chain);

		Session session = this.sessionService.findById(this.sessionId);
		assertThat(session.status()).isEqualTo(SessionStatus.IDLE);
		assertThat(session.stopReason()).isEqualTo(StopReason.END_TURN);
	}

	@Test
	void aFailedCallEndsTheTurnWithAnError() {
		SessionMemoryAdvisor tracking = tracking();
		CallAdvisorChain chain = mock(CallAdvisorChain.class);
		given(chain.nextCall(any())).willThrow(new IllegalStateException("model down"));

		assertThatThrownBy(() -> tracking.adviseCall(buildRequest(this.sessionId, "Hello"), chain))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("model down");

		Session session = this.sessionService.findById(this.sessionId);
		assertThat(session.status()).isEqualTo(SessionStatus.IDLE);
		assertThat(session.stopReason()).isEqualTo(StopReason.ERROR);
		// The next call starts a fresh turn
		assertThat(tracking.before(buildRequest(this.sessionId, "Again"), mock(AdvisorChain.class))).isNotNull();
	}

	@Test
	void aFailedStreamEndsTheTurnWithAnError() {
		SessionMemoryAdvisor tracking = tracking();
		StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
		given(chain.nextStream(any())).willReturn(Flux.error(new IllegalStateException("stream down")));

		assertThatThrownBy(() -> tracking.adviseStream(buildRequest(this.sessionId, "Hello"), chain).blockLast())
			.hasMessageContaining("stream down");

		Session session = this.sessionService.findById(this.sessionId);
		assertThat(session.status()).isEqualTo(SessionStatus.IDLE);
		assertThat(session.stopReason()).isEqualTo(StopReason.ERROR);
	}

	@Test
	void aFailureInsideBeforeEndsTheTurnItStarted() {
		SessionMemoryAdvisor tracking = tracking();
		ChatClientRequest badFilter = ChatClientRequest.builder()
			.prompt(new Prompt(List.of(new UserMessage("Hello"))))
			.context(Map.of(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId,
					SessionMemoryAdvisor.EVENT_FILTER_CONTEXT_KEY, "not a filter"))
			.build();

		assertThatThrownBy(() -> tracking.before(badFilter, mock(AdvisorChain.class)))
			.isInstanceOf(IllegalArgumentException.class);

		Session session = this.sessionService.findById(this.sessionId);
		assertThat(session.status()).isEqualTo(SessionStatus.IDLE);
		assertThat(session.stopReason()).isEqualTo(StopReason.ERROR);
		// A rejected start (another caller's turn) is left alone: it is not ours to end
		this.sessionService.startTurn(this.sessionId);
		assertThatThrownBy(() -> tracking.before(buildRequest(this.sessionId, "Again"), mock(AdvisorChain.class)))
			.isInstanceOf(IllegalStateException.class);
		assertThat(this.sessionService.findById(this.sessionId).status()).isEqualTo(SessionStatus.RUNNING);
	}

	@Test
	void aSecondConcurrentTurnOnTheSameSessionIsRejected() {
		SessionMemoryAdvisor tracking = tracking();
		AdvisorChain chain = mock(AdvisorChain.class);
		tracking.before(buildRequest(this.sessionId, "First"), chain);

		assertThatThrownBy(() -> tracking.before(buildRequest(this.sessionId, "Second"), chain))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("could not start a turn: it is RUNNING");

		// Ending the abandoned turn by hand opens the session again
		this.sessionService.endTurn(this.sessionId, StopReason.INTERRUPTED);
		assertThat(tracking.before(buildRequest(this.sessionId, "Second"), chain)).isNotNull();
	}

	@Test
	void trackStatusFalseLeavesTheStatusAlone() {
		AdvisorChain chain = mock(AdvisorChain.class);
		this.advisor.before(buildRequest(this.sessionId, "Hello"), chain);
		this.advisor.after(buildResponse(this.sessionId, "Hi!"), chain);

		assertThat(this.sessionService.findById(this.sessionId).status()).isEqualTo(SessionStatus.IDLE);
		assertThat(this.sessionService.getEvents(this.sessionId, EventFilter.builder().eventTypes(Set.of(EventType.STATUS)).build()))
			.isEmpty();
	}

	// --- Tool confirmation ---

	record City(String city) {
	}

	private static ToolCallingChatOptions withWeatherTool() {
		return ToolCallingChatOptions.builder()
			.toolCallbacks(FunctionToolCallback.builder("get_weather", (City city) -> "Sunny in " + city.city())
				.description("The weather in a city")
				.inputType(City.class)
				.build())
			.build();
	}

	private ChatClientRequest confirming(ChatOptions options, Object confirmations, Message... messages) {
		return ChatClientRequest.builder()
			.prompt(new Prompt(List.of(messages), options))
			.context(Map.of(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId,
					ToolConfirmationAdvisor.TOOL_CONFIRMATION_CONTEXT_KEY, confirmations))
			.build();
	}

	private ToolConfirmationAdvisor gate() {
		return ToolConfirmationAdvisor.builder(this.sessionService)
			.policy(ToolConfirmationPolicy.forTools("get_weather"))
			.build();
	}

	/** Runs the two advisors in their order for one round: gate outside, memory advisor inside. */
	private ChatClientRequest before(ToolConfirmationAdvisor gate, SessionMemoryAdvisor memory,
			ChatClientRequest request, AdvisorChain chain) {
		return memory.before(gate.before(request, chain), chain);
	}

	private ChatClientResponse after(ToolConfirmationAdvisor gate, SessionMemoryAdvisor memory,
			ChatClientResponse response, AdvisorChain chain) {
		return gate.after(memory.after(response, chain), chain);
	}

	@Test
	void aReplyThatNeedsConfirmationParksTheTurnNamingTheAssistantEvent() {
		ToolConfirmationAdvisor gate = gate();
		SessionMemoryAdvisor memory = tracking();
		AdvisorChain chain = mock(AdvisorChain.class);
		before(gate, memory, buildRequest(this.sessionId, "Weather in Paris?"), chain);

		after(gate, memory, buildResponseFromMessages(this.sessionId, toolCall("c1")), chain);

		Session session = this.sessionService.findById(this.sessionId);
		assertThat(session.status()).isEqualTo(SessionStatus.IDLE);
		assertThat(session.stopReason()).isEqualTo(StopReason.REQUIRES_ACTION);
		List<SessionEvent> log = this.sessionService.getEvents(this.sessionId);
		SessionEvent assistant = log.stream().filter(SessionEvent::hasToolCalls).findFirst().orElseThrow();
		SessionEvent parked = log.get(log.size() - 1);
		assertThat(SessionStatusEvents.stopReason(parked)).isEqualTo(StopReason.REQUIRES_ACTION);
		assertThat(SessionStatusEvents.eventIds(parked)).containsExactly(assistant.getId());

		// A call the policy does not name is left to the loop: the turn stays running
		String free = this.sessionService.create(CreateSessionRequest.builder().build()).id();
		before(gate, memory, buildRequest(free, "Hi"), chain);
		after(gate, memory, buildResponseFromMessages(free, AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall("c9", "function", "read_file", "{}")))
			.build()), chain);
		assertThat(this.sessionService.findById(free).status()).isEqualTo(SessionStatus.RUNNING);
	}

	@Test
	void anAllowedCallIsRunRecordedAndSentToTheModelWithThePrompt() {
		ToolConfirmationAdvisor gate = gate();
		SessionMemoryAdvisor memory = tracking();
		AdvisorChain chain = mock(AdvisorChain.class);
		before(gate, memory, buildRequest(this.sessionId, "Weather in Paris?"), chain);
		after(gate, memory, buildResponseFromMessages(this.sessionId, toolCall("c1")), chain);

		ChatClientRequest resumed = before(gate, memory, confirming(withWeatherTool(), ToolConfirmation.allow("c1")),
				chain);

		// The prompt: history (question, the pending tool call) then the tool result
		assertThat(describe(resumed.prompt().getInstructions())).containsExactly("Weather in Paris?", "call:c1",
				"result:c1");
		ToolResponseMessage result = (ToolResponseMessage) resumed.prompt().getInstructions().get(2);
		// The callback returns the tool's result JSON-encoded
		assertThat(result.getResponses().get(0).responseData()).isEqualTo("\"Sunny in Paris\"");
		// The log: the turn runs again, the decision and the tool result are recorded once
		List<SessionEvent> log = this.sessionService.getEvents(this.sessionId);
		assertThat(log).extracting(SessionEvent::getType)
			.containsSubsequence(EventType.MESSAGE, EventType.STATUS, EventType.STATUS, EventType.TOOL_CONFIRMATION,
					EventType.MESSAGE);
		SessionEvent decision = log.stream()
			.filter(e -> e.getType() == EventType.TOOL_CONFIRMATION)
			.findFirst()
			.orElseThrow();
		assertThat(ToolConfirmationEvents.confirmation(decision)).isEqualTo(ToolConfirmation.allow("c1"));
		assertThat(this.sessionService.findById(this.sessionId).status()).isEqualTo(SessionStatus.RUNNING);
		assertThat(describe(this.sessionService.getActiveMessages(this.sessionId))).containsExactly("Weather in Paris?",
				"call:c1", "result:c1");

		after(gate, memory, buildResponse(this.sessionId, "It is sunny in Paris."), chain);
		assertThat(this.sessionService.findById(this.sessionId).stopReason()).isEqualTo(StopReason.END_TURN);
	}

	@Test
	void aDeniedCallAnswersTheModelWithTheDenyMessageWithoutRunningTheTool() {
		ToolConfirmationAdvisor gate = gate();
		SessionMemoryAdvisor memory = tracking();
		AdvisorChain chain = mock(AdvisorChain.class);
		before(gate, memory, buildRequest(this.sessionId, "Weather in Paris?"), chain);
		after(gate, memory, buildResponseFromMessages(this.sessionId, toolCall("c1")), chain);

		// No tools on the request: a denied call never runs, so none are needed
		ChatClientRequest resumed = before(gate, memory, confirming(ChatOptions.builder().build(),
				List.of(ToolConfirmation.deny("c1", "No weather today."))), chain);

		assertThat(describe(resumed.prompt().getInstructions())).containsExactly("Weather in Paris?", "call:c1",
				"result:c1");
		ToolResponseMessage result = (ToolResponseMessage) resumed.prompt().getInstructions().get(2);
		assertThat(result.getResponses().get(0).responseData()).isEqualTo("No weather today.");
		assertThat(this.sessionService.getActiveMessages(this.sessionId)).hasSize(3);
	}

	@Test
	void aDecisionIsCheckedAgainstThePendingCallsBeforeAnythingIsRecorded() {
		ToolConfirmationAdvisor gate = gate();
		SessionMemoryAdvisor memory = tracking();
		AdvisorChain chain = mock(AdvisorChain.class);

		// Nothing pending
		assertThatThrownBy(() -> gate.before(confirming(withWeatherTool(), ToolConfirmation.allow("c1")), chain))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("not waiting");

		before(gate, memory, buildRequest(this.sessionId, "Weather in Paris?"), chain);
		after(gate, memory, buildResponseFromMessages(this.sessionId, toolCall("c1")), chain);
		// The wrong call
		assertThatThrownBy(() -> gate.before(confirming(withWeatherTool(), ToolConfirmation.allow("other")), chain))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("not waiting on tool call 'other'");
		// A bad context value
		assertThatThrownBy(() -> gate.before(confirming(withWeatherTool(), "yes please"), chain))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining(ToolConfirmationAdvisor.TOOL_CONFIRMATION_CONTEXT_KEY);
		// New user text goes in the next turn
		assertThatThrownBy(() -> gate.before(
				confirming(withWeatherTool(), ToolConfirmation.allow("c1"), new UserMessage("and Rome?")), chain))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("no new user message");
		// An allowed call needs the tools to run with
		assertThatThrownBy(() -> gate.before(confirming(ChatOptions.builder().build(), ToolConfirmation.allow("c1")),
				chain)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("ToolCallingChatOptions");
		// None of that touched the session: still parked, nothing recorded
		assertThat(this.sessionService.findById(this.sessionId).stopReason()).isEqualTo(StopReason.REQUIRES_ACTION);
		assertThat(this.sessionService.getEvents(this.sessionId))
			.noneMatch(e -> e.getType() == EventType.TOOL_CONFIRMATION);

		// The request's tools lack the confirmed one: the decision is recorded, the run
		// fails and the turn ends with an error, so the call can be confirmed again
		assertThatThrownBy(() -> gate.before(confirming(ToolCallingChatOptions.builder().build(),
				ToolConfirmation.allow("c1")), chain)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("get_weather");
		assertThat(this.sessionService.findById(this.sessionId).stopReason()).isEqualTo(StopReason.ERROR);
		assertThat(this.sessionService.getEvents(this.sessionId))
			.filteredOn(e -> e.getType() == EventType.TOOL_CONFIRMATION)
			.hasSize(1);
		ChatClientRequest resumed = before(gate, memory, confirming(withWeatherTool(), ToolConfirmation.allow("c1")),
				chain);
		assertThat(describe(resumed.prompt().getInstructions())).containsExactly("Weather in Paris?", "call:c1",
				"result:c1");
	}

	@Test
	void anUngatedCallParkedWithAGatedOneIsAllowedImplicitly() {
		ToolConfirmationAdvisor gate = gate();
		SessionMemoryAdvisor memory = tracking();
		AdvisorChain chain = mock(AdvisorChain.class);
		before(gate, memory, buildRequest(this.sessionId, "Weather in Paris?"), chain);
		AssistantMessage both = AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "get_weather", "{\"city\":\"Paris\"}"),
					new AssistantMessage.ToolCall("c2", "function", "get_time", "{\"city\":\"Paris\"}")))
			.build();
		after(gate, memory, buildResponseFromMessages(this.sessionId, both), chain);
		assertThat(this.sessionService.findById(this.sessionId).stopReason()).isEqualTo(StopReason.REQUIRES_ACTION);

		// The gated call still needs its decision
		assertThatThrownBy(() -> gate.before(confirming(withWeatherTool(), ToolConfirmation.allow("c2")), chain))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("missing: [c1]");
		ToolCallingChatOptions tools = ToolCallingChatOptions.builder()
			.toolCallbacks(FunctionToolCallback.builder("get_weather", (City city) -> "Sunny in " + city.city())
				.description("The weather in a city")
				.inputType(City.class)
				.build(), FunctionToolCallback.builder("get_time", (City city) -> "Noon in " + city.city())
				.description("The time in a city")
				.inputType(City.class)
				.build())
			.build();

		ChatClientRequest resumed = before(gate, memory, confirming(tools, ToolConfirmation.deny("c1", "No.")), chain);

		ToolResponseMessage result = (ToolResponseMessage) resumed.prompt().getInstructions().get(2);
		assertThat(result.getResponses()).extracting(ToolResponseMessage.ToolResponse::id).containsExactly("c1", "c2");
		assertThat(result.getResponses().get(0).responseData()).isEqualTo("No.");
		assertThat(result.getResponses().get(1).responseData()).isEqualTo("\"Noon in Paris\"");
		// Both decisions are in the log, the implicit one as an allow
		assertThat(this.sessionService.getEvents(this.sessionId))
			.filteredOn(e -> e.getType() == EventType.TOOL_CONFIRMATION)
			.extracting(ToolConfirmationEvents::confirmation)
			.containsExactly(ToolConfirmation.deny("c1", "No."), ToolConfirmation.allow("c2"));
	}

	@Test
	void aSecondResumptionOfTheSameTurnIsRejectedBeforeTheToolRuns() {
		ToolConfirmationAdvisor gate = gate();
		SessionMemoryAdvisor memory = tracking();
		AdvisorChain chain = mock(AdvisorChain.class);
		before(gate, memory, buildRequest(this.sessionId, "Weather in Paris?"), chain);
		after(gate, memory, buildResponseFromMessages(this.sessionId, toolCall("c1")), chain);
		gate.before(confirming(withWeatherTool(), ToolConfirmation.allow("c1")), chain);

		assertThatThrownBy(() -> gate.before(confirming(withWeatherTool(), ToolConfirmation.allow("c1")), chain))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("RUNNING");
		assertThat(this.sessionService.getEvents(this.sessionId))
			.filteredOn(e -> e.getType() == EventType.TOOL_CONFIRMATION)
			.hasSize(1);
	}

	@Test
	void theRoundsThatFollowAResumptionPassThroughTheGate() {
		ToolConfirmationAdvisor gate = gate();
		SessionMemoryAdvisor memory = tracking();
		AdvisorChain chain = mock(AdvisorChain.class);
		before(gate, memory, buildRequest(this.sessionId, "Weather in Paris?"), chain);
		after(gate, memory, buildResponseFromMessages(this.sessionId, toolCall("c1")), chain);
		ChatClientRequest resumed = confirming(withWeatherTool(), ToolConfirmation.allow("c1"));
		before(gate, memory, resumed, chain);
		// The model asks for an ungated tool, the loop runs it and starts the next round
		// with a copy of the same context and the tool result in the prompt
		AssistantMessage readFile = AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall("c2", "function", "read_file", "{}")))
			.build();
		after(gate, memory, buildResponseFromMessages(this.sessionId, readFile), chain);
		ChatClientRequest nextRound = ChatClientRequest.builder()
			.prompt(new Prompt(List.of(toolResponse("c2")), withWeatherTool()))
			.context(resumed.context())
			.build();

		ChatClientRequest forwarded = before(gate, memory, nextRound, chain);

		assertThat(describe(forwarded.prompt().getInstructions())).containsExactly("Weather in Paris?", "call:c1",
				"result:c1", "call:c2", "result:c2");
		assertThat(this.sessionService.findById(this.sessionId).status()).isEqualTo(SessionStatus.RUNNING);
		assertThat(this.sessionService.getEvents(this.sessionId))
			.filteredOn(e -> e.getType() == EventType.TOOL_CONFIRMATION)
			.hasSize(1);
	}

	@Test
	void aStreamedReplyIsParkedOnItsAggregatedToolCalls() {
		ToolConfirmationAdvisor gate = gate();
		SessionMemoryAdvisor memory = tracking();
		StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
		memory.before(buildRequest(this.sessionId, "Weather in Paris?"), chain);
		memory.after(buildResponseFromMessages(this.sessionId, toolCall("c1")), chain);
		// The chunk with the tool call comes first; the finish reason rides on an empty one
		ChatClientResponse callChunk = buildResponseFromMessages(this.sessionId, toolCall("c1"));
		ChatClientResponse finishChunk = ChatClientResponse.builder()
			.chatResponse(ChatResponse.builder()
				.generations(List.of(new Generation(new AssistantMessage(""),
						ChatGenerationMetadata.builder().finishReason("tool_calls").build())))
				.build())
			.context(Map.of(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId))
			.build();
		given(chain.nextStream(any())).willReturn(Flux.just(callChunk, finishChunk));

		gate.adviseStream(buildRequest(this.sessionId, "Weather in Paris?"), chain).blockLast();

		Session session = this.sessionService.findById(this.sessionId);
		assertThat(session.status()).isEqualTo(SessionStatus.IDLE);
		assertThat(session.stopReason()).isEqualTo(StopReason.REQUIRES_ACTION);
	}

	@Test
	void aTurnInterruptedBeforeItCouldBeParkedKeepsTheReply() {
		ToolConfirmationAdvisor gate = gate();
		SessionMemoryAdvisor memory = tracking();
		AdvisorChain chain = mock(AdvisorChain.class);
		before(gate, memory, buildRequest(this.sessionId, "Weather in Paris?"), chain);
		this.sessionService.endTurn(this.sessionId, StopReason.INTERRUPTED);

		ChatClientResponse reply = after(gate, memory, buildResponseFromMessages(this.sessionId, toolCall("c1")), chain);

		assertThat(reply.chatResponse()).isNotNull();
		assertThat(this.sessionService.findById(this.sessionId).stopReason()).isEqualTo(StopReason.INTERRUPTED);
		// The pending call is in the log and can still be confirmed
		ChatClientRequest resumed = before(gate, memory, confirming(withWeatherTool(), ToolConfirmation.allow("c1")),
				chain);
		assertThat(describe(resumed.prompt().getInstructions())).containsExactly("Weather in Paris?", "call:c1",
				"result:c1");
	}

	@Test
	void parkingNeedsTheMemoryAdvisorsTurnTracking() {
		ToolConfirmationAdvisor gate = gate();
		SessionMemoryAdvisor untracked = SessionMemoryAdvisor.builder(this.sessionService).trackStatus(false).build();
		AdvisorChain chain = mock(AdvisorChain.class);
		before(gate, untracked, buildRequest(this.sessionId, "Weather in Paris?"), chain);

		assertThatThrownBy(() -> after(gate, untracked, buildResponseFromMessages(this.sessionId, toolCall("c1")), chain))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("trackStatus");
	}

	// --- The context key the core's tools share with the advisor ---

	@Test
	void theToolsAndTheAdvisorShareSpringAisConversationKey() {
		assertThat(SessionEventTools.SESSION_ID_CONTEXT_KEY).isEqualTo(ChatMemory.CONVERSATION_ID)
			.isEqualTo(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY);
	}

	// --- Agent snapshot ---

	@Test
	void theAdvisorStampsItsAgentOnTheSessionsItCreates() {
		SessionMemoryAdvisor advisor = SessionMemoryAdvisor.builder(this.sessionService)
			.agent(AgentSnapshot.of("support", "2", "claude-sonnet-5-5"))
			.build();
		AdvisorChain chain = mock(AdvisorChain.class);
		String fresh = "fresh-" + UUID.randomUUID();
		advisor.before(buildRequest(fresh, "Hi"), chain);
		assertThat(SessionAttributes.agent(this.sessionService.findById(fresh)))
			.isEqualTo(AgentSnapshot.of("support", "2", "claude-sonnet-5-5"));

		// Request metadata that names an agent replaces the snapshot as a whole, never a
		// mix of that name with the advisor's version and model; an existing session is left alone
		String overridden = "override-" + UUID.randomUUID();
		advisor.before(buildRequestWithMetadata(overridden, "Hi", Map.of(SessionAttributes.AGENT_NAME, "billing")),
				chain);
		assertThat(SessionAttributes.agent(this.sessionService.findById(overridden)))
			.isEqualTo(AgentSnapshot.of("billing"));
		advisor.before(buildRequest(this.sessionId, "Hi"), chain);
		assertThat(SessionAttributes.agent(this.sessionService.findById(this.sessionId))).isNull();
	}

	// --- Event ids on the context (#20) ---

	@Test
	void theAdvisorNamesTheEventsItStoredOnTheContext() {
		SessionMemoryAdvisor advisor = tracking();
		AdvisorChain chain = mock(AdvisorChain.class);

		ChatClientRequest forwarded = advisor.before(buildRequest(this.sessionId, "Hi"), chain);
		ChatClientResponse reply = advisor.after(buildResponse(this.sessionId, "Hello"), chain);

		List<SessionEvent> messages = this.sessionService.getEvents(this.sessionId, EventFilter.active());
		assertThat(forwarded.context()).containsEntry(SessionMemoryAdvisor.REQUEST_EVENT_ID_CONTEXT_KEY,
				messages.get(0).getId());
		assertThat(reply.context()).containsEntry(SessionMemoryAdvisor.RESPONSE_EVENT_IDS_CONTEXT_KEY,
				List.of(messages.get(1).getId()));
		// Nothing stored: the request key is present and null, the response list empty
		ChatClientRequest empty = advisor.before(ChatClientRequest.builder()
			.prompt(new Prompt(List.of()))
			.context(Map.of(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId))
			.build(), chain);
		assertThat(empty.context()).containsKey(SessionMemoryAdvisor.REQUEST_EVENT_ID_CONTEXT_KEY);
		assertThat(empty.context().get(SessionMemoryAdvisor.REQUEST_EVENT_ID_CONTEXT_KEY)).isNull();
		ChatClientResponse blank = advisor.after(buildResponse(this.sessionId, "   "), chain);
		assertThat(blank.context()).containsEntry(SessionMemoryAdvisor.RESPONSE_EVENT_IDS_CONTEXT_KEY, List.of());
	}

	@Test
	void theEventIdsAreSetWithoutTouchingAnUnmodifiableContext() {
		SessionMemoryAdvisor advisor = tracking();
		AdvisorChain chain = mock(AdvisorChain.class);
		ChatClientRequest frozen = new ChatClientRequest(new Prompt(List.of(new UserMessage("Hi"))),
				Map.of(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId));

		ChatClientRequest forwarded = advisor.before(frozen, chain);
		ChatClientResponse reply = advisor.after(new ChatClientResponse(
				ChatResponse.builder().generations(List.of(new Generation(new AssistantMessage("Hello")))).build(),
				Map.of(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId)), chain);

		assertThat(forwarded.context().get(SessionMemoryAdvisor.REQUEST_EVENT_ID_CONTEXT_KEY)).isNotNull();
		assertThat(reply.context().get(SessionMemoryAdvisor.RESPONSE_EVENT_IDS_CONTEXT_KEY)).asList().hasSize(1);
		assertThat(frozen.context()).doesNotContainKey(SessionMemoryAdvisor.REQUEST_EVENT_ID_CONTEXT_KEY);
	}

	// --- Usage and budget ---

	private static ChatClientResponse buildResponseWithUsage(String sessionId, String assistantText, int prompt,
			int completion) {
		ChatResponse chatResponse = ChatResponse.builder()
			.generations(List.of(new Generation(new AssistantMessage(assistantText))))
			.metadata(ChatResponseMetadata.builder().model("test-model").usage(new DefaultUsage(prompt, completion)).build())
			.build();
		return ChatClientResponse.builder()
			.chatResponse(chatResponse)
			.context(Map.of(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId))
			.build();
	}

	@Test
	void afterRecordsTheCallsUsageAndTheServiceSumsIt() {
		SessionMemoryAdvisor tracking = tracking();
		AdvisorChain chain = mock(AdvisorChain.class);
		tracking.before(buildRequest(this.sessionId, "Hi"), chain);
		tracking.after(buildResponseWithUsage(this.sessionId, "Hello", 100, 10), chain);
		tracking.before(buildRequest(this.sessionId, "More"), chain);
		tracking.after(buildResponseWithUsage(this.sessionId, "Sure", 200, 20), chain);
		// A reply without usage records nothing
		tracking.before(buildRequest(this.sessionId, "Again"), chain);
		tracking.after(buildResponse(this.sessionId, "Yes"), chain);

		List<SessionEvent> usage = this.sessionService.getEvents(this.sessionId,
				EventFilter.builder().eventTypes(Set.of(EventType.USAGE)).build());
		assertThat(usage).hasSize(2);
		assertThat(UsageEvents.usage(usage.get(0))).isEqualTo(new TokenUsage(100, 10, 110, 0, 0));
		assertThat(UsageEvents.model(usage.get(0))).isEqualTo("test-model");
		assertThat(this.sessionService.usage(this.sessionId)).isEqualTo(new TokenUsage(300, 30, 330, 0, 0));
		// The usage events sit between the reply and the turn's end, outside the conversation
		assertThat(this.sessionService.getEvents(this.sessionId)).extracting(SessionEvent::getType)
			.containsSubsequence(EventType.MESSAGE, EventType.USAGE, EventType.STATUS);
		assertThat(this.sessionService.getActiveMessages(this.sessionId)).hasSize(6);
	}

	@Test
	void aStreamedReplyRecordsTheAggregatedUsageWithoutCacheCounts() {
		SessionMemoryAdvisor tracking = tracking();
		StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
		ChatClientResponse textChunk = buildResponse(this.sessionId, "Hel");
		ChatClientResponse usageChunk = ChatClientResponse.builder()
			.chatResponse(ChatResponse.builder()
				.generations(List.of(new Generation(new AssistantMessage("lo"),
						ChatGenerationMetadata.builder().finishReason("stop").build())))
				.metadata(ChatResponseMetadata.builder()
					.model("test-model")
					.usage(new Usage() {
						@Override
						public Integer getPromptTokens() {
							return 100;
						}

						@Override
						public Integer getCompletionTokens() {
							return 10;
						}

						@Override
						public Object getNativeUsage() {
							return null;
						}

						@Override
						public Long getCacheReadInputTokens() {
							return 80L;
						}
					})
					.build())
				.build())
			.context(Map.of(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId))
			.build();
		given(chain.nextStream(any())).willReturn(Flux.just(textChunk, usageChunk));

		tracking.adviseStream(buildRequest(this.sessionId, "Hi"), chain).blockLast();

		List<SessionEvent> usage = this.sessionService.getEvents(this.sessionId,
				EventFilter.builder().eventTypes(Set.of(EventType.USAGE)).build());
		assertThat(usage).hasSize(1);
		// Spring AI's stream aggregator carries the three counts and the model, not the cache counts
		assertThat(UsageEvents.usage(usage.get(0))).isEqualTo(new TokenUsage(100, 10, 110, 0, 0));
		assertThat(UsageEvents.model(usage.get(0))).isEqualTo("test-model");
		assertThat(this.sessionService.findById(this.sessionId).stopReason()).isEqualTo(StopReason.END_TURN);
	}

	@Test
	void aRetriedAfterWithADeterministicGeneratorCountsTheUsageOnce() {
		SessionMemoryAdvisor idempotent = SessionMemoryAdvisor.builder(this.sessionService)
			.trackStatus(false)
			.responseEventIdGenerator(new IdempotentSessionEventIdGenerator())
			.build();
		AdvisorChain chain = mock(AdvisorChain.class);
		idempotent.before(buildRequest(this.sessionId, "Hi"), chain);
		ChatClientResponse reply = buildResponseWithUsage(this.sessionId, "Hello", 100, 10);

		idempotent.after(reply, chain);
		idempotent.after(reply, chain);

		assertThat(this.sessionService.usage(this.sessionId)).isEqualTo(new TokenUsage(100, 10, 110, 0, 0));
	}

	@Test
	void recordUsageFalseRecordsNothing() {
		SessionMemoryAdvisor quiet = SessionMemoryAdvisor.builder(this.sessionService).recordUsage(false).build();
		AdvisorChain chain = mock(AdvisorChain.class);
		quiet.before(buildRequest(this.sessionId, "Hi"), chain);
		quiet.after(buildResponseWithUsage(this.sessionId, "Hello", 100, 10), chain);

		assertThat(this.sessionService.usage(this.sessionId)).isEqualTo(TokenUsage.ZERO);
	}

	private BudgetAdvisor budget(long maxTotalTokens) {
		return BudgetAdvisor.builder(this.sessionService)
			.policy(BudgetPolicy.fixed(Budget.ofTotalTokens(maxTotalTokens)))
			.build();
	}

	@Test
	void aTurnIsRefusedOnceTheTreeHasReachedItsBudget() {
		SessionMemoryAdvisor memory = tracking();
		BudgetAdvisor budget = budget(150);
		AdvisorChain chain = mock(AdvisorChain.class);
		// The first call is under budget and completes, taking the usage to 110
		budget.before(memory.before(buildRequest(this.sessionId, "Hi"), chain), chain);
		memory.after(buildResponseWithUsage(this.sessionId, "Hello", 100, 10), chain);
		// Still under budget: the call that goes over it completes
		budget.before(memory.before(buildRequest(this.sessionId, "More"), chain), chain);
		memory.after(buildResponseWithUsage(this.sessionId, "Sure", 100, 10), chain);
		assertThat(this.sessionService.findById(this.sessionId).stopReason()).isEqualTo(StopReason.END_TURN);

		// The next call is refused and the turn the memory advisor started ends as BUDGET_REACHED
		ChatClientRequest started = memory.before(buildRequest(this.sessionId, "Again"), chain);
		assertThatThrownBy(() -> budget.before(started, chain)).isInstanceOf(BudgetReachedException.class)
			.hasMessageContaining("220 of 150")
			.satisfies(ex -> assertThat(((BudgetReachedException) ex).getBudget()).isEqualTo(Budget.ofTotalTokens(150)));
		Session session = this.sessionService.findById(this.sessionId);
		assertThat(session.status()).isEqualTo(SessionStatus.IDLE);
		assertThat(session.stopReason()).isEqualTo(StopReason.BUDGET_REACHED);
		// A higher budget lets it continue
		BudgetAdvisor raised = budget(1000);
		raised.before(memory.before(buildRequest(this.sessionId, "Again"), chain), chain);
		assertThat(this.sessionService.findById(this.sessionId).status()).isEqualTo(SessionStatus.RUNNING);
	}

	@Test
	void aChildsUsageCountsTowardsItsRootsBudget() {
		SessionMemoryAdvisor memory = tracking();
		BudgetAdvisor budget = budget(100);
		AdvisorChain chain = mock(AdvisorChain.class);
		Session child = this.sessionService.createChild(this.sessionId, "worker");
		this.sessionService.appendEvent(UsageEvents.of(child.id(), new TokenUsage(90, 10, 100, 0, 0), "m"));

		ChatClientRequest onRoot = memory.before(buildRequest(this.sessionId, "Hi"), chain);
		assertThatThrownBy(() -> budget.before(onRoot, chain)).isInstanceOf(BudgetReachedException.class);
		ChatClientRequest onChild = memory.before(buildRequest(child.id(), "Hi"), chain);
		assertThatThrownBy(() -> budget.before(onChild, chain)).isInstanceOf(BudgetReachedException.class);
		assertThat(this.sessionService.findById(child.id()).stopReason()).isEqualTo(StopReason.BUDGET_REACHED);
		// No budget for the tree: nothing is checked
		BudgetAdvisor unlimited = BudgetAdvisor.builder(this.sessionService).build();
		assertThat(unlimited.before(memory.before(buildRequest(child.id(), "Hi"), chain), chain)).isNotNull();
	}

	@Test
	void aStreamedCallIsRefusedWithTheSameException() {
		SessionMemoryAdvisor memory = tracking();
		BudgetAdvisor budget = budget(10);
		this.sessionService.appendEvent(UsageEvents.of(this.sessionId, new TokenUsage(9, 1, 10, 0, 0), "m"));
		StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
		ChatClientRequest started = memory.before(buildRequest(this.sessionId, "Hi"), chain);

		assertThatThrownBy(() -> budget.adviseStream(started, chain).blockLast())
			.isInstanceOf(BudgetReachedException.class);
		assertThat(this.sessionService.findById(this.sessionId).stopReason()).isEqualTo(StopReason.BUDGET_REACHED);
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
			// Some tests store system messages on purpose (session setup).
			return DefaultSessionService.builder()
				.sessionRepository(sessionRepository)
				.allowSystemMessages(true)
				.build();
		}

		@Bean
		SessionMemoryAdvisor sessionMemoryAdvisor(SessionService sessionService) {
			// The hook-level tests call before() and after() in isolation and out of order;
			// the turn status is exercised by the dedicated status tests below
			return SessionMemoryAdvisor.builder(sessionService).trackStatus(false).build();
		}

	}

}
