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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.tool.function.FunctionToolCallback;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SessionMemoryAdvisor} inside a real {@link ToolCallingAdvisor} loop, driven
 * through a {@link ChatClient} with a scripted model: what each round sends to the model.
 *
 * @author Christian Tzolov
 */
class SessionMemoryAdvisorToolLoopTests {

	private final ScriptedModel model = new ScriptedModel();

	private final SessionService sessionService = DefaultSessionService.builder()
		.sessionRepository(InMemorySessionRepository.builder().build())
		.build();

	private final String sessionId = "s1";

	@Test
	void aSecondTurnWithAToolLoopSendsEachMessageOnce() {
		this.model.thenCall("c1", "Lisbon").thenAnswer("Lisbon: sunny.").thenCall("c2", "Porto").thenAnswer("Porto: rain.");
		ChatClient client = client(ToolCallingAdvisor.builder().build());

		say(client, "Weather in Lisbon?");
		say(client, "And Porto?");

		assertThat(this.model.prompts()).hasSize(4).allSatisfy(SessionMemoryAdvisorToolLoopTests::assertNoDuplicates);
		assertThat(describe(this.model.prompts().get(3))).containsExactly("Weather in Lisbon?", "call:c1",
				"result:c1", "Lisbon: sunny.", "And Porto?", "call:c2", "result:c2");
	}

	@Test
	void aToolLoopWithoutItsOwnHistorySendsTheSameConversation() {
		this.model.thenCall("c1", "Lisbon").thenAnswer("Lisbon: sunny.").thenCall("c2", "Porto").thenAnswer("Porto: rain.");
		ChatClient client = client(ToolCallingAdvisor.builder().conversationHistoryEnabled(false).build());

		say(client, "Weather in Lisbon?");
		say(client, "And Porto?");

		assertThat(this.model.prompts()).hasSize(4).allSatisfy(SessionMemoryAdvisorToolLoopTests::assertNoDuplicates);
		// The loop adds the request's system message to each later round, a blank one when
		// the request has none
		assertThat(describe(this.model.prompts().get(3))).containsExactly("", "Weather in Lisbon?", "call:c1",
				"result:c1", "Lisbon: sunny.", "And Porto?", "call:c2", "result:c2");
	}

	private ChatClient client(ToolCallingAdvisor toolCalling) {
		return ChatClient.builder(this.model)
			.defaultAdvisors(toolCalling, SessionMemoryAdvisor.builder(this.sessionService).build())
			.defaultToolCallbacks(FunctionToolCallback.builder("get_weather", (City city) -> "Sunny in " + city.city())
				.description("The weather in a city")
				.inputType(City.class)
				.build())
			.build();
	}

	private String say(ChatClient client, String text) {
		return client.prompt()
			.user(text)
			.advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId))
			.call()
			.content();
	}

	/** The text, "call:id" for an assistant tool call, "result:id" for a tool result. */
	private static List<String> describe(Prompt prompt) {
		return prompt.getInstructions().stream().map(m -> {
			if (m instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
				return "call:" + assistant.getToolCalls().get(0).id();
			}
			if (m instanceof ToolResponseMessage responses) {
				return "result:" + responses.getResponses().get(0).id();
			}
			return m.getText();
		}).toList();
	}

	private static void assertNoDuplicates(Prompt prompt) {
		assertThat(describe(prompt)).doesNotHaveDuplicates();
	}

	record City(String city) {
	}

	/** Answers each prompt with the next scripted reply, and keeps the prompts it got. */
	static class ScriptedModel implements ChatModel {

		private final Deque<Function<Prompt, ChatResponse>> replies = new ArrayDeque<>();

		private final List<Prompt> prompts = new ArrayList<>();

		ScriptedModel thenCall(String id, String city) {
			this.replies.add(prompt -> new ChatResponse(List.of(new Generation(AssistantMessage.builder()
				.content("")
				.toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", "get_weather",
						"{\"city\":\"" + city + "\"}")))
				.build()))));
			return this;
		}

		ScriptedModel thenAnswer(String text) {
			this.replies.add(prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
			return this;
		}

		@Override
		public ChatResponse call(Prompt prompt) {
			this.prompts.add(prompt);
			Function<Prompt, ChatResponse> reply = this.replies.poll();
			if (reply == null) {
				throw new IllegalStateException("No scripted reply left for prompt " + this.prompts.size());
			}
			return reply.apply(prompt);
		}

		List<Prompt> prompts() {
			return this.prompts;
		}

		@Override
		public ChatOptions getDefaultOptions() {
			return ToolCallingChatOptions.builder().build();
		}

		@Override
		public ChatOptions getOptions() {
			return ToolCallingChatOptions.builder().build();
		}

	}

}
