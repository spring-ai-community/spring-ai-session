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
import java.util.function.ToIntFunction;

import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.util.Assert;

/**
 * Compaction strategy that keeps only the last {@code maxEvents} real events in the
 * view, always cutting on a turn boundary to preserve conversation semantics.
 *
 * <h2>Algorithm</h2>
 * <ol>
 * <li>Pin the latest stored system message (the system prompt; see
 * {@code CompactionUtils#pinnedSystemEvents}) and the synthetic summary events: they stay
 * at the front of the view whatever the cut and do not consume slots from the
 * {@code maxEvents} budget.</li>
 * <li>Compute a raw cut index that keeps the last {@code maxEvents} real events.</li>
 * <li>Snap the raw cut index forward to the nearest
 * {@link org.springframework.ai.chat.messages.MessageType#USER} event so the kept window
 * always starts at a turn boundary.</li>
 * <li>Cover every real event before the cut and preserve the rest. The log is not
 * changed: the service records the decision as an appended compaction boundary, and the
 * view from then on is the pinned events followed by the preserved ones.</li>
 * </ol>
 *
 * <h2>No-op condition</h2>
 * <p>
 * If the number of real events does not exceed {@code maxEvents} nothing is covered and
 * {@link CompactionResult#unchanged()} is returned; no boundary is written. A superseded
 * stored system message is covered only when a later pass cuts.
 *
 * @author Christian Tzolov
 * @since 0.1.0
 * @see TurnWindowCompactionStrategy
 */
public final class SlidingWindowCompactionStrategy implements CompactionStrategy {

	private static final String STRATEGY_NAME = "sliding-window";

	public static final int DEFAULT_MAX_EVENTS = 20;

	private final int maxEvents;

	private final TokenCountEstimator tokenCountEstimator;

	private SlidingWindowCompactionStrategy(int maxEvents, TokenCountEstimator tokenCountEstimator) {
		Assert.isTrue(maxEvents > 0, "maxEvents must be greater than 0");
		Assert.notNull(tokenCountEstimator, "tokenCountEstimator must not be null");
		this.maxEvents = maxEvents;
		this.tokenCountEstimator = tokenCountEstimator;
	}

	@Override
	public CompactionResult compact(CompactionRequest request) {
		Assert.notNull(request, "request must not be null");

		List<SessionEvent> events = request.events();

		// The latest stored system message (the system prompt) and the synthetic summary a
		// previous compaction left are pinned: kept at the front of the view whatever the
		// cut. The window applies to the real conversation events only.
		List<SessionEvent> pinned = CompactionUtils.concat(CompactionUtils.pinnedSystemEvents(events),
				CompactionUtils.syntheticEvents(events));
		List<SessionEvent> real = CompactionUtils.compactableEvents(events);
		ToIntFunction<SessionEvent> tokens = e -> this.tokenCountEstimator.estimate(CompactionUtils.formatEvent(e));

		// maxEvents controls the real-events window only; pinned events do not consume
		// slots from the budget. No-op if the real events fit within the available slots.
		if (real.size() <= this.maxEvents) {
			return CompactionResult.unchanged();
		}

		// Keep the last maxEvents real events, cut at a turn boundary
		int cutIndex = CompactionUtils.turnCut(real, real.size() - this.maxEvents);
		return CompactionUtils.cut(events, pinned, real, cutIndex, List.of(), 0, tokens, STRATEGY_NAME);
	}

	public int getMaxEvents() {
		return this.maxEvents;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private int maxEvents = DEFAULT_MAX_EVENTS;

		private TokenCountEstimator tokenCountEstimator = new JTokkitTokenCountEstimator();

		private Builder() {
		}

		public Builder maxEvents(int maxEvents) {
			Assert.isTrue(maxEvents > 0, "maxEvents must be greater than 0");
			this.maxEvents = maxEvents;
			return this;
		}

		public Builder tokenCountEstimator(TokenCountEstimator tokenCountEstimator) {
			Assert.notNull(tokenCountEstimator, "tokenCountEstimator must not be null");
			this.tokenCountEstimator = tokenCountEstimator;
			return this;
		}

		public SlidingWindowCompactionStrategy build() {
			return new SlidingWindowCompactionStrategy(this.maxEvents, this.tokenCountEstimator);
		}

	}

}
