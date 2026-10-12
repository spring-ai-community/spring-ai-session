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

import org.springframework.ai.session.SessionEvent;

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
 * Tests for {@link TurnWindowCompactionStrategy}.
 */
class TurnWindowCompactionStrategyTests {

	private static TurnWindowCompactionStrategy maxTurns(int maxTurns) {
		return TurnWindowCompactionStrategy.builder().maxTurns(maxTurns).build();
	}

	@Test
	void noOpWhenTurnsUnderLimit() {
		CompactionResult result = maxTurns(5)
			.compact(request(events(turn("q1", "a1"), turn("q2", "a2"), turn("q3", "a3"))));

		assertThat(result.isUnchanged()).isTrue();
		assertThat(result.coveredEventCount()).isZero();
	}

	@Test
	void noOpWhenTurnsAtExactLimit() {
		assertThat(maxTurns(3).compact(request(events(turn("q1", "a1"), turn("q2", "a2"), turn("q3", "a3"))))
			.isUnchanged()).isTrue();
	}

	@Test
	void coversOldestTurnsWhenOverLimit() {
		CompactionResult result = maxTurns(2)
			.compact(request(events(turn("q1", "a1"), turn("q2", "a2"), turn("q3", "a3"), turn("q4", "a4"))));

		assertThat(kept(result)).containsExactly("q3", "a3", "q4", "a4");
		assertThat(covered(result)).containsExactly("q1", "a1", "q2", "a2");
		assertThat(result.strategy()).isEqualTo("turn-window");
	}

	@Test
	void aPriorSummaryIsPinnedFirst() {
		CompactionResult result = maxTurns(2).compact(request(events(summaryTurn("prior summary"), turn("q1", "a1"),
				turn("q2", "a2"), turn("q3", "a3"), turn("q4", "a4"))));

		assertThat(result.pinnedEvents()).hasSize(2).allMatch(SessionEvent::isSynthetic);
		assertThat(kept(result)).containsExactly("Summarize the conversation we had so far.", "prior summary", "q3", "a3",
				"q4", "a4");
	}

	@Test
	void multiplePriorSummariesAreAllPinned() {
		CompactionResult result = maxTurns(1).compact(
				request(events(summaryTurn("summary-1"), summaryTurn("summary-2"), turn("q1", "a1"), turn("q2", "a2"))));

		assertThat(result.pinnedEvents()).hasSize(4).allMatch(SessionEvent::isSynthetic);
		assertThat(kept(result)).endsWith("q2", "a2");
	}

	@Test
	void preambleEventsBeforeFirstUserMessageArePinned() {
		List<SessionEvent> view = events(List.of(assistant("system boot info")), turn("q1", "a1"), turn("q2", "a2"));

		CompactionResult result = maxTurns(1).compact(request(view));

		assertThat(kept(result)).containsExactly("system boot info", "q2", "a2");
		assertThat(covered(result)).containsExactly("q1", "a1");
	}

	@Test
	void neverSplitsATurnInHalf() {
		List<SessionEvent> turn1 = List.of(user("u1"), assistant("step1"), assistant("step2"));

		CompactionResult result = maxTurns(2).compact(request(events(turn1, turn("u2", "a2"), turn("u3", "a3"))));

		assertThat(covered(result)).containsExactly("u1", "step1", "step2");
		assertThat(kept(result)).containsExactly("u2", "a2", "u3", "a3");
	}

	@Test
	void emptySessionReturnsUnchanged() {
		assertThat(maxTurns(3).compact(request(List.of())).isUnchanged()).isTrue();
	}

	@Test
	void maxTurnsZeroIsRejected() {
		assertThatThrownBy(() -> TurnWindowCompactionStrategy.builder().maxTurns(0).build())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("maxTurns must be greater than 0");
	}

	@Test
	void nullRequestIsRejected() {
		assertThatThrownBy(() -> maxTurns(3).compact(null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void defaultMaxTurnsIsApplied() {
		assertThat(TurnWindowCompactionStrategy.builder().build().getMaxTurns())
			.isEqualTo(TurnWindowCompactionStrategy.DEFAULT_MAX_TURNS);
	}

	@Test
	void tokensEstimatedSavedIsPositiveWhenCoveringATurnWithToolEvents() {
		CompactionResult result = maxTurns(1)
			.compact(request(events(toolTurn("What's the weather?"), turn("Thanks", "You're welcome"))));

		assertThat(result.coveredEvents()).isNotEmpty();
		assertThat(result.tokensEstimatedSaved()).isGreaterThan(0);
	}

	@Test
	void latestSystemMessageIsPinnedFirstAndEarlierOnesAreCovered() {
		List<SessionEvent> view = new ArrayList<>();
		view.add(system("Answer in French"));
		view.addAll(turn("u1", "a1"));
		view.add(2, system("Answer in German"));
		view.addAll(turn("u2", "a2"));

		CompactionResult result = maxTurns(1).compact(request(view));

		assertThat(covered(result)).containsExactly("Answer in French", "u1", "a1");
		assertThat(kept(result)).containsExactly("Answer in German", "u2", "a2");
	}

	@Test
	void systemMessageSurvivesWhenItsTurnIsCovered() {
		List<SessionEvent> view = new ArrayList<>(turn("u1", "a1"));
		view.add(1, system("Researcher rules"));
		view.addAll(turn("u2", "a2"));

		CompactionResult result = maxTurns(1).compact(request(view));

		assertThat(covered(result)).containsExactly("u1", "a1");
		assertThat(kept(result)).containsExactly("Researcher rules", "u2", "a2");
	}

	@Test
	void compactionWithoutUserMessageCoversNothing() {
		List<SessionEvent> view = new ArrayList<>();
		for (int i = 1; i <= 6; i++) {
			view.add(assistant("step " + i));
			view.add(assistant("answer " + i));
		}

		assertThat(maxTurns(1).compact(request(view)).isUnchanged()).isTrue();
	}

}
