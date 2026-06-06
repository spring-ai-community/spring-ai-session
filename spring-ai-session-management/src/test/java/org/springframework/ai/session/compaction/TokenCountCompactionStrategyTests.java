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
import org.springframework.ai.chat.messages.ToolResponseMessage;
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
		TokenCountCompactionStrategy strategy = TokenCountCompactionStrategy.builder()
			.maxTokens(100)
			.tokenCountEstimator(CHAR_ESTIMATOR)
			.build();
		CompactionRequest request = requestWith(turn("hi", "hello"));

		CompactionResult result = strategy.compact(request);

		assertThat(result.archivedEvents()).isEmpty();
		assertThat(result.eventsRemoved()).isEqualTo(0);
		assertThat(result.compactedEvents()).hasSize(2);
	}

	@Test
	void archivesEventsExceedingBudget() {
		// CHAR_ESTIMATOR counts formatted-text characters (formatEvent output), not raw
		// getText() lengths. "User: u1"(8) + "Assistant: a1"(13) = 21 per turn.
		// Budget = 25 → turn2 fits (21 ≤ 25) but turn1+turn2 does not (42 > 25).
		TokenCountCompactionStrategy strategy = TokenCountCompactionStrategy.builder()
			.maxTokens(25)
			.tokenCountEstimator(CHAR_ESTIMATOR)
			.build();
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
	 * Formatted costs: "User: u2"(8) fits in budget 10; "Assistant: a1_very_large_text_here"(34)
	 * would exceed it → scan stops. Kept: [u2]. Archived: [u1, a1_huge]. u2 is a USER
	 * event so no turn-boundary snap is needed.
	 */
	@Test
	void stopsAtFirstOversizeEventKeepsContiguousSuffix() {
		TokenCountCompactionStrategy strategy = TokenCountCompactionStrategy.builder()
			.maxTokens(10)
			.tokenCountEstimator(CHAR_ESTIMATOR)
			.build();

		List<SessionEvent> events = new ArrayList<>();
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage("u1")).build()); // 8 tokens
		events.add(SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(new AssistantMessage("a1_very_large_text_here"))
			.build()); // 34 tokens — stops scan
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage("u2")).build()); // 8 tokens

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
		TokenCountCompactionStrategy strategy = TokenCountCompactionStrategy.builder()
			.maxTokens(4)
			.tokenCountEstimator(CHAR_ESTIMATOR)
			.build();

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
		TokenCountCompactionStrategy strategy = TokenCountCompactionStrategy.builder()
			.maxTokens(6)
			.tokenCountEstimator(CHAR_ESTIMATOR)
			.build();
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
		TokenCountCompactionStrategy strategy = TokenCountCompactionStrategy.builder()
			.maxTokens(1)
			.tokenCountEstimator(CHAR_ESTIMATOR)
			.build();

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
		assertThatThrownBy(() -> TokenCountCompactionStrategy.builder().maxTokens(0).tokenCountEstimator(CHAR_ESTIMATOR).build())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("maxTokens must be greater than 0");
	}

	@Test
	void nullRequestIsRejected() {
		TokenCountCompactionStrategy strategy = TokenCountCompactionStrategy.builder()
			.maxTokens(100)
			.tokenCountEstimator(CHAR_ESTIMATOR)
			.build();
		assertThatThrownBy(() -> strategy.compact(null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void emptySessionReturnsUnchanged() {
		TokenCountCompactionStrategy strategy = TokenCountCompactionStrategy.builder()
			.maxTokens(100)
			.tokenCountEstimator(CHAR_ESTIMATOR)
			.build();
		CompactionResult result = strategy.compact(requestWith(List.of()));

		assertThat(result.compactedEvents()).isEmpty();
		assertThat(result.archivedEvents()).isEmpty();
	}

	// --- branch-awareness ---

	@Test
	void snapSkipsBranchUserEventOnTurnBoundary() {
		// Events (CHAR_ESTIMATOR costs based on formatEvent output):
		//   u1-root "User: u1" = 8, a1-root "Assistant: a1" = 13
		//   u2-sub  "User: u2" = 8, a2-sub  "Assistant: a2" = 13  (branch="sub")
		//   u3-root "User: u3" = 8, a3-root "Assistant: a3" = 13
		//
		// Backwards scan with budget=40:
		//   a3-root(13) fits, u3-root(8) → 21 fits, a2-sub(13) → 34 fits,
		//   u2-sub(8)   → 42 > 40, stop  →  rawCutIndex = 3 (a2-sub)
		//
		// snapToTurnStart(real, 3):
		//   idx=3: a2-sub (branch → not root → skip)
		//   idx=4: u3-root (root USER → stop)
		// → cutIndex=4, kept=[u3-root, a3-root]
		//
		// Without branch-awareness the old snap would have stopped at u2-sub (branch USER),
		// leaving the kept window starting on a sub-agent message.
		TokenCountCompactionStrategy strategy = TokenCountCompactionStrategy.builder()
			.maxTokens(40)
			.tokenCountEstimator(CHAR_ESTIMATOR)
			.build();

		List<SessionEvent> events = new ArrayList<>();
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage("u1")).build());
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new AssistantMessage("a1")).build());
		events.add(SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(new UserMessage("u2"))
			.branch("sub")
			.build());
		events.add(SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(new AssistantMessage("a2"))
			.branch("sub")
			.build());
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage("u3")).build());
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new AssistantMessage("a3")).build());

		CompactionResult result = strategy.compact(requestWith(events));

		// Kept window must start at root USER u3, not branch USER u2
		assertThat(result.compactedEvents()).hasSize(2);
		assertThat(result.compactedEvents().get(0).getMessage().getText()).isEqualTo("u3");
		assertThat(result.compactedEvents().get(1).getMessage().getText()).isEqualTo("a3");
		assertThat(result.archivedEvents()).hasSize(4); // u1, a1, u2-sub, a2-sub
	}

	// --- tool call / tool response token counting ---

	@Test
	void toolEventsCountTowardBudget() {
		// Budget = 40 chars (CHAR_ESTIMATOR: 1 char = 1 token).
		// Formatted tool-call and tool-response texts are ~45-55 chars each, which
		// pushes the total over the 40-token limit and forces archiving turn 1.
		// Before the fix, both events returned null from getText() and cost 0 tokens,
		// so the entire session was kept without archiving anything.
		TokenCountCompactionStrategy strategy = TokenCountCompactionStrategy.builder()
			.maxTokens(40)
			.tokenCountEstimator(CHAR_ESTIMATOR)
			.build();

		List<SessionEvent> events = new ArrayList<>();
		events.add(
				SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage("What's the weather?")).build());
		events.add(SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(AssistantMessage.builder()
				.toolCalls(List
					.of(new AssistantMessage.ToolCall("call-1", "function", "get_weather", "{\"location\":\"Paris\"}")))
				.build())
			.build());
		events.add(SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(ToolResponseMessage.builder()
				.responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "get_weather", "{\"temp\":\"22C\"}")))
				.build())
			.build());
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage("Thanks")).build());
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new AssistantMessage("You're welcome")).build());

		CompactionResult result = strategy.compact(requestWith(events));

		assertThat(result.archivedEvents()).isNotEmpty();
		assertThat(result.tokensEstimatedSaved()).isGreaterThan(0);
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
