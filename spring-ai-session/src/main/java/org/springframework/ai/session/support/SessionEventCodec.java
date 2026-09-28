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

package org.springframework.ai.session.support;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.Assert;

/**
 * The persisted shape of a {@link Message}, shared by every
 * {@link org.springframework.ai.session.SessionRepository} that stores messages as
 * columns or fields rather than as Java objects.
 *
 * <p>
 * A message is stored as three parts: its {@link MessageType}, its text, and a JSON
 * {@code data} string holding the type-specific payload (the tool calls of an
 * {@link AssistantMessage}, or the responses of a {@link ToolResponseMessage}; {@code null}
 * for the other types). {@link #encode} and {@link #decode} convert between the two, and
 * {@link #toJson} / {@link #fromJsonMap} handle the metadata maps of sessions and events.
 *
 * <p>
 * The codec is lossy on purpose for now: message metadata and media are not part of the
 * persisted shape. Changing that is tracked separately, and this class is the one place
 * where it will change for every backend.
 *
 * @author Christian Tzolov
 * @since 0.10.0
 */
public final class SessionEventCodec {

	private static final Logger logger = LoggerFactory.getLogger(SessionEventCodec.class);

	private final JsonMapper jsonMapper;

	/** Creates a codec with a default {@link JsonMapper}. */
	public SessionEventCodec() {
		this(JsonMapper.builder().build());
	}

	/**
	 * Creates a codec that serializes with the given mapper.
	 * @param jsonMapper the mapper used for the {@code data} payload and the metadata maps
	 */
	public SessionEventCodec(JsonMapper jsonMapper) {
		Assert.notNull(jsonMapper, "jsonMapper must not be null");
		this.jsonMapper = jsonMapper;
	}

	/**
	 * The persisted shape of a message.
	 * @param type the message type
	 * @param text the message text, or {@code null} when the message has none
	 * @param data the JSON payload of the type-specific parts, or {@code null} when the
	 * message has none
	 */
	public record EncodedMessage(MessageType type, @Nullable String text, @Nullable String data) {
	}

	/**
	 * Encodes a message into its persisted shape.
	 * @param message the message to encode
	 * @return the type, text and JSON payload to store
	 */
	public EncodedMessage encode(Message message) {
		Assert.notNull(message, "message must not be null");
		String data = null;
		if (message instanceof AssistantMessage am && am.hasToolCalls()) {
			data = toJson(am.getToolCalls());
		}
		else if (message instanceof ToolResponseMessage trm) {
			data = toJson(trm.getResponses());
		}
		return new EncodedMessage(message.getMessageType(), message.getText(), data);
	}

	/**
	 * Rebuilds a message from its persisted shape. A payload that cannot be parsed is
	 * logged and treated as absent, so a damaged row still yields a message.
	 * @param encoded the stored type, text and JSON payload
	 * @return the message
	 */
	public Message decode(EncodedMessage encoded) {
		Assert.notNull(encoded, "encoded must not be null");
		String text = (encoded.text() != null) ? encoded.text() : "";
		String data = encoded.data();
		boolean hasData = data != null && !data.isBlank();
		return switch (encoded.type()) {
			case USER -> new UserMessage(text);
			case SYSTEM -> new SystemMessage(text);
			case ASSISTANT -> hasData
					? AssistantMessage.builder().content(encoded.text()).toolCalls(parseToolCalls(data)).build()
					: new AssistantMessage(text);
			case TOOL -> ToolResponseMessage.builder()
				.responses(hasData ? parseToolResponses(data) : List.of())
				.build();
		};
	}

	/**
	 * Serializes a value (typically a metadata map) to JSON.
	 * @param value the value, or {@code null}
	 * @return the JSON text, or {@code null} for a {@code null} value
	 * @throws IllegalStateException if the value cannot be serialized
	 */
	public @Nullable String toJson(@Nullable Object value) {
		if (value == null) {
			return null;
		}
		try {
			return this.jsonMapper.writeValueAsString(value);
		}
		catch (JacksonException ex) {
			throw new IllegalStateException("Failed to serialize value to JSON", ex);
		}
	}

	/**
	 * Parses a JSON object into a metadata map. Blank input yields an empty map; input
	 * that cannot be parsed is logged and yields an empty map, so a damaged row still
	 * loads.
	 * @param json the JSON text, or {@code null}
	 * @return the map, never {@code null}
	 */
	public Map<String, Object> fromJsonMap(@Nullable String json) {
		if (json == null || json.isBlank()) {
			return Map.of();
		}
		try {
			return this.jsonMapper.readValue(json, new TypeReference<Map<String, Object>>() {
			});
		}
		catch (JacksonException ex) {
			logger.warn("Failed to deserialize metadata JSON; returning empty map", ex);
			return new HashMap<>();
		}
	}

	private List<AssistantMessage.ToolCall> parseToolCalls(String json) {
		try {
			return this.jsonMapper.readValue(json, new TypeReference<List<AssistantMessage.ToolCall>>() {
			});
		}
		catch (JacksonException ex) {
			logger.warn("Failed to deserialize tool calls from JSON; returning empty list", ex);
			return List.of();
		}
	}

	private List<ToolResponseMessage.ToolResponse> parseToolResponses(String json) {
		try {
			return this.jsonMapper.readValue(json, new TypeReference<List<ToolResponseMessage.ToolResponse>>() {
			});
		}
		catch (JacksonException ex) {
			logger.warn("Failed to deserialize tool responses from JSON; returning empty list", ex);
			return List.of();
		}
	}

}
