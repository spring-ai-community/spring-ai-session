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
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link SlidingWindowCompactionStrategy}.
 */
class SlidingWindowCompactionStrategyTests {

	private static final String SESSION_ID = "test-session";

	@Test
	void eventsUnderLimitNoCompaction() {
		SlidingWindowCompactionStrategy strategy = SlidingWindowCompactionStrategy.builder().maxEvents(5).build();
		List<SessionEvent> events = buildRealEvents(3);
		CompactionRequest context = contextFor(events);

		CompactionResult result = strategy.compact(context);

		assertThat(result.compactedEvents()).hasSize(3);
		assertThat(result.archivedEvents()).isEmpty();
		assertThat(result.eventsRemoved()).isEqualTo(0);
	}

	@Test
	void eventsAtExactLimitNoCompaction() {
		SlidingWindowCompactionStrategy strategy = SlidingWindowCompactionStrategy.builder().maxEvents(3).build();
		List<SessionEvent> events = buildRealEvents(3);
		CompactionRequest context = contextFor(events);

		CompactionResult result = strategy.compact(context);

		assertThat(result.compactedEvents()).hasSize(3);
		assertThat(result.eventsRemoved()).isEqualTo(0);
	}

	@Test
	void eventsOverLimitKeepsLastMaxEvents() {
		SlidingWindowCompactionStrategy strategy = SlidingWindowCompactionStrategy.builder().maxEvents(3).build();
		List<SessionEvent> events = buildRealEvents(5);
		CompactionRequest context = contextFor(events);

		CompactionResult result = strategy.compact(context);

		assertThat(result.compactedEvents()).hasSize(3);
		assertThat(result.archivedEvents()).hasSize(2);
		assertThat(result.eventsRemoved()).isEqualTo(2);

		assertThat(result.compactedEvents().get(0).getMessage().getText()).isEqualTo("msg-3");
		assertThat(result.compactedEvents().get(1).getMessage().getText()).isEqualTo("msg-4");
		assertThat(result.compactedEvents().get(2).getMessage().getText()).isEqualTo("msg-5");
	}

	@Test
	void syntheticEventsAlwaysPreservedAndPlacedFirst() {
		SlidingWindowCompactionStrategy strategy = SlidingWindowCompactionStrategy.builder().maxEvents(3).build();

		List<SessionEvent> events = new ArrayList<>();
		// One summary turn = 2 synthetic events (USER shadow + ASSISTANT summary)
		events.add(SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(new UserMessage("Summarize the conversation we had so far."))
			.metadata(SessionEvent.METADATA_SYNTHETIC, true)
			.metadata(SessionEvent.METADATA_COMPACTION_SOURCE, "sliding-window")
			.build());
		events.add(SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(new AssistantMessage("prior summary"))
			.metadata(SessionEvent.METADATA_SYNTHETIC, true)
			.metadata(SessionEvent.METADATA_COMPACTION_SOURCE, "sliding-window")
			.build());
		events.addAll(buildRealEvents(4)); // msg-1 … msg-4

		CompactionRequest context = contextFor(events);
		CompactionResult result = strategy.compact(context);

		// maxEvents=3 controls the real-events window independently of synthetics.
		// 4 real events → keep last 3 (msg-2, msg-3, msg-4); 1 real archived.
		// Result: [s_user, s_assistant, msg-2, msg-3, msg-4] = 5 events total.
		assertThat(result.compactedEvents()).hasSize(5);
		assertThat(result.compactedEvents().get(0).isSynthetic()).isTrue();
		assertThat(result.compactedEvents().get(0).getMessageType())
			.isEqualTo(org.springframework.ai.chat.messages.MessageType.USER);
		assertThat(result.compactedEvents().get(1).isSynthetic()).isTrue();
		assertThat(result.compactedEvents().get(1).getMessage().getText()).isEqualTo("prior summary");
		assertThat(result.compactedEvents().get(2).getMessage().getText()).isEqualTo("msg-2");
		assertThat(result.compactedEvents().get(3).getMessage().getText()).isEqualTo("msg-3");
		assertThat(result.compactedEvents().get(4).getMessage().getText()).isEqualTo("msg-4");
		assertThat(result.archivedEvents()).hasSize(1);
		assertThat(result.archivedEvents().get(0).getMessage().getText()).isEqualTo("msg-1");
	}

	@Test
	void eventsRemovedCountIsCorrect() {
		SlidingWindowCompactionStrategy strategy = SlidingWindowCompactionStrategy.builder().maxEvents(2).build();
		List<SessionEvent> events = buildRealEvents(7);
		CompactionRequest context = contextFor(events);

		CompactionResult result = strategy.compact(context);

		assertThat(result.eventsRemoved()).isEqualTo(5);
		assertThat(result.archivedEvents()).hasSize(5);
		assertThat(result.compactedEvents()).hasSize(2);
	}

	@Test
	void maxEventsZeroIsRejected() {
		assertThatThrownBy(() -> SlidingWindowCompactionStrategy.builder().maxEvents(0).build())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("maxEvents must be greater than 0");
	}

	@Test
	void nullEventsIsRejected() {
		SlidingWindowCompactionStrategy strategy = SlidingWindowCompactionStrategy.builder().maxEvents(5).build();
		assertThatThrownBy(() -> strategy.compact(null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void snapsToTurnBoundaryWhenRawCutLandsOnAssistantEvent() {
		// maxEvents = 3 → slotsForReal = 3 (no synthetics)
		// Session: [u1, a1, u2, a2, u3, a3] (3 turns, 6 events)
		// Raw cutIndex = 6 - 3 = 3 → real[3] = a2 (ASSISTANT — not a turn start)
		// Snap forward: real[3]=a2(ASSISTANT) → real[4]=u3(USER) → cutIndex=4
		// Kept: [u3, a3], Archived: [u1, a1, u2, a2]
		SlidingWindowCompactionStrategy strategy = SlidingWindowCompactionStrategy.builder().maxEvents(3).build();

		List<SessionEvent> events = new ArrayList<>();
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage("u1")).build());
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new AssistantMessage("a1")).build());
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage("u2")).build());
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new AssistantMessage("a2")).build());
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage("u3")).build());
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new AssistantMessage("a3")).build());

		CompactionResult result = strategy.compact(contextFor(events));

		// Kept window must start at a USER event
		assertThat(result.compactedEvents()).isNotEmpty();
		assertThat(result.compactedEvents().get(0).getMessage().getText()).isEqualTo("u3");
		assertThat(result.compactedEvents().get(1).getMessage().getText()).isEqualTo("a3");

		// Archived contains the first two turns intact
		assertThat(result.archivedEvents()).hasSize(4);
	}

	@Test
	void keptWindowAlwaysStartsAtUserMessage() {
		// Any compacted result that is non-empty must begin with a USER event
		SlidingWindowCompactionStrategy strategy = SlidingWindowCompactionStrategy.builder().maxEvents(2).build();

		List<SessionEvent> events = new ArrayList<>();
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage("q1")).build());
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new AssistantMessage("r1")).build());
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new AssistantMessage("r1b")).build()); // multi-step
																												// turn
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage("q2")).build());
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new AssistantMessage("r2")).build());

		CompactionResult result = strategy.compact(contextFor(events));

		if (!result.compactedEvents().isEmpty()) {
			SessionEvent first = result.compactedEvents().get(0);
			assertThat(first.isSynthetic() || first.getMessage() instanceof UserMessage).isTrue();
		}
	}

	@Test
	void tokensEstimatedSavedIsPositiveWhenArchivingToolEvents() {
		SlidingWindowCompactionStrategy strategy = SlidingWindowCompactionStrategy.builder().maxEvents(2).build();

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

		CompactionResult result = strategy.compact(contextFor(events));

		assertThat(result.archivedEvents()).isNotEmpty();
		assertThat(result.tokensEstimatedSaved()).isGreaterThan(0);
	}

	// --- branch-awareness ---

	@Test
	void branchEventsDoNotConsumeMaxEventsSlots() {
		// real=[u1, a1, sub-q(branch), sub-a(branch), u2, a2] → 4 root events, 2 branch
		// maxEvents=2 → archive 2 root events (u1, a1); kept window starts at u2.
		// Branch events between the archived and kept root turns are also archived because
		// they fall before the snap cut point.
		SlidingWindowCompactionStrategy strategy = SlidingWindowCompactionStrategy.builder().maxEvents(2).build();

		List<SessionEvent> events = new ArrayList<>();
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage("u1")).build());
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new AssistantMessage("a1")).build());
		events.add(SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(new UserMessage("sub-q"))
			.branch("sub")
			.build());
		events.add(SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(new AssistantMessage("sub-a"))
			.branch("sub")
			.build());
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage("u2")).build());
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new AssistantMessage("a2")).build());

		CompactionResult result = strategy.compact(contextFor(events));

		assertThat(result.compactedEvents()).hasSize(2);
		assertThat(result.compactedEvents().get(0).getMessage().getText()).isEqualTo("u2");
		assertThat(result.compactedEvents().get(1).getMessage().getText()).isEqualTo("a2");
		assertThat(result.archivedEvents()).hasSize(4); // u1, a1, sub-q, sub-a
	}

	@Test
	void noCompactionWhenRootEventsWithinBudgetDespiteExcessTotalEvents() {
		// real=[u1, a1, sub-q(branch), sub-a(branch), u2, a2] — 6 total, 4 root events
		// maxEvents=4 → 4 root events <= 4 slots → no-op (branch events come for free)
		// Old behaviour (count all real events): 6 > 4 → would compact and start window
		// at branch USER u2-sub, which is semantically wrong.
		SlidingWindowCompactionStrategy strategy = SlidingWindowCompactionStrategy.builder().maxEvents(4).build();

		List<SessionEvent> events = new ArrayList<>();
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage("u1")).build());
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new AssistantMessage("a1")).build());
		events.add(SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(new UserMessage("sub-q"))
			.branch("sub")
			.build());
		events.add(SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(new AssistantMessage("sub-a"))
			.branch("sub")
			.build());
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage("u2")).build());
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new AssistantMessage("a2")).build());

		CompactionResult result = strategy.compact(contextFor(events));

		// All 6 events returned unchanged — no compaction needed
		assertThat(result.archivedEvents()).isEmpty();
		assertThat(result.compactedEvents()).hasSize(6);
	}

	private List<SessionEvent> buildRealEvents(int count) {
		List<SessionEvent> events = new ArrayList<>();
		for (int i = 1; i <= count; i++) {
			events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage("msg-" + i)).build());
		}
		return events;
	}

	private CompactionRequest contextFor(List<SessionEvent> events) {
		Session session = Session.builder().id(SESSION_ID).userId("test-user").build();
		return CompactionRequest.of(session, new ArrayList<>(events));
	}

}
