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

import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.util.Assert;

/**
 * Triggers compaction when the estimated token count of the session's events reaches a
 * threshold. Token count is measured using a configurable {@link TokenCountEstimator},
 * defaulting to {@link JTokkitTokenCountEstimator} — the same estimator used by
 * {@link TokenCountCompactionStrategy} — so that the trigger threshold and the strategy
 * budget are expressed in the same units and can be calibrated against each other.
 *
 * @author Christian Tzolov
 * @since 2.0.0
 * @see TokenCountCompactionStrategy
 */
public final class TokenCountTrigger implements CompactionTrigger {

	private final int threshold;

	private final TokenCountEstimator tokenCountEstimator;

	/**
	 * Creates a trigger with the given threshold, using
	 * {@link JTokkitTokenCountEstimator} to count tokens.
	 * @param threshold minimum total token count that triggers compaction. Must be
	 * positive.
	 */
	public TokenCountTrigger(int threshold) {
		this(threshold, new JTokkitTokenCountEstimator());
	}

	/**
	 * Creates a trigger with the given threshold and token count estimator.
	 * @param threshold minimum total token count that triggers compaction. Must be
	 * positive.
	 * @param tokenCountEstimator estimator used to measure each event's token cost. Must
	 * not be {@code null}.
	 */
	public TokenCountTrigger(int threshold, TokenCountEstimator tokenCountEstimator) {
		Assert.isTrue(threshold > 0, "threshold must be greater than 0");
		Assert.notNull(tokenCountEstimator, "tokenCountEstimator must not be null");
		this.threshold = threshold;
		this.tokenCountEstimator = tokenCountEstimator;
	}

	@Override
	public boolean shouldCompact(CompactionRequest request) {
		int totalTokens = request.events()
			.stream()
			.map(e -> e.getMessage().getText())
			.filter(t -> t != null)
			.mapToInt(this.tokenCountEstimator::estimate)
			.sum();
		return totalTokens >= this.threshold;
	}

	public int getThreshold() {
		return this.threshold;
	}

}
