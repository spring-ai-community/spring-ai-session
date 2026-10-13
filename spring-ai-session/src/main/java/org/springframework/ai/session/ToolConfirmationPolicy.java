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

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolExecutionEligibilityChecker;
import org.springframework.util.Assert;

/**
 * Which tool calls must be confirmed by a caller before they run: the counterpart of an
 * agent runtime's {@code always_ask} tool permission. The same policy is given to the
 * {@code ToolConfirmationAdvisor}, which parks a turn whose reply asks for such a call
 * ({@link org.springframework.ai.session.StopReason#REQUIRES_ACTION}) and runs it on the
 * next request when a {@link org.springframework.ai.session.ToolConfirmation} allows it,
 * and, as {@link #eligibilityChecker()}, to the tool-calling advisor, so its loop does not
 * run the call by itself.
 *
 * @author Christian Tzolov
 * @since 0.11.0
 */
@FunctionalInterface
public interface ToolConfirmationPolicy {

	/** Whether the tool call must be confirmed before it runs. */
	boolean requiresConfirmation(AssistantMessage.ToolCall toolCall);

	/** No tool call needs confirmation. */
	static ToolConfirmationPolicy none() {
		return toolCall -> false;
	}

	/** The named tools need confirmation; every other tool runs at once. */
	static ToolConfirmationPolicy forTools(String... toolNames) {
		Assert.notNull(toolNames, "toolNames must not be null");
		Set<String> names = new HashSet<>(Arrays.asList(toolNames));
		return toolCall -> names.contains(toolCall.name());
	}

	/** Whether any tool call of the reply must be confirmed. */
	default boolean requiresConfirmation(ChatResponse response) {
		return response.getResults()
			.stream()
			.filter(generation -> generation.getOutput() != null && generation.getOutput().hasToolCalls())
			.flatMap(generation -> generation.getOutput().getToolCalls().stream())
			.anyMatch(this::requiresConfirmation);
	}

	/**
	 * The checker to configure on the tool-calling advisor
	 * ({@code ToolCallingAdvisor.builder().toolExecutionEligibilityChecker(...)}): a reply
	 * with tool calls is executed by the loop only when none of them needs confirmation;
	 * otherwise the loop returns the reply as it is and the {@code ToolConfirmationAdvisor}
	 * parks the turn.
	 */
	default ToolExecutionEligibilityChecker eligibilityChecker() {
		return response -> response != null && response.hasToolCalls() && !requiresConfirmation(response);
	}

}
