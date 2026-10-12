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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
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
		// An event that is not a message is not sent to the model and costs nothing
		return event.hasMessage() ? formatMessage(event.getMessage()) : "";
	}

	/** Renders a message the way {@link #formatEvent(SessionEvent)} renders its event. */
	static String formatMessage(Message message) {
		String role = switch (message.getMessageType()) {
			case USER -> "User";
			case ASSISTANT -> "Assistant";
			case SYSTEM -> "System";
			case TOOL -> "Tool";
		};

		if (message instanceof AssistantMessage am && am.hasToolCalls()) {
			String calls = am.getToolCalls()
				.stream()
				.map(tc -> tc.name() + "(" + tc.arguments() + ")")
				.collect(Collectors.joining(", "));
			String text = am.getText();
			return (text != null && !text.isBlank()) ? role + ": " + text + " [tool calls: " + calls + "]"
					: role + " [tool calls: " + calls + "]";
		}

		if (message instanceof ToolResponseMessage trm) {
			String responses = trm.getResponses()
				.stream()
				.map(r -> r.name() + " -> " + r.responseData())
				.collect(Collectors.joining(", "));
			return role + " [responses: " + responses + "]";
		}

		String text = message.getText();
		return role + ": " + (text != null ? text : "[no text content]");
	}

	/**
	 * Returns {@code true} for a system message stored by the application: a
	 * non-synthetic {@link MessageType#SYSTEM} event. Synthetic events (e.g. legacy
	 * {@code SYSTEM} summaries) keep their own handling.
	 */
	static boolean isStoredSystemEvent(SessionEvent event) {
		return event.hasMessage() && !event.isSynthetic() && event.getMessageType() == MessageType.SYSTEM;
	}

	/**
	 * Returns the stored system message that compaction keeps: the <em>latest</em>
	 * {@linkplain #isStoredSystemEvent stored system event}, as a list of at most one
	 * event.
	 *
	 * <p>
	 * The latest stored system message is the agent's system prompt ("latest wins"):
	 * storing a new one replaces the previous one, and it is the integrator's
	 * responsibility to put the complete intended content in it. Every strategy pins this
	 * event, so it stays at the front of the view whatever the cut, and never summarizes
	 * it. Every earlier stored system message is superseded and covered by a pass that
	 * cuts, wherever it sits ({@link #cut}), so a compacted view holds at most one stored
	 * system message and a reader such as {@code SessionMemoryAdvisor} can take the last
	 * one in view order: before any compaction that is log order, after one the pinned
	 * event is alone unless a newer one was stored since.
	 * @param events the view, oldest first
	 * @return the latest stored system event, or an empty list if there is none
	 */
	static List<SessionEvent> pinnedSystemEvents(List<SessionEvent> events) {
		for (int i = events.size() - 1; i >= 0; i--) {
			if (isStoredSystemEvent(events.get(i))) {
				return List.of(events.get(i));
			}
		}
		return List.of();
	}

	/** Returns the synthetic events of the view: the summary a previous compaction left. */
	static List<SessionEvent> syntheticEvents(List<SessionEvent> events) {
		return events.stream().filter(SessionEvent::isSynthetic).toList();
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
	 * Returns the events whose tokens count toward a token budget: the events actually
	 * sent to the model. That is the latest stored system message, the synthetic summary
	 * events and the real conversation; superseded system messages are not sent, so they
	 * are not counted. {@link TokenCountCompactionStrategy} and {@link TokenCountTrigger}
	 * both use this, so the trigger's threshold and the strategy's budget measure the same
	 * thing.
	 */
	static List<SessionEvent> budgetedEvents(List<SessionEvent> events) {
		List<SessionEvent> pinned = pinnedSystemEvents(events);
		return events.stream().filter(e -> !isStoredSystemEvent(e) || pinned.contains(e)).toList();
	}

	/**
	 * Builds the result of a cut. The real events before {@code cutIndex} leave the view,
	 * together with every other view event that precedes the first preserved event and is
	 * not pinned (a superseded system message, a prior summary a summarizing strategy
	 * replaces). The pinned events go first; the real events from {@code cutIndex} on, and
	 * every non-pinned view event after the first of them, are preserved in log order.
	 * @param view the view the strategy received, oldest first
	 * @param pinned the events kept at the front whatever the cut
	 * @param real the {@linkplain #compactableEvents compactable} events of the view
	 * @param cutIndex the index into {@code real} of the first preserved real event;
	 * {@code real.size()} preserves none
	 * @param summary the messages that stand in for the covered events, or an empty list
	 * @param summaryTokens the estimated tokens of the summary
	 * @param tokens the token estimate of an event
	 * @param strategy the strategy name
	 * @return the result; {@link CompactionResult#unchanged()} when nothing would leave the
	 * view
	 */
	static CompactionResult cut(List<SessionEvent> view, List<SessionEvent> pinned, List<SessionEvent> real,
			int cutIndex, List<Message> summary, int summaryTokens, ToIntFunction<SessionEvent> tokens,
			String strategy) {
		Set<SessionEvent> pinnedSet = new HashSet<>(pinned);
		int preservedFrom = (cutIndex < real.size()) ? view.indexOf(real.get(cutIndex)) : view.size();
		List<SessionEvent> covered = new ArrayList<>();
		List<SessionEvent> preserved = new ArrayList<>();
		for (int i = 0; i < view.size(); i++) {
			SessionEvent event = view.get(i);
			if (pinnedSet.contains(event)) {
				continue;
			}
			// A stored system message that is not the pinned one is superseded: it leaves
			// the view with this pass even when it sits inside the preserved window
			(i < preservedFrom || isStoredSystemEvent(event) ? covered : preserved).add(event);
		}
		if (covered.isEmpty()) {
			return CompactionResult.unchanged();
		}
		int saved = covered.stream().mapToInt(tokens).sum() - summaryTokens;
		return new CompactionResult(covered, pinned, preserved, summary, Math.max(0, saved), strategy);
	}

	/**
	 * Returns {@code true} if the event starts a turn (see
	 * {@link SessionEvent#isTurnStart()}). Synthetic events are excluded from the
	 * real-event list before this is applied.
	 */
	static boolean isTurnStart(SessionEvent event) {
		return event.isTurnStart();
	}

	/**
	 * Advances {@code rawCutIndex} forward until it points to a
	 * {@linkplain #isTurnStart turn start}, or to {@code real.size()} if no such event
	 * exists.
	 *
	 * <p>
	 * Compaction strategies compute a raw cut point (the index into the real-event list
	 * where the kept window would start) based on event counts or token budgets. That raw
	 * cut can land in the middle of a turn — for example at an assistant reply whose user
	 * message would be covered. Snapping to the nearest turn start guarantees that the
	 * kept window always begins at a complete turn, preserving conversation semantics.
	 * @param real the list of non-synthetic session events
	 * @param rawCutIndex the initial cut point; must be in {@code [0, real.size()]}
	 * @return the adjusted index pointing to the first USER event at or after
	 * {@code rawCutIndex}, or {@code real.size()} if none exists
	 */
	static int snapToTurnStart(List<SessionEvent> real, int rawCutIndex) {
		int idx = rawCutIndex;
		while (idx < real.size() && !isTurnStart(real.get(idx))) {
			idx++;
		}
		return idx;
	}

	/**
	 * Guards against a cut that would cover the entire real-event window. When
	 * {@code cutIndex == real.size()} — e.g. because the most recent turn alone exceeds
	 * the strategy's budget, so {@link #snapToTurnStart} found no later turn start — the
	 * cut is moved back to the last {@linkplain #isTurnStart turn start} so that the
	 * current turn is always kept in the active window, even if it exceeds the budget.
	 * When there is no {@code USER} event at all (no turn boundary to cut at), returns
	 * {@code 0}, so nothing is covered. Returns {@code cutIndex} unchanged when it already
	 * keeps at least one event.
	 * @param real the list of non-synthetic session events
	 * @param cutIndex the snapped cut point; must be in {@code [0, real.size()]}
	 * @return an index that keeps at least the last complete turn, or {@code 0} when
	 * there is no turn
	 */
	static int retainLastTurn(List<SessionEvent> real, int cutIndex) {
		if (cutIndex < real.size()) {
			return cutIndex;
		}
		for (int i = real.size() - 1; i >= 0; i--) {
			if (isTurnStart(real.get(i))) {
				return i;
			}
		}
		// No turn boundary to cut at: cover nothing rather than the whole window
		return 0;
	}

	/** Concatenates two event lists. */
	static List<SessionEvent> concat(List<SessionEvent> first, List<SessionEvent> second) {
		List<SessionEvent> all = new ArrayList<>(first.size() + second.size());
		all.addAll(first);
		all.addAll(second);
		return all;
	}

}
