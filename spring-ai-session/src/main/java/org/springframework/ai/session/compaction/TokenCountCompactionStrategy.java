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
 * Compaction strategy that keeps the view within a maximum estimated token budget. Token
 * count is approximated with the help of a {@link TokenCountEstimator}.
 *
 * <h2>Algorithm</h2>
 * <ol>
 * <li>Pin the latest stored system message (the system prompt; see
 * {@code CompactionUtils#pinnedSystemEvents}) and the synthetic summary events: they stay
 * at the front of the view whatever the cut. Their token cost is deducted from the budget
 * before real events are considered, so a large system prompt or prior compaction summary
 * reduces the space available for real events.</li>
 * <li>Walk real events from newest to oldest, accumulating cost until the budget is
 * exhausted. Stops at the first event that would exceed the remaining budget, producing a
 * contiguous kept window (a suffix of the real-event list). Skipping oversize events and
 * continuing would produce non-contiguous gaps that break conversation coherence.</li>
 * <li>Snap the cut point to the next user message. This guarantees the kept window
 * always starts at a turn boundary.</li>
 * <li>Cover every real event before the cut and preserve the rest. The log is not
 * changed: the service records the decision as an appended compaction boundary, and the
 * view from then on is the pinned events followed by the preserved ones.</li>
 * </ol>
 *
 * <h2>No-op condition</h2>
 * <p>
 * If all real events fit within the token budget nothing is covered and
 * {@link CompactionResult#unchanged()} is returned; no boundary is written. A superseded
 * stored system message is covered only when a later pass cuts.
 *
 * @author Christian Tzolov
 * @since 2.0.0
 */
public final class TokenCountCompactionStrategy implements CompactionStrategy {

	public static final int DEFAULT_MAX_TOKENS = 4000;

	private static final String STRATEGY_NAME = "token-count";

	private final int maxTokens;

	private final TokenCountEstimator tokenCountEstimator;

	private TokenCountCompactionStrategy(int maxTokens, TokenCountEstimator tokenCountEstimator) {
		Assert.isTrue(maxTokens > 0, "maxTokens must be greater than 0");
		Assert.notNull(tokenCountEstimator, "tokenCountEstimator must not be null");
		this.maxTokens = maxTokens;
		this.tokenCountEstimator = tokenCountEstimator;
	}

	@Override
	public CompactionResult compact(CompactionRequest context) {

		Assert.notNull(context, "context must not be null");
		Assert.notNull(context.session(), "session must not be null");

		List<SessionEvent> events = context.events();

		// The latest stored system message and the synthetic summary a previous compaction
		// left are pinned: kept at the front of the view whatever the cut
		List<SessionEvent> pinned = CompactionUtils.concat(CompactionUtils.pinnedSystemEvents(events),
				CompactionUtils.syntheticEvents(events));
		List<SessionEvent> real = CompactionUtils.compactableEvents(events);
		ToIntFunction<SessionEvent> tokens = e -> this.tokenCountEstimator.estimate(CompactionUtils.formatEvent(e));

		// Pinned events are sent to the model too, so their cost comes off the budget
		// first.
		int remainingBudget = this.maxTokens - pinned.stream().mapToInt(tokens).sum();

		// Walk from newest to oldest, accumulating events until the budget is reached.
		// Stop at the first event that would exceed the remaining budget so the kept
		// window is always a contiguous suffix — keeping older events after skipping a
		// large middle event would produce gaps that break conversation coherence.
		int rawCutIndex = real.size();
		int usedTokens = 0;
		for (int i = real.size() - 1; i >= 0; i--) {
			int eventTokens = tokens.applyAsInt(real.get(i));
			if (usedTokens + eventTokens <= remainingBudget) {
				usedTokens += eventTokens;
				rawCutIndex = i;
			}
			else {
				break;
			}
		}

		// Everything fits: nothing leaves the view and no boundary is written, even when
		// the view starts mid-turn (snapping the cut would otherwise cover the preamble)
		if (rawCutIndex == 0) {
			return CompactionResult.unchanged();
		}

		// Snap the raw cut forward to the nearest USER event so the kept window always
		// starts at a turn boundary.
		// If no later turn start exists (the newest turn alone exceeds the budget), keep
		// that last turn rather than covering the whole active window.
		int cutIndex = CompactionUtils.retainLastTurn(real, CompactionUtils.snapToTurnStart(real, rawCutIndex));

		return CompactionUtils.cut(events, pinned, real, cutIndex, List.of(), 0, tokens, STRATEGY_NAME);
	}

	public int getMaxTokens() {
		return this.maxTokens;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private int maxTokens = DEFAULT_MAX_TOKENS;

		private TokenCountEstimator tokenCountEstimator = new JTokkitTokenCountEstimator();

		private Builder() {
		}

		public Builder maxTokens(int maxTokens) {
			Assert.isTrue(maxTokens > 0, "maxTokens must be greater than 0");
			this.maxTokens = maxTokens;
			return this;
		}

		public Builder tokenCountEstimator(TokenCountEstimator tokenCountEstimator) {
			Assert.notNull(tokenCountEstimator, "tokenCountEstimator must not be null");
			this.tokenCountEstimator = tokenCountEstimator;
			return this;
		}

		public TokenCountCompactionStrategy build() {
			return new TokenCountCompactionStrategy(this.maxTokens, this.tokenCountEstimator);
		}

	}

}
