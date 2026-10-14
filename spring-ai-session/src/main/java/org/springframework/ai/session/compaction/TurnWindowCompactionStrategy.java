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

import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.util.Assert;

/**
 * Compaction strategy that keeps only the last {@code maxTurns} complete <em>turns</em>
 * in the view, covering older ones.
 *
 * <h2>What is a turn?</h2>
 * <p>
 * A turn begins with a {@link MessageType#USER} message and ends just before the next
 * user message. It includes every event the agent produced in response: assistant
 * messages, tool calls, tool results, and any additional assistant follow-ups. A turn is
 * the atomic unit of a conversation.
 *
 * <h2>Algorithm</h2>
 * <ol>
 * <li>Pin the latest stored system message (the system prompt; see
 * {@code CompactionUtils#pinnedSystemEvents}) and the synthetic summary events: they stay
 * at the front of the view whatever the cut.</li>
 * <li>Pin any events that appear before the first user message (rare, but possible for
 * pre-seeded tool state): the preamble is kept verbatim, ahead of the turns.</li>
 * <li>Group the remaining events into turns (each turn starts at a user message).</li>
 * <li>If the turn count is within {@code maxTurns}, return unchanged.</li>
 * <li>Cover the oldest turns until only {@code maxTurns} remain and preserve those. The
 * log is not changed: the service records the decision as an appended compaction
 * boundary, and the view from then on is the pinned events followed by the preserved
 * turns.</li>
 * </ol>
 *
 * <h2>No-op condition</h2>
 * <p>
 * If the session has at most {@code maxTurns} turns nothing is covered and
 * {@link CompactionResult#unchanged()} is returned; no boundary is written. A superseded
 * stored system message is covered only when a later pass cuts.
 *
 * @author Christian Tzolov
 * @since 0.1.0
 * @see TurnCountTrigger
 */
public final class TurnWindowCompactionStrategy implements CompactionStrategy {

	/** Default number of complete turns preserved after compaction. */
	public static final int DEFAULT_MAX_TURNS = 10;

	private static final String STRATEGY_NAME = "turn-window";

	private final int maxTurns;

	private final TokenCountEstimator tokenCountEstimator;

	private TurnWindowCompactionStrategy(int maxTurns, TokenCountEstimator tokenCountEstimator) {
		Assert.isTrue(maxTurns > 0, "maxTurns must be greater than 0");
		Assert.notNull(tokenCountEstimator, "tokenCountEstimator must not be null");
		this.maxTurns = maxTurns;
		this.tokenCountEstimator = tokenCountEstimator;
	}

	@Override
	public CompactionResult compact(CompactionRequest request) {
		Assert.notNull(request, "request must not be null");

		List<SessionEvent> events = request.events();

		// 1. Separate the real events from what is pinned: the latest stored system message
		// and the synthetic summary a previous compaction left
		List<SessionEvent> pinnedSystem = CompactionUtils.pinnedSystemEvents(events);
		List<SessionEvent> synthetic = CompactionUtils.syntheticEvents(events);
		List<SessionEvent> real = CompactionUtils.compactableEvents(events);
		ToIntFunction<SessionEvent> tokens = e -> this.tokenCountEstimator.estimate(CompactionUtils.formatEvent(e));

		// 2. Any preamble events before the first user message (e.g. pre-seeded tool
		// context) are pinned too: kept verbatim, ahead of the turns.
		int firstUserIdx = 0;
		while (firstUserIdx < real.size() && !real.get(firstUserIdx).isTurnStart()) {
			firstUserIdx++;
		}
		List<SessionEvent> preamble = real.subList(0, firstUserIdx);
		List<SessionEvent> afterPreamble = real.subList(firstUserIdx, real.size());

		// 3. Group into turns — each turn starts at a user message
		List<List<SessionEvent>> turns = groupIntoTurns(afterPreamble);

		// 4. No-op if within budget
		if (turns.size() <= this.maxTurns) {
			return CompactionResult.unchanged();
		}

		// 5. Cover the oldest turns: the cut is the first event of the oldest kept turn
		int cutIndex = real.indexOf(turns.get(turns.size() - this.maxTurns).get(0));
		List<SessionEvent> pinned = CompactionUtils.concat(CompactionUtils.concat(pinnedSystem, synthetic), preamble);
		return CompactionUtils.cut(events, pinned, real, cutIndex, List.of(), 0, tokens, STRATEGY_NAME);
	}

	/**
	 * Groups a flat list of events into turns. Each turn starts with a
	 * {@link MessageType#USER} event. Assumes {@code events} begins with a user message
	 * (preamble has already been stripped).
	 */
	private static List<List<SessionEvent>> groupIntoTurns(List<SessionEvent> events) {
		List<List<SessionEvent>> turns = new ArrayList<>();
		List<SessionEvent> currentTurn = null;

		for (SessionEvent event : events) {
			if (event.isTurnStart()) {
				if (currentTurn != null) {
					turns.add(currentTurn);
				}
				currentTurn = new ArrayList<>();
			}
			if (currentTurn != null) {
				currentTurn.add(event);
			}
		}
		if (currentTurn != null && !currentTurn.isEmpty()) {
			turns.add(currentTurn);
		}
		return turns;
	}

	public int getMaxTurns() {
		return this.maxTurns;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private int maxTurns = DEFAULT_MAX_TURNS;

		private TokenCountEstimator tokenCountEstimator = new JTokkitTokenCountEstimator();

		private Builder() {
		}

		public Builder maxTurns(int maxTurns) {
			Assert.isTrue(maxTurns > 0, "maxTurns must be greater than 0");
			this.maxTurns = maxTurns;
			return this;
		}

		public Builder tokenCountEstimator(TokenCountEstimator tokenCountEstimator) {
			Assert.notNull(tokenCountEstimator, "tokenCountEstimator must not be null");
			this.tokenCountEstimator = tokenCountEstimator;
			return this;
		}

		public TurnWindowCompactionStrategy build() {
			return new TurnWindowCompactionStrategy(this.maxTurns, this.tokenCountEstimator);
		}
	}

}
