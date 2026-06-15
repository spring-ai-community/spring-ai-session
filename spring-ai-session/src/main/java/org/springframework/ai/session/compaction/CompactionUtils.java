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

package org.springframework.ai.session.compaction;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.session.SessionEvent;

/**
 * Internal utilities shared by compaction strategies.
 *
 * @author Christian Tzolov
 * @since 2.0.0
 */
final class CompactionUtils {

	private CompactionUtils() {
	}

	/**
	 * Renders a {@link SessionEvent} as a single line of text suitable for token
	 * estimation and LLM summarization prompts.
	 *
	 * <p>
	 * Handles all Spring AI message types:
	 * <ul>
	 * <li>Plain user / assistant / system messages → {@code "Role: text"}</li>
	 * <li>{@link AssistantMessage} with tool calls →
	 * {@code "Assistant [tool calls: name(args), ...]"}</li>
	 * <li>{@link ToolResponseMessage} →
	 * {@code "Tool [responses: name -> data, ...]"}</li>
	 * </ul>
	 * @param event the session event to format
	 * @return a non-null, non-empty string representing the event
	 */
	static String formatEvent(SessionEvent event) {
		String role = switch (event.getMessageType()) {
			case USER -> "User";
			case ASSISTANT -> "Assistant";
			case SYSTEM -> "System";
			case TOOL -> "Tool";
		};

		if (event.getMessage() instanceof AssistantMessage am && am.hasToolCalls()) {
			String calls = am.getToolCalls()
				.stream()
				.map(tc -> tc.name() + "(" + tc.arguments() + ")")
				.collect(Collectors.joining(", "));
			String text = am.getText();
			return (text != null && !text.isBlank()) ? role + ": " + text + " [tool calls: " + calls + "]"
					: role + " [tool calls: " + calls + "]";
		}

		if (event.getMessage() instanceof ToolResponseMessage trm) {
			String responses = trm.getResponses()
				.stream()
				.map(r -> r.name() + " -> " + r.responseData())
				.collect(Collectors.joining(", "));
			return role + " [responses: " + responses + "]";
		}

		String text = event.getMessage().getText();
		return role + ": " + (text != null ? text : "[no text content]");
	}

	/**
	 * Advances {@code rawCutIndex} forward until it points to a root-level (null-branch)
	 * {@link MessageType#USER} event, or to {@code real.size()} if no such event exists.
	 *
	 * <p>
	 * Compaction strategies compute a raw cut point (the index into the real-event list
	 * where the kept window would start) based on event counts or token budgets. That raw
	 * cut can land in the middle of a turn — for example at an assistant reply whose user
	 * message would be archived. Snapping to the nearest turn start guarantees that the
	 * kept window always begins at a complete turn, preserving conversation semantics.
	 * @param real the list of non-synthetic session events
	 * @param rawCutIndex the initial cut point; must be in {@code [0, real.size()]}
	 * @return the adjusted index pointing to the first root-level USER event at or after
	 * {@code rawCutIndex}, or {@code real.size()} if none exists
	 */
	static int snapToTurnStart(List<SessionEvent> real, int rawCutIndex) {
		int idx = rawCutIndex;
		while (idx < real.size()
				&& !(real.get(idx).isRootEvent() && real.get(idx).getMessageType() == MessageType.USER)) {
			idx++;
		}
		return idx;
	}

}
