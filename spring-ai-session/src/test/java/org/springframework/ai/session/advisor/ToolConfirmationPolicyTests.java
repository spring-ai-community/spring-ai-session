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

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ToolConfirmationPolicy}.
 *
 * @author Christian Tzolov
 */
class ToolConfirmationPolicyTests {

	private static ChatResponse reply(AssistantMessage message) {
		return ChatResponse.builder().generations(List.of(new Generation(message))).build();
	}

	private static AssistantMessage calling(String... tools) {
		List<AssistantMessage.ToolCall> calls = java.util.Arrays.stream(tools)
			.map(tool -> new AssistantMessage.ToolCall("id-" + tool, "function", tool, "{}"))
			.toList();
		return AssistantMessage.builder().toolCalls(calls).build();
	}

	@Test
	void forToolsConfirmsTheNamedToolsOnlyAndTheCheckerStopsTheLoopForThem() {
		ToolConfirmationPolicy policy = ToolConfirmationPolicy.forTools("bash", "bash");

		assertThat(policy.requiresConfirmation(new AssistantMessage.ToolCall("1", "function", "bash", "{}"))).isTrue();
		assertThat(policy.requiresConfirmation(new AssistantMessage.ToolCall("2", "function", "read", "{}"))).isFalse();
		assertThat(policy.requiresConfirmation(reply(calling("read", "bash")))).isTrue();
		assertThat(policy.requiresConfirmation(reply(calling("read")))).isFalse();
		assertThat(policy.requiresConfirmation(reply(new AssistantMessage("plain text")))).isFalse();

		// The loop runs a reply's tool calls only when none needs confirmation
		assertThat(policy.eligibilityChecker().isToolCallResponse(reply(calling("read")))).isTrue();
		assertThat(policy.eligibilityChecker().isToolCallResponse(reply(calling("read", "bash")))).isFalse();
		assertThat(policy.eligibilityChecker().isToolCallResponse(reply(new AssistantMessage("plain text")))).isFalse();
		assertThat(policy.eligibilityChecker().isToolCallResponse(null)).isFalse();
		assertThat(ToolConfirmationPolicy.none().eligibilityChecker().isToolCallResponse(reply(calling("bash")))).isTrue();
	}

}
