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
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link CompactionRequest}.
 */
class CompactionRequestTests {

	private static final String SESSION_ID = "test-session";

	@Test
	void currentTurnCountIsZeroForEmptySession() {
		CompactionRequest request = requestWith(List.of());
		assertThat(request.currentTurnCount()).isEqualTo(0);
	}

	@Test
	void currentTurnCountEqualsNumberOfUserMessages() {
		List<SessionEvent> events = new ArrayList<>();
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage("q1")).build());
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new AssistantMessage("a1")).build());
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage("q2")).build());
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new AssistantMessage("a2")).build());
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage("q3")).build());
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new AssistantMessage("a3")).build());

		CompactionRequest request = requestWith(events);

		assertThat(request.currentTurnCount()).isEqualTo(3);
	}

	@Test
	void syntheticEventsAreNotCounted() {
		List<SessionEvent> events = new ArrayList<>();
		// Synthetic events that happen to look like user messages — must not count
		events.add(SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(new UserMessage("Summarize the conversation we had so far."))
			.metadata(SessionEvent.METADATA_SYNTHETIC, true)
			.metadata(SessionEvent.METADATA_COMPACTION_SOURCE, "test")
			.build());
		events.add(SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(new AssistantMessage("summary of earlier user turns"))
			.metadata(SessionEvent.METADATA_SYNTHETIC, true)
			.metadata(SessionEvent.METADATA_COMPACTION_SOURCE, "test")
			.build());
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage("q1")).build());
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new AssistantMessage("a1")).build());

		CompactionRequest request = requestWith(events);

		assertThat(request.currentTurnCount()).isEqualTo(1);
	}

	@Test
	void assistantOnlySessionHasZeroTurns() {
		List<SessionEvent> events = new ArrayList<>();
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new AssistantMessage("preamble")).build());
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new AssistantMessage("more preamble")).build());

		CompactionRequest request = requestWith(events);

		assertThat(request.currentTurnCount()).isEqualTo(0);
	}

	@Test
	void branchedUserMessagesAreNotCountedAsTurns() {
		// Sub-agents write USER messages attributed to their own branch; those must not
		// inflate the root turn count used by TurnCountTrigger.
		List<SessionEvent> events = new ArrayList<>();
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage("root-q1")).build()); // branch=null
																												// →
																												// counts
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new AssistantMessage("root-a1")).build());
		events.add(SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(new UserMessage("sub-agent-q"))
			.branch("orch.researcher")
			.build()); // branch set → ignored
		events.add(SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(new AssistantMessage("sub-agent-a"))
			.branch("orch.researcher")
			.build());
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage("root-q2")).build()); // branch=null
																												// →
																												// counts
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new AssistantMessage("root-a2")).build());

		CompactionRequest request = requestWith(events);

		assertThat(request.currentTurnCount()).isEqualTo(2);
	}

	@Test
	void currentEventCountMatchesEventListSize() {
		List<SessionEvent> events = new ArrayList<>();
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage("q1")).build());
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new AssistantMessage("a1")).build());

		CompactionRequest request = requestWith(events);

		assertThat(request.currentEventCount()).isEqualTo(2);
	}

	@Test
	void ofRejectsNullSession() {
		assertThatThrownBy(() -> CompactionRequest.of(null, List.of())).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void ofWithoutScopeDefaultsToSessionScope() {
		CompactionRequest request = requestWith(List.of());
		assertThat(request.scope()).isEqualTo(CompactionScope.session());
	}

	// --- branch scope ---

	@Test
	void branchScopeCountsOnlyOwnBranchUserMessagesAsTurns() {
		// planner turn 1: [planner-q1, planner-a1]; planner.sub exchange inside it;
		// planner turn 2: [planner-q2, planner-a2]; a root turn interleaved (must not count)
		List<SessionEvent> events = new ArrayList<>();
		events.add(branchEvent(new UserMessage("planner-q1"), "planner"));
		events.add(branchEvent(new AssistantMessage("planner-a1"), "planner"));
		events.add(branchEvent(new UserMessage("sub-q"), "planner.sub"));
		events.add(branchEvent(new AssistantMessage("sub-a"), "planner.sub"));
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage("root-q")).build());
		events.add(branchEvent(new UserMessage("planner-q2"), "planner"));
		events.add(branchEvent(new AssistantMessage("planner-a2"), "planner"));

		Session session = Session.builder().id(SESSION_ID).userId("test-user").build();
		CompactionRequest request = CompactionRequest.of(session, events, CompactionScope.branch("planner"));

		// Only planner-q1 and planner-q2 count; planner.sub-q and root-q do not.
		assertThat(request.currentTurnCount()).isEqualTo(2);
		assertThat(request.currentEventCount()).isEqualTo(7);
		assertThat(request.scope()).isEqualTo(CompactionScope.branch("planner"));
	}

	// --- helper ---

	private static SessionEvent branchEvent(Message message, String branch) {
		return SessionEvent.builder().sessionId(SESSION_ID).message(message).branch(branch).build();
	}

	private CompactionRequest requestWith(List<SessionEvent> events) {
		Session session = Session.builder().id(SESSION_ID).userId("test-user").build();
		return CompactionRequest.of(session, new ArrayList<>(events));
	}

}
