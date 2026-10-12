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

import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.MediaContent;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.tokenizer.TokenCountEstimator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.ai.session.compaction.CompactionTestSupport.assistant;
import static org.springframework.ai.session.compaction.CompactionTestSupport.covered;
import static org.springframework.ai.session.compaction.CompactionTestSupport.events;
import static org.springframework.ai.session.compaction.CompactionTestSupport.kept;
import static org.springframework.ai.session.compaction.CompactionTestSupport.request;
import static org.springframework.ai.session.compaction.CompactionTestSupport.summaryTurn;
import static org.springframework.ai.session.compaction.CompactionTestSupport.system;
import static org.springframework.ai.session.compaction.CompactionTestSupport.toolTurn;
import static org.springframework.ai.session.compaction.CompactionTestSupport.turn;
import static org.springframework.ai.session.compaction.CompactionTestSupport.user;

/**
 * Tests for {@link TokenCountCompactionStrategy}.
 */
class TokenCountCompactionStrategyTests {

	/** Deterministic estimator: each character of the formatted event counts as one token. */
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

	private static TokenCountCompactionStrategy maxTokens(int maxTokens) {
		return TokenCountCompactionStrategy.builder().maxTokens(maxTokens).tokenCountEstimator(CHAR_ESTIMATOR).build();
	}

	@Test
	void noOpWhenAllEventsFitWithinBudget() {
		CompactionResult result = maxTokens(100).compact(request(turn("hi", "hello")));

		assertThat(result.isUnchanged()).isTrue();
	}

	@Test
	void noOpWhenAllEventsFitEvenIfTheViewStartsMidTurn() {
		// Snapping the cut to the first user message must not cover a preamble when
		// nothing needs to leave the view
		CompactionResult result = maxTokens(100)
			.compact(request(events(List.of(assistant("welcome")), turn("hi", "hello"))));

		assertThat(result.isUnchanged()).isTrue();
	}

	@Test
	void coversEventsExceedingBudget() {
		// "User: u1"(8) + "Assistant: a1"(13) = 21 per turn. Budget 25: turn2 fits, both don't.
		CompactionResult result = maxTokens(25).compact(request(events(turn("u1", "a1"), turn("u2", "a2"))));

		assertThat(kept(result)).containsExactly("u2", "a2");
		assertThat(covered(result)).containsExactly("u1", "a1");
		assertThat(result.strategy()).isEqualTo("token-count");
	}

	@Test
	void stopsAtFirstOversizeEventKeepsContiguousSuffix() {
		// "User: u2"(8) fits in 10; "Assistant: a1_very_large_text_here"(34) stops the scan
		List<SessionEvent> view = List.of(user("u1"), assistant("a1_very_large_text_here"), user("u2"));

		CompactionResult result = maxTokens(10).compact(request(view));

		assertThat(kept(result)).containsExactly("u2");
		assertThat(covered(result)).containsExactly("u1", "a1_very_large_text_here");
	}

	@Test
	void neverKeepsPartialTurnAndKeepsTheOversizeLastTurn() {
		// The raw cut lands on a2 and snaps past the end; the last turn is kept anyway
		List<SessionEvent> view = List.of(user("u1"), assistant("a1"), user("u2_longtxt"), assistant("a2"));

		CompactionResult result = maxTokens(4).compact(request(view));

		assertThat(covered(result)).containsExactly("u1", "a1");
		assertThat(kept(result)).containsExactly("u2_longtxt", "a2");
	}

	@Test
	void keptWindowAlwaysStartsAtUserMessage() {
		CompactionResult result = maxTokens(6)
			.compact(request(events(turn("aaaa", "bbbb"), turn("cccc", "dddd"), turn("eeee", "ffff"))));

		SessionEvent first = result.keptEvents().get(0);
		assertThat(first.isSynthetic() || first.getMessage() instanceof UserMessage).isTrue();
	}

	@Test
	void aPriorSummaryIsPinnedFirstEvenWhenItAloneExceedsTheBudget() {
		CompactionResult result = maxTokens(1)
			.compact(request(events(summaryTurn("summary"), turn("hello", "world"), turn("yo", "ok"))));

		assertThat(result.pinnedEvents()).hasSize(2).allMatch(SessionEvent::isSynthetic);
		assertThat(kept(result)).containsExactly("Summarize the conversation we had so far.", "summary", "yo", "ok");
		assertThat(covered(result)).containsExactly("hello", "world");
	}

	@Test
	void maxTokensZeroIsRejected() {
		assertThatThrownBy(() -> TokenCountCompactionStrategy.builder().maxTokens(0).build())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("maxTokens must be greater than 0");
	}

	@Test
	void nullRequestIsRejected() {
		assertThatThrownBy(() -> maxTokens(100).compact(null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void emptySessionReturnsUnchanged() {
		assertThat(maxTokens(100).compact(request(List.of())).isUnchanged()).isTrue();
	}

	@Test
	void toolEventsCountTowardBudget() {
		CompactionResult result = maxTokens(40)
			.compact(request(events(toolTurn("What's the weather?"), turn("Thanks", "You're welcome"))));

		assertThat(result.coveredEvents()).isNotEmpty();
		assertThat(result.tokensEstimatedSaved()).isGreaterThan(0);
	}

	@Test
	void oversizeLastTurnIsKeptInsteadOfCoveringEverything() {
		String big = "x".repeat(100);
		CompactionResult result = maxTokens(30).compact(request(events(turn("hi", "ok"), turn("big question", big))));

		assertThat(covered(result)).containsExactly("hi", "ok");
		assertThat(kept(result)).containsExactly("big question", big);
	}

	@Test
	void singleOversizeTurnIsNotCompacted() {
		assertThat(maxTokens(30).compact(request(turn("big question", "x".repeat(100)))).isUnchanged()).isTrue();
	}

	@Test
	void storedSystemMessageIsPinnedFirstAndItsTokensComeOffTheBudget() {
		// "System: sys" = 11 tokens; each turn = 21. Without the system message both
		// turns (42) fit in 45; with it only 34 remain, so the older turn is covered.
		List<SessionEvent> view = events(List.of(system("sys")), turn("hi", "ok"), turn("yo", "ok"));

		CompactionResult result = maxTokens(45).compact(request(view));

		assertThat(covered(result)).containsExactly("hi", "ok");
		assertThat(kept(result)).containsExactly("sys", "yo", "ok");
	}

	@Test
	void pinnedSystemMessageOverTheBudgetStillKeepsTheNewestTurn() {
		List<SessionEvent> view = events(List.of(system("a system prompt that is longer than the whole token budget")),
				turn("q1", "r1"), turn("q2", "r2"));

		CompactionResult result = maxTokens(40).compact(request(view));

		assertThat(covered(result)).containsExactly("q1", "r1");
		assertThat(kept(result)).containsExactly("a system prompt that is longer than the whole token budget", "q2",
				"r2");
	}

	@Test
	void compactionWithoutUserMessageCoversNothing() {
		List<SessionEvent> view = new ArrayList<>();
		for (int i = 1; i <= 6; i++) {
			view.add(assistant("step " + i));
			view.add(assistant("answer " + i));
		}

		assertThat(maxTokens(10).compact(request(view)).isUnchanged()).isTrue();
	}

}
