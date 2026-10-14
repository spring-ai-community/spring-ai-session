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
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.session.AgentSnapshot;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.EventType;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.SessionStatus;
import org.springframework.ai.session.SessionStatusEvents;
import org.springframework.ai.session.StopReason;
import org.springframework.ai.session.ToolConfirmation;
import org.springframework.ai.session.ToolConfirmationPolicy;
import org.springframework.ai.session.compaction.SlidingWindowCompactionStrategy;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.metadata.ToolMetadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.ai.session.advisor.ScriptedChatModel.describe;

/**
 * {@link SessionTurnAdvisor} around a real {@link ToolCallingAdvisor} loop and the
 * {@link SessionMemoryAdvisor}, driven through a {@link ChatClient} with a scripted model:
 * one turn per call, however the loop ends.
 *
 * @author Christian Tzolov
 */
class SessionTurnAdvisorTests {

	private final ScriptedChatModel model = new ScriptedChatModel();

	private final SessionService sessionService = DefaultSessionService.builder()
		.sessionRepository(InMemorySessionRepository.builder().build())
		.build();

	private final String sessionId = "s1";

	@Test
	void aCallRunsATurnAndRecordsItInTheStatusAndTheLog() {
		this.model.then(prompt -> {
			assertThat(status()).isEqualTo(SessionStatus.RUNNING);
			return answer("Hi!");
		});

		say(client(), "Hello");

		assertThat(session().status()).isEqualTo(SessionStatus.IDLE);
		assertThat(session().stopReason()).isEqualTo(StopReason.END_TURN);
		assertThat(statuses()).containsExactly(SessionStatus.RUNNING, SessionStatus.IDLE);
		assertThat(this.sessionService.getActiveMessages(this.sessionId)).extracting(m -> m.getText())
			.containsExactly("Hello", "Hi!");
	}

	@Test
	void aToolLoopRunsAsOneTurnUntilTheFinalReply() {
		this.model.thenCall("c1", "Lisbon").then(prompt -> {
			assertThat(status()).isEqualTo(SessionStatus.RUNNING);
			return answer("Sunny.");
		});

		say(client(), "Weather in Lisbon?");

		assertThat(session().stopReason()).isEqualTo(StopReason.END_TURN);
		assertThat(statuses()).containsExactly(SessionStatus.RUNNING, SessionStatus.IDLE);
	}

	@Test
	void aReturnDirectToolEndsTheTurn() {
		this.model.thenCall("c1", "Lisbon").thenAnswer("Hello again.");
		ToolCallback direct = FunctionToolCallback.builder("get_weather", (City city) -> "Sunny in " + city.city())
			.description("The weather in a city")
			.inputType(City.class)
			.toolMetadata(ToolMetadata.builder().returnDirect(true).build())
			.build();
		ChatClient client = client(direct, ToolCallingAdvisor.builder().build());

		assertThat(say(client, "Weather in Lisbon?")).contains("Sunny in Lisbon");

		assertThat(session().status()).isEqualTo(SessionStatus.IDLE);
		assertThat(session().stopReason()).isEqualTo(StopReason.END_TURN);
		// The next call starts its own turn
		assertThat(say(client, "Hi")).isEqualTo("Hello again.");
	}

	@Test
	void aBreachedToolCallLimitEndsTheTurn() {
		this.model.thenCall("c1", "Lisbon").thenCall("c2", "Porto").thenAnswer("Hello again.");
		ToolCallingAdvisor limited = ToolCallingAdvisor.builder()
			.toolCallingManager(ToolCallingManager.builder().maxTotalToolCalls(1).build())
			.build();
		ChatClient client = client(weather(), limited);

		say(client, "Weather in Lisbon and Porto?");

		assertThat(this.model.prompts()).hasSize(2);
		assertThat(session().status()).isEqualTo(SessionStatus.IDLE);
		assertThat(session().stopReason()).isEqualTo(StopReason.END_TURN);
		assertThat(say(client, "Hi")).isEqualTo("Hello again.");
	}

	@Test
	void aFailedCallEndsTheTurnWithAnError() {
		this.model.then(prompt -> {
			throw new IllegalStateException("model down");
		}).thenAnswer("Back.");
		ChatClient client = client();

		assertThatThrownBy(() -> say(client, "Hello")).hasMessageContaining("model down");

		assertThat(session().status()).isEqualTo(SessionStatus.IDLE);
		assertThat(session().stopReason()).isEqualTo(StopReason.ERROR);
		assertThat(say(client, "Again")).isEqualTo("Back.");
	}

	@Test
	void aFailureInsideTheMemoryAdvisorEndsTheTurnWithAnError() {
		ChatClient client = client();

		assertThatThrownBy(() -> client.prompt()
			.user("Hello")
			.advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId)
				.param(SessionMemoryAdvisor.EVENT_FILTER_CONTEXT_KEY, "not a filter"))
			.call()
			.content()).isInstanceOf(IllegalArgumentException.class);

		assertThat(session().stopReason()).isEqualTo(StopReason.ERROR);
	}

	@Test
	void aSecondConcurrentTurnIsRejectedAndTheRunningOneLeftAlone() {
		ChatClient client = client();
		say(client, "Hello", "Hi!");
		this.sessionService.startTurn(this.sessionId);

		assertThatThrownBy(() -> say(client, "Hello again")).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("could not start a turn: it is RUNNING");

		assertThat(status()).isEqualTo(SessionStatus.RUNNING);
		assertThat(this.model.prompts()).hasSize(1);
	}

	@Test
	void aStreamedCallRunsATurn() {
		this.model.thenAnswer("Hi!");

		List<ChatResponse> chunks = client().prompt()
			.user("Hello")
			.advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId))
			.stream()
			.chatResponse()
			.collectList()
			.block();

		assertThat(chunks).isNotEmpty();
		assertThat(session().stopReason()).isEqualTo(StopReason.END_TURN);
		assertThat(this.sessionService.getActiveMessages(this.sessionId)).hasSize(2);
	}

	@Test
	void aFailedStreamEndsTheTurnWithAnError() {
		this.model.then(prompt -> {
			throw new IllegalStateException("stream down");
		});

		assertThatThrownBy(() -> client().prompt()
			.user("Hello")
			.advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId))
			.stream()
			.content()
			.blockLast()).hasMessageContaining("stream down");

		assertThat(session().status()).isEqualTo(SessionStatus.IDLE);
		assertThat(session().stopReason()).isEqualTo(StopReason.ERROR);
	}

	@Test
	void compactionRunsOnceTheTurnHasEnded() {
		AtomicInteger triggered = new AtomicInteger();
		SessionTurnAdvisor turn = SessionTurnAdvisor.builder(this.sessionService)
			.compactionTrigger(request -> triggered.incrementAndGet() > 0)
			.compactionStrategy(SlidingWindowCompactionStrategy.builder().maxEvents(2).build())
			.build();
		ChatClient client = client(weather(), turn, ToolCallingAdvisor.builder().build());
		say(client, "Hello", "Hi!");
		this.model.thenCall("c1", "Lisbon").thenAnswer("Sunny.");

		say(client, "Weather in Lisbon?");

		// Once per turn, not once per round of the tool loop
		assertThat(triggered).hasValue(2);
		// The window keeps the newest turn whole: the first one is compacted away
		assertThat(describe(new Prompt(this.sessionService.getActiveMessages(this.sessionId))))
			.containsExactly("Weather in Lisbon?", "call:c1", "result:c1", "Sunny.");
	}

	@Test
	void aFailedCompactionDoesNotFailTheCall() {
		SessionTurnAdvisor turn = SessionTurnAdvisor.builder(this.sessionService)
			.compactionTrigger(request -> true)
			.compactionStrategy(request -> {
				throw new IllegalStateException("summarizer unavailable");
			})
			.build();

		assertThat(say(client(weather(), turn, ToolCallingAdvisor.builder().build()), "Hello", "Hi!"))
			.isEqualTo("Hi!");
		assertThat(session().stopReason()).isEqualTo(StopReason.END_TURN);
		assertThat(this.sessionService.getActiveMessages(this.sessionId)).hasSize(2);
	}

	@Test
	void theCompactionOptionsAreSetTogether() {
		assertThatThrownBy(() -> SessionTurnAdvisor.builder(this.sessionService)
			.compactionStrategy(SlidingWindowCompactionStrategy.builder().build())
			.build()).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void aGatedCallParksTheTurnAndTheConfirmationResumesAndEndsIt() {
		ToolConfirmationPolicy policy = ToolConfirmationPolicy.forTools("get_weather");
		ChatClient client = gated(policy);
		this.model.thenCall("c1", "Paris").thenAnswer("Sunny in Paris.");

		say(client, "Weather in Paris?");

		assertThat(session().status()).isEqualTo(SessionStatus.IDLE);
		assertThat(session().stopReason()).isEqualTo(StopReason.REQUIRES_ACTION);

		String answer = confirm(client, ToolConfirmation.allow("c1"));

		assertThat(answer).isEqualTo("Sunny in Paris.");
		assertThat(session().stopReason()).isEqualTo(StopReason.END_TURN);
		assertThat(statuses()).containsExactly(SessionStatus.RUNNING, SessionStatus.IDLE, SessionStatus.RUNNING,
				SessionStatus.IDLE);
		assertThat(describe(this.model.prompts().get(1))).containsExactly("Weather in Paris?", "call:c1", "result:c1");
		assertThat(this.model.prompts().get(1).getInstructions().get(2)).isInstanceOf(ToolResponseMessage.class);
	}

	@Test
	void aRejectedResumptionLeavesTheTurnThatRunsAlone() {
		ToolConfirmationPolicy policy = ToolConfirmationPolicy.forTools("get_weather");
		ChatClient client = gated(policy);
		this.model.thenCall("c1", "Paris");
		say(client, "Weather in Paris?");
		// Another node resumed the turn first
		this.sessionService.startTurn(this.sessionId);

		assertThatThrownBy(() -> confirm(client, ToolConfirmation.allow("c1"))).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("RUNNING");

		assertThat(status()).isEqualTo(SessionStatus.RUNNING);
	}

	@Test
	void theTurnAdvisorStampsItsAgentOnTheSessionsItCreates() {
		SessionTurnAdvisor turn = SessionTurnAdvisor.builder(this.sessionService)
			.agent(AgentSnapshot.of("support", "2", "claude-sonnet-5-5"))
			.build();

		say(client(weather(), turn, ToolCallingAdvisor.builder().build()), "Hello", "Hi!");

		assertThat(SessionAttributes.agent(session())).isEqualTo(AgentSnapshot.of("support", "2", "claude-sonnet-5-5"));
	}

	private ChatClient client() {
		return client(weather(), ToolCallingAdvisor.builder().build());
	}

	private ChatClient client(ToolCallback tool, ToolCallingAdvisor toolCalling) {
		return client(tool, SessionTurnAdvisor.builder(this.sessionService).build(), toolCalling);
	}

	private ChatClient client(ToolCallback tool, Advisor... advisors) {
		return ChatClient.builder(this.model)
			.defaultAdvisors(advisors)
			.defaultAdvisors(SessionMemoryAdvisor.builder(this.sessionService).build())
			.defaultToolCallbacks(tool)
			.build();
	}

	private ChatClient gated(ToolConfirmationPolicy policy) {
		return client(weather(), SessionTurnAdvisor.builder(this.sessionService).build(),
				ToolCallingAdvisor.builder().toolExecutionEligibilityChecker(policy.eligibilityChecker()).build(),
				ToolConfirmationAdvisor.builder(this.sessionService).policy(policy).build());
	}

	private String say(ChatClient client, String text) {
		return client.prompt()
			.user(text)
			.advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId))
			.call()
			.content();
	}

	private String say(ChatClient client, String text, String reply) {
		this.model.thenAnswer(reply);
		return say(client, text);
	}

	private String confirm(ChatClient client, ToolConfirmation decision) {
		return client.prompt()
			.advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId)
				.param(ToolConfirmationAdvisor.TOOL_CONFIRMATION_CONTEXT_KEY, decision))
			.call()
			.content();
	}

	private static ToolCallback weather() {
		return FunctionToolCallback.builder("get_weather", (City city) -> "Sunny in " + city.city())
			.description("The weather in a city")
			.inputType(City.class)
			.build();
	}

	private static ChatResponse answer(String text) {
		return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
	}

	private Session session() {
		return this.sessionService.findById(this.sessionId);
	}

	private SessionStatus status() {
		return session().status();
	}

	private List<SessionStatus> statuses() {
		return this.sessionService
			.getEvents(this.sessionId, EventFilter.builder().eventTypes(Set.of(EventType.STATUS)).build())
			.stream()
			.map(SessionStatusEvents::status)
			.toList();
	}

	record City(String city) {
	}

}
