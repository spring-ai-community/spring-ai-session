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
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link TurnWindowCompactionStrategy}.
 */
class TurnWindowCompactionStrategyTests {

	private static final String SESSION_ID = "test-session";

	@Test
	void noOpWhenTurnsUnderLimit() {
		TurnWindowCompactionStrategy strategy = TurnWindowCompactionStrategy.builder().maxTurns(5).build();
		CompactionRequest request = requestWith(turn("q1", "a1"), turn("q2", "a2"), turn("q3", "a3"));

		CompactionResult result = strategy.compact(request);

		assertThat(result.archivedEvents()).isEmpty();
		assertThat(result.eventsRemoved()).isEqualTo(0);
		assertThat(result.compactedEvents()).hasSize(6); // 3 turns × 2 events each
	}

	@Test
	void noOpWhenTurnsAtExactLimit() {
		TurnWindowCompactionStrategy strategy = TurnWindowCompactionStrategy.builder().maxTurns(3).build();
		CompactionRequest request = requestWith(turn("q1", "a1"), turn("q2", "a2"), turn("q3", "a3"));

		CompactionResult result = strategy.compact(request);

		assertThat(result.archivedEvents()).isEmpty();
		assertThat(result.eventsRemoved()).isEqualTo(0);
	}

	@Test
	void archivesOldestTurnsWhenOverLimit() {
		TurnWindowCompactionStrategy strategy = TurnWindowCompactionStrategy.builder().maxTurns(2).build();
		// 4 turns, keep last 2 → archive first 2
		CompactionRequest request = requestWith(turn("q1", "a1"), turn("q2", "a2"), turn("q3", "a3"), turn("q4", "a4"));

		CompactionResult result = strategy.compact(request);

		// kept: turns 3 and 4 (4 events)
		assertThat(result.compactedEvents()).hasSize(4);
		assertThat(result.archivedEvents()).hasSize(4); // 2 archived turns × 2 events
		assertThat(result.eventsRemoved()).isEqualTo(4);

		// First kept event must be the user message of turn 3
		assertThat(result.compactedEvents().get(0).getMessage().getText()).isEqualTo("q3");
		assertThat(result.compactedEvents().get(1).getMessage().getText()).isEqualTo("a3");
		assertThat(result.compactedEvents().get(2).getMessage().getText()).isEqualTo("q4");
		assertThat(result.compactedEvents().get(3).getMessage().getText()).isEqualTo("a4");
	}

	@Test
	void syntheticEventsArePlacedFirstAndPreserved() {
		TurnWindowCompactionStrategy strategy = TurnWindowCompactionStrategy.builder().maxTurns(2).build();

		List<SessionEvent> events = new ArrayList<>();
		// One summary turn = 2 synthetic events (USER shadow + ASSISTANT summary)
		events.add(SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(new UserMessage("Summarize the conversation we had so far."))
			.metadata(SessionEvent.METADATA_SYNTHETIC, true)
			.metadata(SessionEvent.METADATA_COMPACTION_SOURCE, "test")
			.build());
		events.add(SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(new AssistantMessage("prior summary"))
			.metadata(SessionEvent.METADATA_SYNTHETIC, true)
			.metadata(SessionEvent.METADATA_COMPACTION_SOURCE, "test")
			.build());
		events.addAll(turn("q1", "a1"));
		events.addAll(turn("q2", "a2"));
		events.addAll(turn("q3", "a3"));
		events.addAll(turn("q4", "a4"));

		CompactionResult result = strategy.compact(requestWith(events));

		// Result: [s_user, s_assistant] + [turn3] + [turn4]
		assertThat(result.compactedEvents()).hasSize(6); // 2 synthetic + 2 turns × 2
		assertThat(result.compactedEvents().get(0).isSynthetic()).isTrue();
		assertThat(result.compactedEvents().get(1).isSynthetic()).isTrue();
		assertThat(result.compactedEvents().get(1).getMessage().getText()).isEqualTo("prior summary");
		assertThat(result.compactedEvents().get(2).getMessage().getText()).isEqualTo("q3");
	}

	@Test
	void multipleSyntheticEventsAllPreserved() {
		TurnWindowCompactionStrategy strategy = TurnWindowCompactionStrategy.builder().maxTurns(1).build();

		List<SessionEvent> events = new ArrayList<>();
		// Two prior summary turns = 4 synthetic events
		events.add(SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(new UserMessage("Summarize the conversation we had so far."))
			.metadata(SessionEvent.METADATA_SYNTHETIC, true)
			.metadata(SessionEvent.METADATA_COMPACTION_SOURCE, "test")
			.build());
		events.add(SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(new AssistantMessage("summary-1"))
			.metadata(SessionEvent.METADATA_SYNTHETIC, true)
			.metadata(SessionEvent.METADATA_COMPACTION_SOURCE, "test")
			.build());
		events.add(SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(new UserMessage("Summarize the conversation we had so far."))
			.metadata(SessionEvent.METADATA_SYNTHETIC, true)
			.metadata(SessionEvent.METADATA_COMPACTION_SOURCE, "test")
			.build());
		events.add(SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(new AssistantMessage("summary-2"))
			.metadata(SessionEvent.METADATA_SYNTHETIC, true)
			.metadata(SessionEvent.METADATA_COMPACTION_SOURCE, "test")
			.build());
		events.addAll(turn("q1", "a1"));
		events.addAll(turn("q2", "a2"));

		CompactionResult result = strategy.compact(requestWith(events));

		// Result: [s1_user, s1_assistant, s2_user, s2_assistant] + [turn2]
		assertThat(result.compactedEvents()).hasSize(6);
		assertThat(result.compactedEvents().get(0).isSynthetic()).isTrue();
		assertThat(result.compactedEvents().get(1).isSynthetic()).isTrue();
		assertThat(result.compactedEvents().get(2).isSynthetic()).isTrue();
		assertThat(result.compactedEvents().get(3).isSynthetic()).isTrue();
		assertThat(result.compactedEvents().get(4).getMessage().getText()).isEqualTo("q2");
	}

	@Test
	void preambleEventsBeforeFirstUserMessageArePreserved() {
		TurnWindowCompactionStrategy strategy = TurnWindowCompactionStrategy.builder().maxTurns(1).build();

		List<SessionEvent> events = new ArrayList<>();
		// preamble: assistant event before any user message (pre-seeded tool state)
		events.add(
				SessionEvent.builder().sessionId(SESSION_ID).message(new AssistantMessage("system boot info")).build());
		events.addAll(turn("q1", "a1"));
		events.addAll(turn("q2", "a2"));

		CompactionResult result = strategy.compact(requestWith(events));

		// Result: [preamble] + [turn2]
		assertThat(result.compactedEvents()).hasSize(3); // 1 preamble + 2 events in turn2
		assertThat(result.compactedEvents().get(0).getMessage().getText()).isEqualTo("system boot info");
		assertThat(result.compactedEvents().get(1).getMessage().getText()).isEqualTo("q2");
	}

	@Test
	void neverSplitsATurnInHalf() {
		TurnWindowCompactionStrategy strategy = TurnWindowCompactionStrategy.builder().maxTurns(2).build();

		// Turn 1: user + assistant + assistant (multi-step)
		List<SessionEvent> turn1 = List.of(
				SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage("u1")).build(),
				SessionEvent.builder().sessionId(SESSION_ID).message(new AssistantMessage("step1")).build(),
				SessionEvent.builder().sessionId(SESSION_ID).message(new AssistantMessage("step2")).build());

		// Turn 2: user + assistant
		List<SessionEvent> turn2 = turn("u2", "a2");

		// Turn 3: user + assistant
		List<SessionEvent> turn3 = turn("u3", "a3");

		CompactionRequest request = requestWith(turn1, turn2, turn3);
		CompactionResult result = strategy.compact(request);

		// Keep last 2 turns (turn2, turn3). Turn1 (3 events) is archived.
		assertThat(result.archivedEvents()).hasSize(3);
		assertThat(result.compactedEvents()).hasSize(4); // turn2 + turn3

		// Verify turn2 starts the kept section
		assertThat(result.compactedEvents().get(0).getMessage().getText()).isEqualTo("u2");
	}

	@Test
	void emptySessionReturnsUnchanged() {
		TurnWindowCompactionStrategy strategy = TurnWindowCompactionStrategy.builder().maxTurns(3).build();
		CompactionRequest request = requestWith(List.of());

		CompactionResult result = strategy.compact(request);

		assertThat(result.compactedEvents()).isEmpty();
		assertThat(result.archivedEvents()).isEmpty();
		assertThat(result.eventsRemoved()).isEqualTo(0);
	}

	@Test
	void maxTurnsZeroIsRejected() {
		assertThatThrownBy(() -> TurnWindowCompactionStrategy.builder().maxTurns(0).build())
				.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("maxTurns must be greater than 0");
	}

	@Test
	void nullRequestIsRejected() {
		TurnWindowCompactionStrategy strategy = TurnWindowCompactionStrategy.builder().maxTurns(3).build();
		assertThatThrownBy(() -> strategy.compact(null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void defaultMaxTurnsIsApplied() {
		TurnWindowCompactionStrategy strategy = TurnWindowCompactionStrategy.builder().build();
		assertThat(strategy.getMaxTurns()).isEqualTo(TurnWindowCompactionStrategy.DEFAULT_MAX_TURNS);
	}

	@Test
	void tokensRemovedApproximation() {
		TurnWindowCompactionStrategy strategy = TurnWindowCompactionStrategy.builder().maxTurns(1).build();
		// Turn 1 archived: "q1" + "a1" — token count estimated via
		// JTokkitTokenCountEstimator
		CompactionRequest request = requestWith(turn("q1", "a1"), turn("q2", "a2"));

		CompactionResult result = strategy.compact(request);

		assertThat(result.tokensEstimatedSaved()).isGreaterThanOrEqualTo(0);
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
