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

package org.springframework.ai.session;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.model.tool.ToolCallDecision;
import org.springframework.ai.model.tool.ToolCallDecisions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Tests for {@link ToolConfirmations}: the question a turn parks on and its answer.
 *
 * @author Christian Tzolov
 */
class ToolConfirmationsTests {

	private static final AssistantMessage REPLY = AssistantMessage.builder()
		.content("")
		.toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "book_flight", "{\"flight\":\"VX205\"}"),
				new AssistantMessage.ToolCall("c2", "function", "weather", "{}")))
		.build();

	@Test
	void theQuestionHoldsEveryCallOfTheReplyAndTheIdsThatWait() {
		Interaction question = ToolConfirmations.question(REPLY, List.of("c1"));

		assertThat(question.kind()).isEqualTo(Interaction.KIND_TOOL_CONFIRMATION);
		assertThat(ToolConfirmations.reply(question).getToolCalls()).isEqualTo(REPLY.getToolCalls());
		assertThat(ToolConfirmations.awaiting(question)).containsExactly("c1");
	}

	@Test
	void aCallThatDoesNotWaitIsAllowed() {
		Interaction question = ToolConfirmations.question(REPLY, List.of("c1"));

		ToolCallDecisions decisions = ToolConfirmations.complete(question,
				List.of(ToolCallDecision.deny("c1", "Too expensive.")));

		assertThat(decisions.decisions()).containsExactly(ToolCallDecision.deny("c1", "Too expensive."),
				ToolCallDecision.allow("c2"));
	}

	@Test
	void everyCallThatWaitsNeedsADecision() {
		Interaction question = ToolConfirmations.question(REPLY, List.of("c1", "c2"));

		assertThatIllegalArgumentException()
			.isThrownBy(() -> ToolConfirmations.complete(question, List.of(ToolCallDecision.allow("c1"))))
			.withMessageContaining("missing: [c2]");
	}

	@Test
	void aDecisionMustBeAboutACallOfTheReply() {
		Interaction question = ToolConfirmations.question(REPLY, List.of("c1"));

		assertThatIllegalArgumentException()
			.isThrownBy(() -> ToolConfirmations.complete(question,
					List.of(ToolCallDecision.allow("c1"), ToolCallDecision.allow("c9"))))
			.withMessageContaining("c9");
	}

	@Test
	void callsWithoutIdsAreDecidedOnByTheirPosition() {
		AssistantMessage withoutIds = AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall("", "function", "book_flight", "{}"),
					new AssistantMessage.ToolCall("", "function", "weather", "{}")))
			.build();
		Interaction question = ToolConfirmations.question(withoutIds, List.of("#0"));

		ToolCallDecisions decisions = ToolConfirmations.complete(question, List.of(ToolCallDecision.deny("#0")));

		assertThat(decisions.decisions()).containsExactly(ToolCallDecision.deny("#0"), ToolCallDecision.allow("#1"));
	}

	@Test
	void decisionsRoundTripThroughAnAnswer() {
		ToolCallDecisions decisions = ToolCallDecisions.of(ToolCallDecision.allow("c1"),
				ToolCallDecision.deny("c2", "Not on Fridays."), ToolCallDecision.deny("c3"));

		List<Map<String, Object>> answer = ToolConfirmations.asAnswer(decisions);

		assertThat(answer.get(0)).containsExactly(Map.entry("toolCallId", "c1"), Map.entry("allowed", true));
		assertThat(answer.get(1)).containsEntry("message", "Not on Fridays.");
		assertThat(ToolConfirmations.fromAnswer(answer)).isEqualTo(decisions);
		assertThat(ToolConfirmations.fromAnswer("yes").decisions()).isEmpty();
		assertThat(ToolConfirmations.fromAnswer(null).decisions()).isEmpty();
	}

	@Test
	void onlyAToolConfirmationQuestionHoldsAReply() {
		Interaction interrupt = new Interaction(Interaction.KIND_INTERRUPT, "", "Proceed?", null);

		assertThatIllegalArgumentException().isThrownBy(() -> ToolConfirmations.reply(interrupt));
	}

}
