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

/**
 * Token counts of one model call, or the sum of many: what a {@link EventType#USAGE}
 * event records and what {@link SessionService#usage} and
 * {@link SessionService#aggregateUsage} return.
 *
 * @param promptTokens tokens sent to the model
 * @param completionTokens tokens the model produced
 * @param totalTokens prompt plus completion tokens, as the provider counts them
 * @param cacheReadInputTokens prompt tokens served from the provider's prompt cache, when
 * the provider reports them
 * @param cacheWriteInputTokens prompt tokens written to the provider's prompt cache, when
 * the provider reports them
 * @author Christian Tzolov
 * @since 0.11.0
 */
public record TokenUsage(long promptTokens, long completionTokens, long totalTokens, long cacheReadInputTokens,
		long cacheWriteInputTokens) {

	/** No tokens. */
	public static final TokenUsage ZERO = new TokenUsage(0, 0, 0, 0, 0);

	public TokenUsage {
		if (promptTokens < 0 || completionTokens < 0 || totalTokens < 0 || cacheReadInputTokens < 0
				|| cacheWriteInputTokens < 0) {
			throw new IllegalArgumentException("Token counts must not be negative");
		}
	}

	/** The counts of a model call's {@link Usage}; {@link #ZERO} for {@code null}. */
	public static TokenUsage of(@Nullable Usage usage) {
		if (usage == null) {
			return ZERO;
		}
		return new TokenUsage(count(usage.getPromptTokens()), count(usage.getCompletionTokens()),
				count(usage.getTotalTokens()), count(usage.getCacheReadInputTokens()),
				count(usage.getCacheWriteInputTokens()));
	}

	/** This usage plus another. */
	public TokenUsage plus(TokenUsage other) {
		return new TokenUsage(this.promptTokens + other.promptTokens, this.completionTokens + other.completionTokens,
				this.totalTokens + other.totalTokens, this.cacheReadInputTokens + other.cacheReadInputTokens,
				this.cacheWriteInputTokens + other.cacheWriteInputTokens);
	}

	/** Whether every count is zero. */
	public boolean isEmpty() {
		return equals(ZERO);
	}

	private static long count(@Nullable Number value) {
		return value != null ? Math.max(0, value.longValue()) : 0;
	}

}
