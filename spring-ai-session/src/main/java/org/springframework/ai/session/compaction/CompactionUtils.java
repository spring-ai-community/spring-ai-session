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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;
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
	 * Returns {@code true} for a system message stored by the application: a
	 * non-synthetic {@link MessageType#SYSTEM} event, on any branch. Synthetic events
	 * (e.g. legacy {@code SYSTEM} summaries) keep their own handling.
	 */
	static boolean isStoredSystemEvent(SessionEvent event) {
		return !event.isSynthetic() && event.getMessageType() == MessageType.SYSTEM;
	}

	/**
	 * Returns the stored system messages that compaction keeps: the <em>latest</em>
	 * {@linkplain #isStoredSystemEvent stored system event} of each branch (the root
	 * agent's {@code null} branch included), in log order.
	 *
	 * <p>
	 * An agent's latest stored system message is its system prompt ("latest wins", per
	 * branch): storing a new one replaces the previous one of the same branch, and it is
	 * the integrator's responsibility to put the complete intended content in it. Every
	 * strategy keeps these events in the active window, places them first and never
	 * summarizes them, so a sub-agent that is delegated to again in a later turn still
	 * has its system prompt. Earlier stored system messages are
	 * {@linkplain #supersededSystemEvents superseded} and archived. At most one stored
	 * system message per branch is ever kept, so storing one per turn cannot grow the
	 * active window.
	 * @param events the session events, oldest first
	 * @return the latest stored system event of each branch, in log order
	 */
	static List<SessionEvent> pinnedSystemEvents(List<SessionEvent> events) {
		Map<String, SessionEvent> latestByBranch = new HashMap<>();
		for (SessionEvent event : events) {
			if (isStoredSystemEvent(event)) {
				latestByBranch.put(event.getBranch(), event);
			}
		}
		if (latestByBranch.isEmpty()) {
			return List.of();
		}
		Set<SessionEvent> latest = new HashSet<>(latestByBranch.values());
		return events.stream().filter(latest::contains).toList();
	}

	/**
	 * Returns the stored system events superseded by a later one: every
	 * {@linkplain #isStoredSystemEvent stored system event} other than the
	 * {@code pinned} one, in order. Strategies archive them (they remain searchable
	 * through Recall Storage) and never summarize them.
	 */
	static List<SessionEvent> supersededSystemEvents(List<SessionEvent> events, List<SessionEvent> pinned) {
		return events.stream().filter(e -> isStoredSystemEvent(e) && !pinned.contains(e)).toList();
	}

	/**
	 * Returns the real conversation events subject to the strategy's budget: every event
	 * that is neither synthetic nor a {@linkplain #isStoredSystemEvent stored system
	 * event}, in order.
	 */
	static List<SessionEvent> compactableEvents(List<SessionEvent> events) {
		return events.stream().filter(e -> !e.isSynthetic() && !isStoredSystemEvent(e)).toList();
	}

	/**
	 * Result for a pass where the strategy's budget needs no cut. The events are returned
	 * unchanged when there are no superseded system events; otherwise only the superseded
	 * ones are archived, so "latest wins" is applied whenever compaction runs.
	 */
	static CompactionResult unchangedExceptSuperseded(List<SessionEvent> events, List<SessionEvent> pinned,
			List<SessionEvent> synthetic, List<SessionEvent> real, List<SessionEvent> superseded,
			ToIntFunction<SessionEvent> tokens) {
		if (superseded.isEmpty()) {
			return new CompactionResult(events, List.of(), 0);
		}
		List<SessionEvent> compacted = new ArrayList<>(pinned);
		compacted.addAll(synthetic);
		compacted.addAll(real);
		return new CompactionResult(compacted, superseded, superseded.stream().mapToInt(tokens).sum());
	}

	/**
	 * Result for a pass that cut real events: archives the removed real events together
	 * with the superseded system events, in their original log order.
	 */
	static CompactionResult archiving(List<SessionEvent> events, List<SessionEvent> compacted,
			List<SessionEvent> removedReal, List<SessionEvent> superseded, ToIntFunction<SessionEvent> tokens) {
		Set<SessionEvent> toArchive = new HashSet<>(removedReal);
		toArchive.addAll(superseded);
		List<SessionEvent> archived = events.stream().filter(toArchive::contains).toList();
		return new CompactionResult(compacted, archived, archived.stream().mapToInt(tokens).sum());
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

	/**
	 * Guards against a cut that would archive the entire real-event window. When
	 * {@code cutIndex == real.size()} — e.g. because the most recent turn alone exceeds
	 * the strategy's budget, so {@link #snapToTurnStart} found no later turn start — the
	 * cut is moved back to the last root-level {@link MessageType#USER} event so that the
	 * current turn is always kept in the active window, even if it exceeds the budget.
	 * Returns {@code cutIndex} unchanged when it already keeps at least one event or when
	 * there is no root-level {@code USER} event to fall back to.
	 * @param real the list of non-synthetic session events
	 * @param cutIndex the snapped cut point; must be in {@code [0, real.size()]}
	 * @return an index that keeps at least the last complete root turn, if one exists
	 */
	static int retainLastTurn(List<SessionEvent> real, int cutIndex) {
		if (cutIndex < real.size()) {
			return cutIndex;
		}
		for (int i = real.size() - 1; i >= 0; i--) {
			if (real.get(i).isRootEvent() && real.get(i).getMessageType() == MessageType.USER) {
				return i;
			}
		}
		return cutIndex;
	}

}
