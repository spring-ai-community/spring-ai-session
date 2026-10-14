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

package org.springframework.ai.session.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import reactor.core.publisher.Flux;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/**
 * A chat model replying from a script, after the one of spring-ai-agent's tests, with a
 * fixed usage per call.
 */
class ScriptedChatModel implements ChatModel {

	private final Deque<Supplier<AssistantMessage>> replies = new ArrayDeque<>();

	private final List<Prompt> prompts = new ArrayList<>();

	private final int promptTokens;

	private final int completionTokens;

	ScriptedChatModel() {
		this(0, 0);
	}

	ScriptedChatModel(int promptTokens, int completionTokens) {
		this.promptTokens = promptTokens;
		this.completionTokens = completionTokens;
	}

	ScriptedChatModel reply(String text) {
		this.replies.add(() -> new AssistantMessage(text));
		return this;
	}

	ScriptedChatModel replyWithToolCall(String toolName, String arguments) {
		ToolCall toolCall = new ToolCall("call-" + (this.replies.size() + 1), "function", toolName, arguments);
		AssistantMessage reply = AssistantMessage.builder().content("").properties(Map.of()).toolCalls(List.of(toolCall)).build();
		this.replies.add(() -> reply);
		return this;
	}

	@Override
	public ChatResponse call(Prompt prompt) {
		this.prompts.add(prompt);
		Supplier<AssistantMessage> reply = this.replies.poll();
		if (reply == null) {
			throw new IllegalStateException("No more scripted replies, received " + prompt);
		}
		ChatResponse.Builder response = ChatResponse.builder().generations(List.of(new Generation(reply.get())));
		if (this.promptTokens > 0 || this.completionTokens > 0) {
			response.metadata(ChatResponseMetadata.builder()
				.model("scripted")
				.usage(new DefaultUsage(this.promptTokens, this.completionTokens))
				.build());
		}
		return response.build();
	}

	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {
		return Flux.defer(() -> Flux.just(call(prompt)));
	}

	@Override
	public ChatOptions getOptions() {
		return ToolCallingChatOptions.builder().build();
	}

	Prompt lastPrompt() {
		return this.prompts.get(this.prompts.size() - 1);
	}

}
