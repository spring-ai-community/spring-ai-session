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

import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.util.Assert;

/**
 * Compaction strategy that retains only the last {@code maxTurns} complete
 * <em>turns</em>, discarding older ones.
 *
 * <h3>What is a turn?</h3>
 * <p>
 * A turn begins with a {@link MessageType#USER} message and ends just before the next
 * user message. It includes every event the agent produced in response: assistant
 * messages, tool calls, tool results, and any additional assistant follow-ups. A turn is
 * the atomic unit of a conversation.
 *
 * <h3>Algorithm</h3>
 * <ol>
 * <li>Strip out synthetic summary events — they are always placed first in the
 * result.</li>
 * <li>Collect any events that appear before the first user message (rare, but possible
 * for pre-seeded tool state) — these are preserved as preamble.</li>
 * <li>Group the remaining events into turns (each turn starts at a user message).</li>
 * <li>If the turn count is within {@code maxTurns}, return unchanged.</li>
 * <li>Archive the oldest turns until only {@code maxTurns} remain.</li>
 * <li>Return: {@code [synthetic summaries] + [preamble] + [kept turns]}.</li>
 * </ol>
 *
 * <h3>No-op condition</h3>
 * <p>
 * If the session has fewer turns than {@code maxTurns}, no events are removed.
 *
 * @author Christian Tzolov
 * @since 2.0.0
 * @see TurnCountTrigger
 */
public final class TurnWindowCompactionStrategy implements CompactionStrategy {

	/** Default number of complete turns preserved after compaction. */
	public static final int DEFAULT_MAX_TURNS = 10;

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

		// 1. Separate synthetic summary events — always preserved, always first
		List<SessionEvent> synthetic = events.stream().filter(SessionEvent::isSynthetic).toList();
		List<SessionEvent> real = events.stream().filter(e -> !e.isSynthetic()).toList();

		// 2. Collect any preamble events that appear before the first user message
		// (e.g., pre-seeded tool context). These are kept verbatim.
		List<SessionEvent> preamble = new ArrayList<>();
		int firstUserIdx = 0;
		while (firstUserIdx < real.size() && real.get(firstUserIdx).getMessageType() != MessageType.USER) {
			preamble.add(real.get(firstUserIdx));
			firstUserIdx++;
		}
		List<SessionEvent> afterPreamble = real.subList(firstUserIdx, real.size());

		// 3. Group into turns — each turn starts at a user message
		List<List<SessionEvent>> turns = groupIntoTurns(afterPreamble);

		// 4. No-op if within budget
		if (turns.size() <= this.maxTurns) {
			return new CompactionResult(events, List.of(), 0);
		}

		// 5. Archive oldest turns
		int toArchiveCount = turns.size() - this.maxTurns;
		List<List<SessionEvent>> archivedTurns = turns.subList(0, toArchiveCount);
		List<List<SessionEvent>> keptTurns = turns.subList(toArchiveCount, turns.size());

		List<SessionEvent> archived = archivedTurns.stream().flatMap(List::stream).toList();
		List<SessionEvent> kept = keptTurns.stream().flatMap(List::stream).toList();

		// 6. Assemble result: [synthetics] + [preamble] + [kept turns]
		List<SessionEvent> compacted = new ArrayList<>(synthetic);
		compacted.addAll(preamble);
		compacted.addAll(kept);

		int tokensArchived = archived.stream()
			.map(e -> e.getMessage().getText())
			.filter(t -> t != null)
			.mapToInt(this.tokenCountEstimator::estimate)
			.sum();

		return new CompactionResult(compacted, archived, tokensArchived);
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
			if (event.getMessageType() == MessageType.USER) {
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
