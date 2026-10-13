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

import org.jspecify.annotations.Nullable;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.util.Assert;

/**
 * The {@link EventType#USAGE} events that record the token usage of a model call, the
 * counterpart of an agent runtime's {@code model_usage} on a model-call span. The
 * session's usage is the sum of them ({@link SessionService#usage}), never stored on its
 * own.
 *
 * @author Christian Tzolov
 * @since 0.11.0
 */
public final class UsageEvents {

	/** Payload key: prompt tokens. */
	public static final String PAYLOAD_PROMPT_TOKENS = "promptTokens";

	/** Payload key: completion tokens. */
	public static final String PAYLOAD_COMPLETION_TOKENS = "completionTokens";

	/** Payload key: total tokens. */
	public static final String PAYLOAD_TOTAL_TOKENS = "totalTokens";

	/** Payload key: prompt tokens read from the provider's cache, when reported. */
	public static final String PAYLOAD_CACHE_READ_INPUT_TOKENS = "cacheReadInputTokens";

	/** Payload key: prompt tokens written to the provider's cache, when reported. */
	public static final String PAYLOAD_CACHE_WRITE_INPUT_TOKENS = "cacheWriteInputTokens";

	/** Payload key: the model that served the call, when known. */
	public static final String PAYLOAD_MODEL = "model";

	/** Payload key: the place in an agent run the call was made from, when known. */
	public static final String PAYLOAD_PATH = "path";

	/** Payload key: how many messages the call sent, when known. */
	public static final String PAYLOAD_MESSAGES = "messages";

	private UsageEvents() {
	}

	/** The event that records one model call's usage. */
	public static SessionEvent of(String sessionId, TokenUsage usage, @Nullable String model) {
		return of(sessionId, usage, model, null, null);
	}

	/**
	 * The event that records one completed model call of an agent run: its usage, the model,
	 * where in the run it was made and how many messages it sent.
	 */
	public static SessionEvent of(String sessionId, TokenUsage usage, @Nullable String model, @Nullable String path,
			@Nullable Integer messages) {
		Assert.notNull(usage, "usage must not be null");
		SessionEvent.Builder event = SessionEvent.builder()
			.sessionId(sessionId)
			.type(EventType.USAGE)
			.payload(PAYLOAD_PROMPT_TOKENS, usage.promptTokens())
			.payload(PAYLOAD_COMPLETION_TOKENS, usage.completionTokens())
			.payload(PAYLOAD_TOTAL_TOKENS, usage.totalTokens());
		if (usage.cacheReadInputTokens() > 0) {
			event.payload(PAYLOAD_CACHE_READ_INPUT_TOKENS, usage.cacheReadInputTokens());
		}
		if (usage.cacheWriteInputTokens() > 0) {
			event.payload(PAYLOAD_CACHE_WRITE_INPUT_TOKENS, usage.cacheWriteInputTokens());
		}
		if (model != null && !model.isBlank()) {
			event.payload(PAYLOAD_MODEL, model);
		}
		if (path != null) {
			event.payload(PAYLOAD_PATH, path);
		}
		if (messages != null) {
			event.payload(PAYLOAD_MESSAGES, messages);
		}
		return event.build();
	}

	/** The event that records one model call's {@link Usage}. */
	public static SessionEvent of(String sessionId, Usage usage, @Nullable String model) {
		return of(sessionId, TokenUsage.of(usage), model);
	}

	/** The usage a {@link EventType#USAGE} event records, or {@code null} for another kind of event. */
	public static @Nullable TokenUsage usage(SessionEvent event) {
		if (event.getType() != EventType.USAGE) {
			return null;
		}
		return new TokenUsage(count(event, PAYLOAD_PROMPT_TOKENS), count(event, PAYLOAD_COMPLETION_TOKENS),
				count(event, PAYLOAD_TOTAL_TOKENS), count(event, PAYLOAD_CACHE_READ_INPUT_TOKENS),
				count(event, PAYLOAD_CACHE_WRITE_INPUT_TOKENS));
	}

	/** The model a {@link EventType#USAGE} event names, or {@code null}. */
	public static @Nullable String model(SessionEvent event) {
		Object value = event.getPayload().get(PAYLOAD_MODEL);
		return (event.getType() == EventType.USAGE && value != null) ? String.valueOf(value) : null;
	}

	private static long count(SessionEvent event, String key) {
		Object value = event.getPayload().get(key);
		if (value instanceof Number number) {
			return number.longValue();
		}
		return value != null ? Long.parseLong(String.valueOf(value)) : 0;
	}

}
