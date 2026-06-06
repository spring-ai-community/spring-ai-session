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

import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.util.Assert;

/**
 * Compaction strategy that retains only the last {@code maxEvents} real events, always
 * cutting on a turn boundary to preserve conversation semantics.
 *
 * <h3>Algorithm</h3>
 * <ol>
 * <li>Separate synthetic summary events — they are always preserved and placed first in
 * the result.</li>
 * <li>Compute a raw cut index based on root (non-branch) real events only. Branch events
 * produced by sub-agents do not consume slots from the {@code maxEvents} budget — they
 * are always included with their enclosing root turn.</li>
 * <li>Snap the raw cut index forward to the nearest root-level
 * {@link org.springframework.ai.chat.messages.MessageType#USER} event so the kept window
 * always starts at a turn boundary. Sub-agent USER messages are skipped because they are
 * turn-internal, not turn starts.</li>
 * <li>Return: {@code [synthetic summaries] + [kept real events]}.</li>
 * </ol>
 *
 * <h3>No-op condition</h3>
 * <p>
 * If the number of real events does not exceed the available slots no events are archived
 * and the session is returned unchanged.
 *
 * @author Christian Tzolov
 * @since 2.0.0
 * @see TurnWindowCompactionStrategy
 */
public final class SlidingWindowCompactionStrategy implements CompactionStrategy {

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
	public CompactionResult compact(CompactionRequest context) {

		Assert.notNull(context, "context must not be null");
		Assert.notNull(context.session(), "session must not be null");

		List<SessionEvent> events = context.events();

		// Separate synthetic summary events (always preserved, always first)
		List<SessionEvent> synthetic = events.stream().filter(SessionEvent::isSynthetic).toList();
		List<SessionEvent> real = events.stream().filter(e -> !e.isSynthetic()).toList();

		// maxEvents controls the real-events window only; synthetic summary events are
		// always preserved on top and do not consume slots from the real-event budget.
		// Branch events produced inside sub-agent sessions also do not consume slots —
		// they are always included with their enclosing root turn.
		int slotsForReal = this.maxEvents;

		// Count only root (non-branch) real events to determine whether compaction is needed
		// and where to place the raw cut. Branch events tagged with a non-null branch are
		// turn-internal and are always carried along with their enclosing root turn.
		long rootEventCount = real.stream().filter(SessionEvent::isRootEvent).count();

		// No-op if root events fit within the available slots
		if (rootEventCount <= slotsForReal) {
			return new CompactionResult(events, List.of(), 0);
		}

		// Find the index in 'real' just after the last root event to archive.
		// Walk forward counting root events; place the raw cut right after the
		// (rootEventCount - slotsForReal)-th root event so snapToTurnStart can advance
		// it to the next root-level USER event.
		long rootEventsToArchive = rootEventCount - slotsForReal;
		int rawCutIndex = 0;
		long rootSeen = 0;
		for (int i = 0; i < real.size(); i++) {
			if (real.get(i).isRootEvent()) {
				rootSeen++;
				if (rootSeen == rootEventsToArchive) {
					rawCutIndex = i + 1;
					break;
				}
			}
		}

		// Snap forward to the nearest turn start (USER message) so we never keep a
		// partial turn — e.g. an assistant reply without its originating user message.
		int cutIndex = CompactionUtils.snapToTurnStart(real, rawCutIndex);

		List<SessionEvent> keptReal = new ArrayList<>(real.subList(cutIndex, real.size()));
		List<SessionEvent> removedReal = real.subList(0, cutIndex);

		List<SessionEvent> compacted = new ArrayList<>(synthetic);
		compacted.addAll(keptReal);

		int tokensRemoved = removedReal.stream()
			.mapToInt(e -> this.tokenCountEstimator.estimate(CompactionUtils.formatEvent(e)))
			.sum();

		return new CompactionResult(compacted, removedReal, tokensRemoved);
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
			this.maxEvents = maxEvents;
			return this;
		}

		public Builder tokenCountEstimator(TokenCountEstimator tokenCountEstimator) {
			this.tokenCountEstimator = tokenCountEstimator;
			return this;
		}

		public SlidingWindowCompactionStrategy build() {
			return new SlidingWindowCompactionStrategy(this.maxEvents, this.tokenCountEstimator);
		}

	}

}
