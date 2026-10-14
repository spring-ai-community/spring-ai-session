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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallDecision;
import org.springframework.ai.session.Budget;
import org.springframework.ai.session.BudgetPolicy;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.EventType;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.StopReason;
import org.springframework.ai.session.compaction.SlidingWindowCompactionStrategy;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.metadata.ToolMetadata;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SessionAdvisors} with a {@link ChatClient} that registers its own tool-calling
 * loop.
 *
 * @author Christian Tzolov
 */
class SessionAdvisorsTests {

	private final ScriptedChatModel model = new ScriptedChatModel();

	private final SessionService sessionService = DefaultSessionService.builder()
		.sessionRepository(InMemorySessionRepository.builder().build())
		.build();

	private final String sessionId = "s1";

	@Test
	void theTurnAndTheMemoryAdvisorsAndABudgetOnlyWithAPolicy() {
		assertThat(SessionAdvisors.builder(this.sessionService).build()).hasExactlyElementsOfTypes(
				SessionTurnAdvisor.class, SessionMemoryAdvisor.class);

		List<Advisor> advisors = SessionAdvisors.builder(this.sessionService)
			.budgetPolicy(BudgetPolicy.fixed(Budget.ofTotalTokens(1000)))
			.build();

		assertThat(advisors).hasExactlyElementsOfTypes(SessionTurnAdvisor.class, SessionMemoryAdvisor.class,
				BudgetAdvisor.class);
		assertThat(advisors).extracting(Advisor::getOrder).isSorted();
	}

	@Test
	void aCallRunsATurnWithTheHistory() {
		ChatClient client = client(SessionAdvisors.builder(this.sessionService).build());
		this.model.thenAnswer("Hi!").thenAnswer("Fine.");

		say(client, "Hello");
		say(client, "How are you?");

		assertThat(this.sessionService.findById(this.sessionId).stopReason()).isEqualTo(StopReason.END_TURN);
		assertThat(ScriptedChatModel.describe(this.model.prompts().get(1))).containsExactly("Hello", "Hi!",
				"How are you?");
	}

	@Test
	void aGatedCallParksAndADecisionResumesIt() {
		ChatClient client = client(SessionAdvisors.builder(this.sessionService).build(), gatedWeather());
		this.model.thenCall("c1", "Paris").thenAnswer("Sunny in Paris.");

		say(client, "Weather in Paris?");
		assertThat(this.sessionService.findById(this.sessionId).stopReason()).isEqualTo(StopReason.REQUIRES_ACTION);

		String answer = client.prompt()
			.advisors(SessionAdvisors.decide(this.sessionId, ToolCallDecision.allow("c1")))
			.call()
			.content();

		assertThat(answer).isEqualTo("Sunny in Paris.");
		assertThat(this.sessionService.findById(this.sessionId).stopReason()).isEqualTo(StopReason.END_TURN);
	}

	@Test
	void theMemoryCustomizerConfiguresTheMemoryAdvisor() {
		ChatClient client = client(
				SessionAdvisors.builder(this.sessionService).memory(memory -> memory.recordUsage(false)).build());
		this.model.thenAnswer("Hi!");

		say(client, "Hello");

		assertThat(this.sessionService
			.getEvents(this.sessionId, EventFilter.builder().eventTypes(Set.of(EventType.USAGE)).build())).isEmpty();
		assertThat(this.sessionService.getActiveMessages(this.sessionId)).hasSize(2);
	}

	@Test
	void theCompactionAndTheMemoryCustomizersReachTheAdvisors() {
		AtomicInteger customized = new AtomicInteger();
		ChatClient client = client(SessionAdvisors.builder(this.sessionService)
			.compaction(request -> true, SlidingWindowCompactionStrategy.builder().maxEvents(2).build())
			.memory(memory -> customized.incrementAndGet())
			.memory(memory -> memory.recordUsage(false))
			.build());
		this.model.thenAnswer("Hi!").thenAnswer("Fine.");

		say(client, "Hello");
		say(client, "How are you?");

		// Both customizers ran; the window keeps the newest turn
		assertThat(customized).hasValue(1);
		assertThat(this.sessionService
			.getEvents(this.sessionId, EventFilter.builder().eventTypes(Set.of(EventType.USAGE)).build())).isEmpty();
		assertThat(ScriptedChatModel.describe(new Prompt(this.sessionService.getActiveMessages(this.sessionId))))
			.containsExactly("How are you?", "Fine.");
	}

	@Test
	void asyncCompactionReachesTurnAdvisor() throws Exception {
		SessionTurnAdvisorTests.HeldCompaction held = new SessionTurnAdvisorTests.HeldCompaction();
		List<Advisor> advisors = SessionAdvisors.builder(this.sessionService)
			.compaction(request -> true, held)
			.asyncCompaction(true)
			.build();
		SessionTurnAdvisor turn = (SessionTurnAdvisor) advisors.get(0);
		this.model.thenAnswer("Hi!");

		// The reply comes back while the compaction is held
		say(client(advisors), "Hello");
		assertThat(held.started.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(turn.isCompacting(this.sessionId)).isTrue();
		held.release.countDown();
		for (int i = 0; i < 100 && turn.isCompacting(this.sessionId); i++) {
			Thread.sleep(50);
		}
		assertThat(turn.isCompacting(this.sessionId)).isFalse();
	}

	private ChatClient client(List<Advisor> advisors, ToolCallback... tools) {
		return ChatClient.builder(this.model).defaultAdvisors(advisors).defaultToolCallbacks(tools).build();
	}

	private String say(ChatClient client, String text) {
		return client.prompt().user(text).advisors(SessionAdvisors.session(this.sessionId)).call().content();
	}

	private static ToolCallback gatedWeather() {
		return FunctionToolCallback.builder("get_weather", (City city) -> "Sunny in " + city.city())
			.description("The weather in a city")
			.inputType(City.class)
			.toolMetadata(ToolMetadata.builder().requiresConfirmation(true).build())
			.build();
	}

	record City(String city) {
	}

}
