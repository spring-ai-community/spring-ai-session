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
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallDecision;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.metadata.ToolMetadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.ai.session.advisor.ScriptedChatModel.describe;

/**
 * {@link RunScopedSessionEventIdGenerator}, the memory advisor's default, through a
 * {@link ChatClient} with the session advisors.
 *
 * @author Christian Tzolov
 */
class RunScopedSessionEventIdGeneratorTests {

	private final ScriptedChatModel model = new ScriptedChatModel();

	private final SessionService sessionService = DefaultSessionService.builder()
		.sessionRepository(InMemorySessionRepository.builder().build())
		.build();

	private final String sessionId = "s1";

	@Test
	void aRetryOfTheSameRunStoresNothingTwice() {
		ChatClient client = client(weather(ToolMetadata.builder().build()));
		this.model.thenCall("c1", "Paris").thenAnswer("Sunny in Paris.");
		ask(client, "Weather in Paris?", "run-1");
		List<String> stored = messageIds();

		// The same call again, as a durability layer would replay it
		this.model.thenCall("c1", "Paris").thenAnswer("Sunny in Paris.");
		ask(client, "Weather in Paris?", "run-1");

		assertThat(messageIds()).isEqualTo(stored).hasSize(4);
	}

	@Test
	void theSameTextInTwoRunsIsStoredTwice() {
		ChatClient client = client(weather(ToolMetadata.builder().build()));
		this.model.thenAnswer("OK.").thenAnswer("OK.");

		ask(client, "yes", "run-1");
		ask(client, "yes", "run-2");

		assertThat(describe(new Prompt(this.sessionService.getActiveMessages(this.sessionId)))).containsExactly("yes",
				"OK.", "yes", "OK.");
	}

	@Test
	void identicalToolCallsWithoutIdsInOneTurnAreBothStored() {
		ChatClient client = client(weather(ToolMetadata.builder().build()));
		this.model.then(prompt -> reply(ScriptedChatModel.toolCall("", "Paris")))
			.then(prompt -> reply(ScriptedChatModel.toolCall("", "Paris")))
			.thenAnswer("Still sunny.");

		ask(client, "Weather in Paris, twice?", "run-1");

		assertThat(describe(new Prompt(this.sessionService.getActiveMessages(this.sessionId))))
			.containsExactly("Weather in Paris, twice?", "call:", "result:", "call:", "result:", "Still sunny.");
	}

	@Test
	void withoutARunIdTheIdsAreRandom() {
		ChatClient client = client(weather(ToolMetadata.builder().build()));
		this.model.thenAnswer("OK.").thenAnswer("OK.");

		client.prompt().user("yes").advisors(SessionAdvisors.session(this.sessionId)).call().content();
		client.prompt().user("yes").advisors(SessionAdvisors.session(this.sessionId)).call().content();

		assertThat(messageIds()).hasSize(4).doesNotHaveDuplicates();
	}

	@Test
	void aResumptionUnderTheRunIdOfThePausedCallIsStored() {
		ChatClient client = client(weather(ToolMetadata.builder().requiresConfirmation(true).build()));
		this.model.thenCall("c1", "Paris").thenAnswer("Sunny in Paris.");
		ask(client, "Weather in Paris?", "run-1");

		String answer = client.prompt()
			.advisors(SessionAdvisors.session(this.sessionId, "run-1")
				.andThen(SessionAdvisors.decide(this.sessionId, ToolCallDecision.allow("c1"))))
			.call()
			.content();

		assertThat(answer).isEqualTo("Sunny in Paris.");
		assertThat(describe(new Prompt(this.sessionService.getActiveMessages(this.sessionId))))
			.containsExactly("Weather in Paris?", "call:c1", "result:c1", "Sunny in Paris.");
	}

	@Test
	void theGenerationsOfAReplyGetTheirOwnIds() {
		RunScopedSessionEventIdGenerator generator = new RunScopedSessionEventIdGenerator();
		AssistantMessage first = new AssistantMessage("One.");
		AssistantMessage second = new AssistantMessage("One.");
		ChatClientResponse response = ChatClientResponse.builder()
			.chatResponse(ChatResponse.builder().generations(List.of(new Generation(first), new Generation(second))).build())
			.context(Map.of(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId,
					SessionTurnAdvisor.RUN_ID_CONTEXT_KEY, "run-1", SessionTurnAdvisor.PHASE_CONTEXT_KEY, "start",
					SessionTurnAdvisor.ROUND_CONTEXT_KEY, new AtomicInteger(1)))
			.build();

		String firstId = generator.generate(response, first);

		assertThat(generator.generate(response, second)).isNotEqualTo(firstId);
		assertThat(generator.generate(response, first)).isEqualTo(firstId).startsWith("assistant-").hasSize(74);
	}

	private ChatClient client(ToolCallback tool) {
		return ChatClient.builder(this.model)
			.defaultAdvisors(SessionAdvisors.builder(this.sessionService).build())
			.defaultToolCallbacks(tool)
			.build();
	}

	private String ask(ChatClient client, String text, String runId) {
		return client.prompt().user(text).advisors(SessionAdvisors.session(this.sessionId, runId)).call().content();
	}

	private List<String> messageIds() {
		return this.sessionService.getEvents(this.sessionId, EventFilter.all())
			.stream()
			.filter(SessionEvent::hasMessage)
			.map(SessionEvent::getId)
			.toList();
	}

	private static ChatResponse reply(Message message) {
		return new ChatResponse(List.of(new Generation((AssistantMessage) message)));
	}

	private static ToolCallback weather(ToolMetadata metadata) {
		return FunctionToolCallback.builder("get_weather", (City city) -> "Sunny in " + city.city())
			.description("The weather in a city")
			.inputType(City.class)
			.toolMetadata(metadata)
			.build();
	}

	record City(String city) {
	}

}
