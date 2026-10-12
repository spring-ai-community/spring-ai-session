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
import static org.springframework.ai.session.compaction.CompactionTestSupport.userMessages;

/**
 * Tests for {@link SlidingWindowCompactionStrategy}.
 */
class SlidingWindowCompactionStrategyTests {

	private static SlidingWindowCompactionStrategy maxEvents(int maxEvents) {
		return SlidingWindowCompactionStrategy.builder().maxEvents(maxEvents).build();
	}

	@Test
	void eventsUnderLimitNoCompaction() {
		CompactionResult result = maxEvents(5).compact(request(userMessages(3)));

		assertThat(result.isUnchanged()).isTrue();
		assertThat(result.coveredEventCount()).isZero();
	}

	@Test
	void eventsAtExactLimitNoCompaction() {
		assertThat(maxEvents(3).compact(request(userMessages(3))).isUnchanged()).isTrue();
	}

	@Test
	void eventsOverLimitKeepsLastMaxEvents() {
		CompactionResult result = maxEvents(3).compact(request(userMessages(5)));

		assertThat(covered(result)).containsExactly("msg-1", "msg-2");
		assertThat(kept(result)).containsExactly("msg-3", "msg-4", "msg-5");
		assertThat(result.summary()).isEmpty();
		assertThat(result.strategy()).isEqualTo("sliding-window");
	}

	@Test
	void aPriorSummaryIsPinnedFirstAndUsesNoSlot() {
		// maxEvents=3 controls the real-events window independently of synthetics.
		// 4 real events → keep last 3 (msg-2, msg-3, msg-4); msg-1 is covered.
		CompactionResult result = maxEvents(3).compact(request(events(summaryTurn("prior summary"), userMessages(4))));

		assertThat(result.pinnedEvents()).allMatch(SessionEvent::isSynthetic);
		assertThat(kept(result)).containsExactly("Summarize the conversation we had so far.", "prior summary", "msg-2",
				"msg-3", "msg-4");
		assertThat(covered(result)).containsExactly("msg-1");
	}

	@Test
	void coveredEventCountIsCorrect() {
		CompactionResult result = maxEvents(2).compact(request(userMessages(7)));

		assertThat(result.coveredEventCount()).isEqualTo(5);
		assertThat(result.preservedEvents()).hasSize(2);
	}

	@Test
	void maxEventsZeroIsRejected() {
		assertThatThrownBy(() -> SlidingWindowCompactionStrategy.builder().maxEvents(0).build())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("maxEvents must be greater than 0");
	}

	@Test
	void nullRequestIsRejected() {
		assertThatThrownBy(() -> maxEvents(5).compact(null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void snapsToTurnBoundaryWhenRawCutLandsOnAssistantEvent() {
		// [u1, a1, u2, a2, u3, a3], maxEvents 3: the raw cut lands on a2 and snaps to u3
		CompactionResult result = maxEvents(3)
			.compact(request(events(turn("u1", "a1"), turn("u2", "a2"), turn("u3", "a3"))));

		assertThat(kept(result)).containsExactly("u3", "a3");
		assertThat(covered(result)).containsExactly("u1", "a1", "u2", "a2");
	}

	@Test
	void keptWindowAlwaysStartsAtUserMessage() {
		List<SessionEvent> view = List.of(user("q1"), assistant("r1"), assistant("r1b"), user("q2"), assistant("r2"));

		CompactionResult result = maxEvents(2).compact(request(view));

		SessionEvent first = result.keptEvents().get(0);
		assertThat(first.isSynthetic() || first.getMessage() instanceof UserMessage).isTrue();
	}

	@Test
	void tokensEstimatedSavedIsPositiveWhenCoveringToolEvents() {
		CompactionResult result = maxEvents(2)
			.compact(request(events(toolTurn("What's the weather?"), turn("Thanks", "You're welcome"))));

		assertThat(result.coveredEvents()).isNotEmpty();
		assertThat(result.tokensEstimatedSaved()).isGreaterThan(0);
	}

	@Test
	void oversizeLastTurnIsKeptInsteadOfCoveringEverything() {
		List<SessionEvent> view = List.of(user("u1"), assistant("a1"), user("u2"), assistant("a2"), assistant("a3"),
				assistant("a4"));

		CompactionResult result = maxEvents(2).compact(request(view));

		assertThat(covered(result)).containsExactly("u1", "a1");
		assertThat(kept(result)).containsExactly("u2", "a2", "a3", "a4");
	}

	@Test
	void singleOversizeTurnIsNotCompacted() {
		List<SessionEvent> view = List.of(user("u1"), assistant("a1"), assistant("a2"), assistant("a3"));

		assertThat(maxEvents(2).compact(request(view)).isUnchanged()).isTrue();
	}

	@Test
	void storedSystemMessageIsPinnedFirstAndUsesNoSlot() {
		List<SessionEvent> view = List.of(system("Answer in French"), user("u1"), assistant("a1"), user("u2"),
				assistant("a2"));

		CompactionResult result = maxEvents(2).compact(request(view));

		assertThat(kept(result)).containsExactly("Answer in French", "u2", "a2");
		assertThat(covered(result)).containsExactly("u1", "a1");
	}

	@Test
	void onlyTheLatestStoredSystemMessageIsPinnedSoPerTurnSystemMessagesStayBounded() {
		// Latest wins: a system message stored per turn replaces the previous one; the
		// earlier ones before the cut are covered, the latest is pinned first.
		List<SessionEvent> view = new ArrayList<>();
		view.add(system("Answer in French"));
		for (int i = 1; i <= 5; i++) {
			view.add(user("u" + i));
			view.add(system("time " + i));
			view.add(assistant("a" + i));
		}

		CompactionResult result = maxEvents(3).compact(request(view));

		assertThat(kept(result)).containsExactly("time 5", "u5", "a5");
		assertThat(covered(result)).contains("Answer in French", "time 1", "time 4").doesNotContain("time 5");
	}

	@Test
	void noCutMeansUnchangedEvenWithSupersededSystemMessages() {
		List<SessionEvent> view = List.of(system("Answer in French"), user("u1"), assistant("a1"),
				system("Answer in German"));

		assertThat(maxEvents(20).compact(request(view)).isUnchanged()).isTrue();
	}

	@Test
	void systemMessageSurvivesCompactionOfTheTurnItWasStoredIn() {
		List<SessionEvent> view = List.of(user("u1"), system("Researcher rules"), assistant("a1"), user("u2"),
				assistant("a2"), user("u3"), assistant("a3"));

		CompactionResult result = maxEvents(2).compact(request(view));

		assertThat(kept(result)).containsExactly("Researcher rules", "u3", "a3");
		assertThat(covered(result)).containsExactly("u1", "a1", "u2", "a2");
	}

	@Test
	void compactionWithoutUserMessageCoversNothing() {
		// No USER event at all (e.g. only assistant and tool steps), so there is no turn
		// boundary to cut at: nothing may leave the view.
		List<SessionEvent> view = new ArrayList<>();
		for (int i = 1; i <= 6; i++) {
			view.add(assistant("step " + i));
			view.add(assistant("answer " + i));
		}

		assertThat(maxEvents(2).compact(request(view)).isUnchanged()).isTrue();
	}

}
