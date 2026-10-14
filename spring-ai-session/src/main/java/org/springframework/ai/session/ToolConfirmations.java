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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.model.tool.ToolCallDecision;
import org.springframework.ai.model.tool.ToolCallDecisions;
import org.springframework.util.Assert;

/**
 * How a tool confirmation is kept in the log: the question a turn parks on when the
 * tool-calling loop pauses on calls that need a person's confirmation, and the decisions
 * that answer it. The counterpart of an agent runtime's {@code requires_action} stop
 * reason and {@code user.tool_confirmation} event.
 * <p>
 * The question ({@link Interaction#KIND_TOOL_CONFIRMATION}) holds every tool call of the
 * paused reply, with the ids the loop returned, and the ids of the calls that wait for a
 * decision: {@code {toolCalls: [{id, name, arguments}], awaiting: [ids]}}. A resumption
 * rebuilds the reply from it, so the calls that run come from the log and never from the
 * caller. The answer is one map per decision: {@code {toolCallId, allowed, message}}.
 *
 * @author Christian Tzolov
 * @since 0.11.0
 * @see ToolCallDecisions
 */
public final class ToolConfirmations {

	/** The question's key for the tool calls of the paused reply. */
	public static final String PAYLOAD_TOOL_CALLS = "toolCalls";

	/** The question's key for the ids of the calls that wait for a decision. */
	public static final String PAYLOAD_AWAITING = "awaiting";

	private ToolConfirmations() {
	}

	/**
	 * The question a turn parks on.
	 * @param reply the paused reply, every tool call with an id
	 * @param awaiting the ids of the calls that wait for a decision
	 */
	public static Interaction question(AssistantMessage reply, List<String> awaiting) {
		Assert.notNull(reply, "reply must not be null");
		Assert.notEmpty(awaiting, "awaiting must not be empty");
		List<Map<String, Object>> toolCalls = new ArrayList<>();
		for (AssistantMessage.ToolCall toolCall : reply.getToolCalls()) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("id", toolCall.id());
			entry.put("name", toolCall.name());
			entry.put("arguments", (toolCall.arguments() != null) ? toolCall.arguments() : "");
			toolCalls.add(entry);
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put(PAYLOAD_TOOL_CALLS, toolCalls);
		payload.put(PAYLOAD_AWAITING, List.copyOf(awaiting));
		return new Interaction(Interaction.KIND_TOOL_CONFIRMATION, "", payload, ToolCallDecisions.class.getName());
	}

	/** The paused reply the question was asked about, with its tool calls. */
	public static AssistantMessage reply(Interaction question) {
		List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
		for (Object entry : list(payload(question).get(PAYLOAD_TOOL_CALLS))) {
			if (entry instanceof Map<?, ?> call) {
				toolCalls.add(new AssistantMessage.ToolCall(String.valueOf(call.get("id")), "function",
						String.valueOf(call.get("name")), String.valueOf(call.get("arguments"))));
			}
		}
		Assert.isTrue(!toolCalls.isEmpty(), "The tool confirmation question names no tool calls");
		return AssistantMessage.builder().content("").toolCalls(toolCalls).build();
	}

	/** The ids of the calls that wait for a decision. */
	public static List<String> awaiting(Interaction question) {
		return list(payload(question).get(PAYLOAD_AWAITING)).stream().map(String::valueOf).toList();
	}

	/**
	 * One decision per tool call of the paused reply: the caller's, and an allow for a call
	 * that does not wait for one.
	 * @throws IllegalArgumentException if a decision names no call of the reply, or a call
	 * that waits has none
	 */
	public static ToolCallDecisions complete(Interaction question, Collection<ToolCallDecision> given) {
		Assert.notNull(given, "given must not be null");
		Set<String> ids = new LinkedHashSet<>();
		reply(question).getToolCalls().forEach(toolCall -> ids.add(toolCall.id()));
		Map<String, ToolCallDecision> decisions = new LinkedHashMap<>();
		for (ToolCallDecision decision : given) {
			Assert.isTrue(ids.contains(decision.toolCallId()),
					() -> "No tool call waits on '" + decision.toolCallId() + "'; the paused calls: " + ids);
			decisions.put(decision.toolCallId(), decision);
		}
		Set<String> missing = new LinkedHashSet<>(awaiting(question));
		missing.removeAll(decisions.keySet());
		Assert.isTrue(missing.isEmpty(), () -> "Every call that waits needs a decision; missing: " + missing);
		List<ToolCallDecision> complete = new ArrayList<>();
		for (String id : ids) {
			complete.add(decisions.getOrDefault(id, ToolCallDecision.allow(id)));
		}
		return new ToolCallDecisions(complete);
	}

	/** The decisions as the question's answer: one map per decision. */
	public static List<Map<String, Object>> asAnswer(ToolCallDecisions decisions) {
		Assert.notNull(decisions, "decisions must not be null");
		List<Map<String, Object>> answer = new ArrayList<>();
		for (ToolCallDecision decision : decisions.decisions()) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("toolCallId", decision.toolCallId());
			entry.put("allowed", decision.allowed());
			if (decision.message() != null) {
				entry.put("message", decision.message());
			}
			answer.add(entry);
		}
		return answer;
	}

	/**
	 * The decisions an answer holds, as {@link #asAnswer} wrote them and a store reads them
	 * back; none for an answer of another shape.
	 */
	public static ToolCallDecisions fromAnswer(@Nullable Object answer) {
		List<ToolCallDecision> decisions = new ArrayList<>();
		for (Object entry : list(answer)) {
			if (entry instanceof Map<?, ?> map && map.get("toolCallId") != null) {
				Object message = map.get("message");
				decisions.add(new ToolCallDecision(String.valueOf(map.get("toolCallId")),
						Boolean.TRUE.equals(map.get("allowed")), (message != null) ? String.valueOf(message) : null));
			}
		}
		return new ToolCallDecisions(decisions);
	}

	private static Map<?, ?> payload(Interaction question) {
		Assert.notNull(question, "question must not be null");
		Assert.isTrue(Interaction.KIND_TOOL_CONFIRMATION.equals(question.kind()),
				() -> "Not a tool confirmation question: " + question.kind());
		return (question.payload() instanceof Map<?, ?> map) ? map : Map.of();
	}

	private static List<?> list(@Nullable Object value) {
		return (value instanceof Collection<?> collection) ? new ArrayList<>(collection) : List.of();
	}

}
