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
 * Compaction strategy that retains events within a maximum estimated token budget. Token
 * count is approximated with the help of a {@link TokenCountEstimator}.
 *
 * <h3>Algorithm</h3>
 * <ol>
 * <li>Separate synthetic summary events — they are always preserved and placed first in
 * the result. Their token cost is deducted from the budget before real events are
 * considered, so a large prior compaction summary reduces the space available for real
 * events.</li>
 * <li>Walk real events from newest to oldest, accumulating cost until the budget is
 * exhausted. Stops at the first event that would exceed the remaining budget, producing a
 * contiguous kept window (a suffix of the real-event list). Skipping oversize events and
 * continuing would produce non-contiguous gaps that break conversation coherence.</li>
 * <li>Drop any leading kept events that are not {@link MessageType#USER} messages. This
 * guarantees the kept window always starts at a turn boundary, so an assistant reply or
 * tool result is never kept without the user message that originated its turn.</li>
 * <li>Return: {@code [synthetic events] + [kept events]}.</li>
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

		// Always keep synthetic events
		List<SessionEvent> synthetic = events.stream().filter(SessionEvent::isSynthetic).toList();
		List<SessionEvent> real = events.stream().filter(e -> !e.isSynthetic()).toList();

		int syntheticTokens = synthetic.stream()
			.map(e -> e.getMessage().getText())
			.filter(t -> t != null)
			.mapToInt(t -> this.tokenCountEstimator.estimate(t))
			.sum();

		int remainingBudget = this.maxTokens - syntheticTokens;

		// Walk from newest to oldest, accumulating events until the budget is reached.
		// Stop at the first event that would exceed the remaining budget so the kept
		// window is always a contiguous suffix — keeping older events after skipping a
		// large middle event would produce gaps that break conversation coherence.
		int cutIndex = real.size();
		int usedTokens = 0;
		for (int i = real.size() - 1; i >= 0; i--) {
			String text = real.get(i).getMessage().getText();
			int tokens = (text != null) ? this.tokenCountEstimator.estimate(text) : 0;
			if (usedTokens + tokens <= remainingBudget) {
				usedTokens += tokens;
				cutIndex = i;
			}
			else {
				break;
			}
		}

		// Build kept and archived lists in chronological order
		List<SessionEvent> kept = new ArrayList<>(real.subList(cutIndex, real.size()));
		List<SessionEvent> archived = new ArrayList<>(real.subList(0, cutIndex));

		// Ensure the kept window starts at a turn boundary (USER message).
		// Drop any leading kept events that are not USER messages — keeping an assistant
		// reply without its originating user message breaks conversation semantics.
		while (!kept.isEmpty() && kept.get(0).getMessageType() != MessageType.USER) {
			archived.add(kept.remove(0));
		}

		if (archived.isEmpty()) {
			return new CompactionResult(events, List.of(), 0);
		}

		List<SessionEvent> compacted = new ArrayList<>(synthetic);
		compacted.addAll(kept);

		int tokensRemoved = archived.stream()
			.map(e -> e.getMessage().getText())
			.filter(t -> t != null)
			.mapToInt(t -> this.tokenCountEstimator.estimate(t))
			.sum();

		return new CompactionResult(compacted, archived, tokensRemoved);
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
			this.maxTokens = maxTokens;
			return this;
		}

		public Builder tokenCountEstimator(TokenCountEstimator tokenCountEstimator) {
			this.tokenCountEstimator = tokenCountEstimator;
			return this;
		}

		public TokenCountCompactionStrategy build() {
			return new TokenCountCompactionStrategy(this.maxTokens, this.tokenCountEstimator);
		}

	}

}
