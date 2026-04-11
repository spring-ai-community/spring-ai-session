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
 * <li>Compute a raw cut index so that at most {@code maxEvents - syntheticCount} real
 * events are kept.</li>
 * <li>Snap the raw cut index forward to the nearest
 * {@link org.springframework.ai.chat.messages.MessageType#USER} event so the kept window
 * always starts at a turn boundary. This prevents keeping an assistant reply or tool
 * result without the user message that originated its turn.</li>
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

	public SlidingWindowCompactionStrategy() {
		this(DEFAULT_MAX_EVENTS);
	}

	public SlidingWindowCompactionStrategy(int maxEvents) {
		this(maxEvents, new JTokkitTokenCountEstimator());
	}

	public SlidingWindowCompactionStrategy(int maxEvents, TokenCountEstimator tokenCountEstimator) {
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
		int slotsForReal = this.maxEvents;

		// No-op if real events fit within the available slots
		if (real.size() <= slotsForReal) {
			return new CompactionResult(events, List.of(), 0);
		}

		// Raw cut point: index where the kept window would start based on count alone
		int rawCutIndex = real.size() - slotsForReal;

		// Snap forward to the nearest turn start (USER message) so we never keep a
		// partial turn — e.g. an assistant reply without its originating user message.
		int cutIndex = CompactionUtils.snapToTurnStart(real, rawCutIndex);

		List<SessionEvent> keptReal = new ArrayList<>(real.subList(cutIndex, real.size()));
		List<SessionEvent> removedReal = real.subList(0, cutIndex);

		List<SessionEvent> compacted = new ArrayList<>(synthetic);
		compacted.addAll(keptReal);

		int tokensRemoved = removedReal.stream()
			.map(e -> e.getMessage().getText())
			.filter(t -> t != null)
			.mapToInt(this.tokenCountEstimator::estimate)
			.sum();

		return new CompactionResult(compacted, removedReal, tokensRemoved);
	}

	public int getMaxEvents() {
		return this.maxEvents;
	}

}
