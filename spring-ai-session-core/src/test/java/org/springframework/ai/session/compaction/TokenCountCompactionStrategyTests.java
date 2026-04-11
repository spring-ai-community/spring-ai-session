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

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.MediaContent;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.tokenizer.TokenCountEstimator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link TokenCountCompactionStrategy}.
 */
class TokenCountCompactionStrategyTests {

	private static final String SESSION_ID = "test-session";

	/**
	 * Deterministic estimator: each character counts as one token.
	 */
	private static final TokenCountEstimator CHAR_ESTIMATOR = new TokenCountEstimator() {
		@Override
		public int estimate(String text) {
			return (text != null) ? text.length() : 0;
		}

		@Override
		public int estimate(MediaContent content) {
			return 0;
		}

		@Override
		public int estimate(Iterable<MediaContent> messages) {
			return 0;
		}
	};

	@Test
	void noOpWhenAllEventsFitWithinBudget() {
		// budget = 100 tokens, events are tiny
		TokenCountCompactionStrategy strategy = new TokenCountCompactionStrategy(100, CHAR_ESTIMATOR);
		CompactionRequest request = requestWith(turn("hi", "hello"));

		CompactionResult result = strategy.compact(request);

		assertThat(result.archivedEvents()).isEmpty();
		assertThat(result.eventsRemoved()).isEqualTo(0);
		assertThat(result.compactedEvents()).hasSize(2);
	}

	@Test
	void archivesEventsExceedingBudget() {
		// Each event costs exactly its text length in tokens.
		// Budget = 4 tokens. Turn1 = "u1"(2) + "a1"(2) = 4 tokens.
		// Turn2 = "u2"(2) + "a2"(2) = 4 tokens.
		// Total = 8 tokens; budget = 4 → keep only the newest 4 tokens (turn2).
		TokenCountCompactionStrategy strategy = new TokenCountCompactionStrategy(4, CHAR_ESTIMATOR);
		CompactionRequest request = requestWith(turn("u1", "a1"), turn("u2", "a2"));

		CompactionResult result = strategy.compact(request);

		assertThat(result.compactedEvents()).hasSize(2);
		assertThat(result.compactedEvents().get(0).getMessage().getText()).isEqualTo("u2");
		assertThat(result.compactedEvents().get(1).getMessage().getText()).isEqualTo("a2");
		assertThat(result.archivedEvents()).hasSize(2);
	}

	/**
	 * Verifies that scanning stops at the first oversize event so the kept window is a
	 * contiguous suffix, not a sparse selection of individually-fitting events.
	 * <p>
	 * Events (newest-to-oldest scan): u2(2) fits; a1_huge(24) exceeds budget → stop.
	 * Kept: [u2]. Archived: [u1, a1_huge]. u2 is a USER event so no turn-boundary snap is
	 * needed.
	 */
	@Test
	void stopsAtFirstOversizeEventKeepsContiguousSuffix() {
		TokenCountCompactionStrategy strategy = new TokenCountCompactionStrategy(6, CHAR_ESTIMATOR);

		List<SessionEvent> events = new ArrayList<>();
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage("u1")).build()); // 2
																											// tokens
		events.add(SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(new AssistantMessage("a1_very_large_text_here"))
			.build()); // 24 tokens — stops scan
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage("u2")).build()); // 2
																											// tokens

		CompactionResult result = strategy.compact(requestWith(events));

		assertThat(result.compactedEvents()).hasSize(1);
		assertThat(result.compactedEvents().get(0).getMessage().getText()).isEqualTo("u2");
		assertThat(result.archivedEvents()).hasSize(2);
		assertThat(result.archivedEvents().get(0).getMessage().getText()).isEqualTo("u1");
		assertThat(result.archivedEvents().get(1).getMessage().getText()).isEqualTo("a1_very_large_text_here");
	}

	/**
	 * The kept window must never begin with an ASSISTANT event — if the contiguous suffix
	 * starts on a non-USER event the turn-boundary snap advances to the next USER message
	 * (or produces an empty kept set).
	 * <p>
	 * Events: u1(2) + a1(2) + u2_longtxt(10) + a2(2). Budget = 4 tokens. Backwards scan:
	 * a2(2) fits (used=2); u2_longtxt(10) → 12 &gt; 4, stop. Raw kept = [a2]. Snap: a2 is
	 * ASSISTANT, no USER follows → kept becomes empty.
	 */
	@Test
	void neverKeepsPartialTurn_snapsToTurnBoundary() {
		TokenCountCompactionStrategy strategy = new TokenCountCompactionStrategy(4, CHAR_ESTIMATOR);

		List<SessionEvent> events = new ArrayList<>();
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage("u1")).build()); // 2
																											// tokens
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new AssistantMessage("a1")).build()); // 2
																												// tokens
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage("u2_longtxt")).build()); // 10
																													// tokens
																													// —
																													// stops
																													// scan
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new AssistantMessage("a2")).build()); // 2
																												// tokens

		CompactionResult result = strategy.compact(requestWith(events));

		// All events archived after snap removes the orphaned assistant reply
		assertThat(result.compactedEvents()).isEmpty();
		assertThat(result.archivedEvents()).hasSize(4);
	}

	@Test
	void keptWindowAlwaysStartsAtUserMessage() {
		// Force a situation where the raw budget cut lands inside turn2 (at an assistant
		// message). The strategy must snap forward to the start of the next turn.
		// Turn1: "aaaa"(4) user + "bbbb"(4) assistant = 8 tokens
		// Turn2: "cccc"(4) user + "dddd"(4) assistant = 8 tokens
		// Turn3: "eeee"(4) user + "ffff"(4) assistant = 8 tokens
		// Budget = 6 tokens → only "ffff"(4) fits + "eeee"(4) = 8 > 6, so only ffff(4)
		// fits
		// Raw cutIndex = 6 - 1 = 5 → real[5] = ffff (ASSISTANT)
		// Snap: real[5]=ASSISTANT, real[6] doesn't exist → cutIndex becomes 6 (past end)
		// Result: no real events kept (all archived), only synthetics (none here)
		TokenCountCompactionStrategy strategy = new TokenCountCompactionStrategy(6, CHAR_ESTIMATOR);
		CompactionRequest request = requestWith(turn("aaaa", "bbbb"), turn("cccc", "dddd"), turn("eeee", "ffff"));

		CompactionResult result = strategy.compact(request);

		// The compacted list must not start with an ASSISTANT event
		if (!result.compactedEvents().isEmpty()) {
			SessionEvent first = result.compactedEvents().get(0);
			boolean isSyntheticOrUser = first.isSynthetic()
					|| first.getMessage() instanceof org.springframework.ai.chat.messages.UserMessage;
			assertThat(isSyntheticOrUser).isTrue();
		}
	}

	@Test
	void syntheticEventsAreAlwaysPreservedAndPlacedFirst() {
		// Budget so small no real event fits
		TokenCountCompactionStrategy strategy = new TokenCountCompactionStrategy(1, CHAR_ESTIMATOR);

		List<SessionEvent> events = new ArrayList<>();
		events.add(SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(new UserMessage("Summarize the conversation we had so far."))
			.metadata(SessionEvent.METADATA_SYNTHETIC, true)
			.metadata(SessionEvent.METADATA_COMPACTION_SOURCE, "test")
			.build());
		events.add(SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(new AssistantMessage("summary"))
			.metadata(SessionEvent.METADATA_SYNTHETIC, true)
			.metadata(SessionEvent.METADATA_COMPACTION_SOURCE, "test")
			.build());
		events.addAll(turn("hello", "world"));

		CompactionResult result = strategy.compact(requestWith(events));

		// Summary turn: get(0)=USER shadow prompt, get(1)=ASSISTANT summary text
		assertThat(result.compactedEvents().get(0).isSynthetic()).isTrue();
		assertThat(result.compactedEvents().get(1).isSynthetic()).isTrue();
		assertThat(result.compactedEvents().get(1).getMessage().getText()).isEqualTo("summary");
	}

	@Test
	void maxTokensZeroIsRejected() {
		assertThatThrownBy(() -> new TokenCountCompactionStrategy(0, CHAR_ESTIMATOR))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("maxTokens must be greater than 0");
	}

	@Test
	void nullRequestIsRejected() {
		TokenCountCompactionStrategy strategy = new TokenCountCompactionStrategy(100, CHAR_ESTIMATOR);
		assertThatThrownBy(() -> strategy.compact(null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void emptySessionReturnsUnchanged() {
		TokenCountCompactionStrategy strategy = new TokenCountCompactionStrategy(100, CHAR_ESTIMATOR);
		CompactionResult result = strategy.compact(requestWith(List.of()));

		assertThat(result.compactedEvents()).isEmpty();
		assertThat(result.archivedEvents()).isEmpty();
	}

	// --- helpers ---

	private List<SessionEvent> turn(String userText, String assistantText) {
		return List.of(SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage(userText)).build(),
				SessionEvent.builder().sessionId(SESSION_ID).message(new AssistantMessage(assistantText)).build());
	}

	@SafeVarargs
	private CompactionRequest requestWith(List<SessionEvent>... turns) {
		List<SessionEvent> all = new ArrayList<>();
		for (List<SessionEvent> turn : turns) {
			all.addAll(turn);
		}
		return requestWith(all);
	}

	private CompactionRequest requestWith(List<SessionEvent> events) {
		Session session = Session.builder().id(SESSION_ID).userId("test-user").build();
		return CompactionRequest.of(session, new ArrayList<>(events));
	}

}
