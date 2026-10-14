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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.agent.AgentEvent;
import org.springframework.ai.agent.graph.NodeEvent;
import org.springframework.ai.agent.graph.NodeEvent.NodeCompletedEvent;
import org.springframework.ai.agent.graph.NodeEvent.NodeFailedEvent;
import org.springframework.ai.agent.graph.NodeEvent.NodeStartedEvent;
import org.springframework.ai.agent.model.ModelEvent;
import org.springframework.ai.agent.model.ModelEvent.ModelCallCompletedEvent;
import org.springframework.ai.agent.model.ModelEvent.ModelCallFailedEvent;
import org.springframework.ai.agent.model.ModelEvent.ModelCallStartedEvent;
import org.springframework.ai.agent.tools.ToolEvent;
import org.springframework.ai.agent.tools.ToolEvent.ToolCallCompletedEvent;
import org.springframework.ai.agent.tools.ToolEvent.ToolCallFailedEvent;
import org.springframework.ai.agent.tools.ToolEvent.ToolCallStartedEvent;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.session.EventType;
import org.springframework.ai.session.SessionEvent;

/**
 * How a spring-ai-agent event is stored as a session event, and how a message appended by
 * a run gets an id a re-attempt of the run reproduces.
 *
 * @author Christian Tzolov
 * @since 0.11.0
 */
public final class AgentEventCodec {

	/** Payload key of every stored agent event: the context path it came from. */
	public static final String PAYLOAD_PATH = "path";

	/** Payload key: {@code started}, {@code completed} or {@code failed}. */
	public static final String PAYLOAD_PHASE = "phase";

	/** Payload key of a {@link EventType#NODE} event: the node's name. */
	public static final String PAYLOAD_NODE = "node";

	/** Payload key: the error's class and message of a failed step. */
	public static final String PAYLOAD_ERROR = "error";

	/** The prefix of the type name of an application's own event: {@code AGENT.MY_EVENT}. */
	public static final String CUSTOM_TYPE_PREFIX = "AGENT.";

	private final JsonMapper jsonMapper;

	public AgentEventCodec(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	/** The session event that stores the agent event, or {@code null} for one that is not stored. */
	public @Nullable SessionEvent encode(String sessionId, AgentEvent event) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put(PAYLOAD_PATH, event.path());
		EventType type;
		if (event instanceof NodeEvent) {
			type = EventType.NODE;
			if (event instanceof NodeStartedEvent started) {
				payload.put(PAYLOAD_PHASE, "started");
				payload.put(PAYLOAD_NODE, started.node());
				putValue(payload, "input", started.input());
			}
			else if (event instanceof NodeCompletedEvent completed) {
				payload.put(PAYLOAD_PHASE, "completed");
				payload.put(PAYLOAD_NODE, completed.node());
				putValue(payload, "output", completed.output());
			}
			else if (event instanceof NodeFailedEvent failed) {
				payload.put(PAYLOAD_PHASE, "failed");
				payload.put(PAYLOAD_NODE, failed.node());
				payload.put(PAYLOAD_ERROR, describe(failed.error()));
			}
		}
		else if (event instanceof ModelEvent) {
			type = EventType.MODEL_CALL;
			if (event instanceof ModelCallStartedEvent started) {
				payload.put(PAYLOAD_PHASE, "started");
				payload.put("messages", started.prompt().getInstructions().size());
			}
			else if (event instanceof ModelCallCompletedEvent) {
				return null; // a completed call is a USAGE event
			}
			else if (event instanceof ModelCallFailedEvent failed) {
				payload.put(PAYLOAD_PHASE, "failed");
				payload.put(PAYLOAD_ERROR, describe(failed.error()));
			}
			else {
				return null; // a streamed chunk
			}
		}
		else if (event instanceof ToolEvent) {
			type = EventType.TOOL_CALL;
			if (event instanceof ToolCallStartedEvent started) {
				payload.put(PAYLOAD_PHASE, "started");
				payload.put("name", started.name());
				payload.put("arguments", started.arguments());
			}
			else if (event instanceof ToolCallCompletedEvent completed) {
				payload.put(PAYLOAD_PHASE, "completed");
				payload.put("name", completed.name());
				payload.put("arguments", completed.arguments());
				payload.put("result", completed.result());
			}
			else if (event instanceof ToolCallFailedEvent failed) {
				payload.put(PAYLOAD_PHASE, "failed");
				payload.put("name", failed.name());
				payload.put("arguments", failed.arguments());
				payload.put(PAYLOAD_ERROR, describe(failed.error()));
			}
		}
		else {
			type = customType(event);
			Object asMap = this.jsonMapper.convertValue(event, Object.class);
			if (asMap instanceof Map<?, ?> fields) {
				fields.forEach((k, v) -> {
					if (k != null && v != null && !PAYLOAD_PATH.equals(k)) {
						payload.put(String.valueOf(k), v);
					}
				});
			}
			else if (asMap != null) {
				payload.put("value", asMap);
			}
		}
		return SessionEvent.builder().sessionId(sessionId).type(type).payload(payload).build();
	}

	/** The type of an application's own event: {@code AGENT.} plus its class name in upper snake case. */
	public static EventType customType(AgentEvent event) {
		String simple = event.getClass().getSimpleName();
		StringBuilder name = new StringBuilder(CUSTOM_TYPE_PREFIX);
		for (int i = 0; i < simple.length(); i++) {
			char c = simple.charAt(i);
			if (Character.isUpperCase(c) && i > 0 && !Character.isUpperCase(simple.charAt(i - 1))) {
				name.append('_');
			}
			name.append(Character.isLetterOrDigit(c) ? Character.toUpperCase(c) : '_');
		}
		return EventType.of(name.toString());
	}

	/**
	 * The id of the message a run appends at a position of a context path's conversation:
	 * the run, the path, the ordinal and a digest of the content, so a re-attempt that
	 * appends the same message again produces the same id, which the store ignores, while
	 * a different message at the same position is a new event.
	 */
	public static String messageId(String runId, String path, int ordinal, Message message) {
		return runId + ":" + path + ordinal + "-" + digest(message);
	}

	private static String digest(Message message) {
		StringBuilder content = new StringBuilder(message.getMessageType().name()).append('|');
		if (message.getText() != null) {
			content.append(message.getText());
		}
		if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
			assistant.getToolCalls().forEach(call -> content.append('|').append(call.id()).append(call.arguments()));
		}
		if (message instanceof ToolResponseMessage responses) {
			responses.getResponses().forEach(r -> content.append('|').append(r.id()).append(r.responseData()));
		}
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(content.toString().getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash, 0, 8);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private void putValue(Map<String, Object> payload, String key, @Nullable Object value) {
		if (value == null) {
			return;
		}
		try {
			Object plain = this.jsonMapper.convertValue(value, Object.class);
			if (plain != null) {
				payload.put(key, plain);
			}
		}
		catch (RuntimeException ex) {
			payload.put(key, String.valueOf(value));
		}
	}

	private static String describe(Exception error) {
		String message = error.getMessage();
		return error.getClass().getName() + (message != null ? ": " + message : "");
	}

	/** The last segment of a context path: {@code "a/b/"} gives {@code "b"}. */
	static String lastSegment(String path) {
		String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
		return trimmed.substring(trimmed.lastIndexOf('/') + 1);
	}

}
