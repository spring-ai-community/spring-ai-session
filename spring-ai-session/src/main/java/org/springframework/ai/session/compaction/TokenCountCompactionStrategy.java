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
import java.util.List;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;

import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.util.Assert;

/**
 * Compaction strategy that retains events within a maximum estimated token budget. Token
 * count is approximated with the help of a {@link TokenCountEstimator}.
 *
 * <h3>Algorithm</h3>
 * <ol>
 * <li>Separate the latest stored system message of each branch (that agent's system prompt — earlier stored system
 * messages are superseded and archived; see {@code CompactionUtils#pinnedSystemEvents}) and synthetic
 * summary events — they are always preserved and placed first in the result. Their token
 * cost is deducted from the budget before real events are considered, so a large system
 * message or prior compaction summary reduces the space available for real events.</li>
 * <li>Walk real events from newest to oldest, accumulating cost until the budget is
 * exhausted. Stops at the first event that would exceed the remaining budget, producing a
 * contiguous kept window (a suffix of the real-event list). Skipping oversize events and
 * continuing would produce non-contiguous gaps that break conversation coherence.</li>
 * <li>Snap the cut point to the next root-level ({@code branch == null}) user message.
 * This guarantees the kept window always starts at a turn boundary — sub-agent
 * {@code USER} messages are skipped because they are turn-internal, not turn starts.</li>
 * <li>Return: {@code [system messages] + [synthetic events] + [kept events]}.</li>
 * </ol>
 *
 * <h3>No-op condition</h3>
 * <p>
 * If all real events fit within the token budget no events are archived and the session
 * is returned unchanged.
 *
 * @author Christian Tzolov
 * @since 2.0.0
 */
public final class TokenCountCompactionStrategy implements CompactionStrategy {

	public static final int DEFAULT_MAX_TOKENS = 4000;

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

		// Always keep the latest stored system message of each branch and synthetic events; earlier stored
		// system messages are superseded and archived
		List<SessionEvent> pinnedSystem = CompactionUtils.pinnedSystemEvents(events);
		List<SessionEvent> supersededSystem = CompactionUtils.supersededSystemEvents(events, pinnedSystem);
		List<SessionEvent> synthetic = events.stream().filter(SessionEvent::isSynthetic).toList();
		List<SessionEvent> real = CompactionUtils.compactableEvents(events);
		ToIntFunction<SessionEvent> tokens = e -> this.tokenCountEstimator.estimate(CompactionUtils.formatEvent(e));

		// Preserved events are sent to the model too, so their cost comes off the budget
		// first.
		int preservedTokens = Stream.concat(pinnedSystem.stream(), synthetic.stream()).mapToInt(tokens).sum();

		int remainingBudget = this.maxTokens - preservedTokens;

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

		// Snap the raw cut forward to the nearest root-level USER event so the kept
		// window always starts at a turn boundary. Sub-agent USER messages (branch != null)
		// are skipped — they are turn-internal, not turn starts.
		// If no later turn start exists (the newest turn alone exceeds the budget), keep
		// that last turn rather than archiving the whole active window.
		int cutIndex = CompactionUtils.retainLastTurn(real, CompactionUtils.snapToTurnStart(real, rawCutIndex));

		// Build kept and archived lists in chronological order
		List<SessionEvent> kept = new ArrayList<>(real.subList(cutIndex, real.size()));
		List<SessionEvent> archived = new ArrayList<>(real.subList(0, cutIndex));

		if (archived.isEmpty()) {
			return CompactionUtils.unchangedExceptSuperseded(events, pinnedSystem, synthetic, real, supersededSystem,
					tokens);
		}

		List<SessionEvent> compacted = new ArrayList<>(pinnedSystem);
		compacted.addAll(synthetic);
		compacted.addAll(kept);

		return CompactionUtils.archiving(events, compacted, archived, supersededSystem, tokens);
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
