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

package org.springframework.ai.session;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.internal.InMemorySessionRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for branch-based isolation in {@link SessionEvent} and {@link EventFilter}.
 *
 * <p>
 * Models the multi-agent visibility rule: an event at branch {@code X} is visible to an
 * agent at branch {@code Y} if {@code X} is null (root), equals {@code Y}, or is a
 * dot-prefix ancestor of {@code Y}. Sibling branches and child branches are hidden.
 */
class EventFilterBranchTests {

	private static final String SESSION_ID = "test-session";

	private InMemorySessionRepository repository;

	@BeforeEach
	void setUp() {
		this.repository = InMemorySessionRepository.builder().build();
		this.repository.save(Session.builder().id(SESSION_ID).userId("test-user").build());
	}

	// --- SessionEvent.branch() ---

	@Test
	void builderWithoutBranchHasNullBranch() {
		SessionEvent event = SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage("hello")).build();
		assertThat(event.getBranch()).isNull();
	}

	@Test
	void builderWithBranchStoresBranch() {
		SessionEvent event = SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(new UserMessage("hello"))
			.branch("orch.researcher")
			.build();
		assertThat(event.getBranch()).isEqualTo("orch.researcher");
	}

	@Test
	void builderWithNullBranchHasNullBranch() {
		SessionEvent event = SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(new UserMessage("hello"))
			.branch(null)
			.build();
		assertThat(event.getBranch()).isNull();
	}

	@Test
	void syntheticSummaryTurnEventsHaveNullBranch() {
		List<SessionEvent> turn = List.of(
				SessionEvent.builder()
					.sessionId(SESSION_ID)
					.message(new UserMessage("Summarize the conversation we had so far."))
					.metadata(SessionEvent.METADATA_SYNTHETIC, true)
					.metadata(SessionEvent.METADATA_COMPACTION_SOURCE, "test")
					.build(),
				SessionEvent.builder()
					.sessionId(SESSION_ID)
					.message(new AssistantMessage("The user asked about X."))
					.metadata(SessionEvent.METADATA_SYNTHETIC, true)
					.metadata(SessionEvent.METADATA_COMPACTION_SOURCE, "test")
					.build());
		assertThat(turn).allMatch(e -> e.getBranch() == null);
	}

	// --- EventFilter.matches() — branch visibility ---

	@Test
	void nullFilterBranchMatchesAllEvents() {
		// No branch filter → all events visible regardless of their branch
		EventFilter noFilter = EventFilter.all();
		assertThat(noFilter.matches(event(null))).isTrue();
		assertThat(noFilter.matches(event("orch"))).isTrue();
		assertThat(noFilter.matches(event("orch.researcher"))).isTrue();
	}

	@Test
	void rootEventsAreVisibleToAllAgents() {
		// Events with null branch are pre-delegation; visible everywhere
		EventFilter filter = EventFilter.forBranch("orch.researcher");
		assertThat(filter.matches(event(null))).isTrue();
	}

	@Test
	void agentSeesItsOwnBranchEvents() {
		EventFilter filter = EventFilter.forBranch("orch.researcher");
		assertThat(filter.matches(event("orch.researcher"))).isTrue();
	}

	@Test
	void agentSeesAncestorBranchEvents() {
		// "orch" is an ancestor of "orch.researcher"
		EventFilter filter = EventFilter.forBranch("orch.researcher");
		assertThat(filter.matches(event("orch"))).isTrue();
	}

	@Test
	void agentSeesDeepAncestorBranchEvents() {
		// "orch" is an ancestor of "orch.researcher.summarizer"
		EventFilter filter = EventFilter.forBranch("orch.researcher.summarizer");
		assertThat(filter.matches(event("orch"))).isTrue();
		assertThat(filter.matches(event("orch.researcher"))).isTrue();
		assertThat(filter.matches(event("orch.researcher.summarizer"))).isTrue();
	}

	@Test
	void agentDoesNotSeeSiblingBranchEvents() {
		// "orch.writer" is a sibling of "orch.researcher" — not visible
		EventFilter filter = EventFilter.forBranch("orch.researcher");
		assertThat(filter.matches(event("orch.writer"))).isFalse();
	}

	@Test
	void agentDoesNotSeeChildBranchEvents() {
		// "orch.researcher.summarizer" is a child — parent doesn't see child's events
		EventFilter filter = EventFilter.forBranch("orch.researcher");
		assertThat(filter.matches(event("orch.researcher.summarizer"))).isFalse();
	}

	@Test
	void branchPrefixMatchRequiresDotSeparator() {
		// "orcha" should NOT match as an ancestor of "orch.researcher" even though
		// "orch.researcher".startsWith("orcha") is false — but let's also test that
		// "orch" is NOT confused with "orchestra"
		EventFilter filter = EventFilter.forBranch("orchestra.researcher");
		assertThat(filter.matches(event("orch"))).isFalse(); // "orch" ≠ prefix of
																// "orchestra"
		assertThat(filter.matches(event("orchestra"))).isTrue(); // exact ancestor
	}

	// --- Repository integration ---

	@Test
	void repositoryReturnsBranchFilteredEvents() {
		append(event(null)); // root event — visible to all
		append(event("orch")); // orchestrator event
		append(event("orch.researcher")); // researcher's own event
		append(event("orch.writer")); // sibling — should be hidden from researcher

		List<SessionEvent> visible = this.repository.findEvents(SESSION_ID, EventFilter.forBranch("orch.researcher"));

		assertThat(visible).hasSize(3);
		assertThat(visible).allMatch(
				e -> e.getBranch() == null || e.getBranch().equals("orch") || e.getBranch().equals("orch.researcher"));
	}

	@Test
	void repositoryHidesSiblingAndChildEventsFromAgent() {
		append(event("orch.researcher"));
		append(event("orch.writer")); // sibling
		append(event("orch.researcher.summarizer")); // child

		List<SessionEvent> visible = this.repository.findEvents(SESSION_ID, EventFilter.forBranch("orch.researcher"));

		assertThat(visible).hasSize(1);
		assertThat(visible.get(0).getBranch()).isEqualTo("orch.researcher");
	}

	@Test
	void syntheticEventsWithNullBranchAreAlwaysVisible() {
		this.append(SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(new UserMessage("Summarize the conversation we had so far."))
			.metadata(SessionEvent.METADATA_SYNTHETIC, true)
			.metadata(SessionEvent.METADATA_COMPACTION_SOURCE, "test")
			.build());
		this.append(SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(new AssistantMessage("Prior conversation summary."))
			.metadata(SessionEvent.METADATA_SYNTHETIC, true)
			.metadata(SessionEvent.METADATA_COMPACTION_SOURCE, "test")
			.build());
		append(event("orch.writer")); // unrelated branch

		List<SessionEvent> visible = this.repository.findEvents(SESSION_ID, EventFilter.forBranch("orch.researcher"));

		// Both synthetic events (null branch) are visible; writer event is not
		assertThat(visible).hasSize(2);
		assertThat(visible).allMatch(SessionEvent::isSynthetic);
	}

	// --- helpers ---

	private SessionEvent event(@org.jspecify.annotations.Nullable String branch) {
		return SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(new AssistantMessage("msg-" + UUID.randomUUID()))
			.branch(branch)
			.build();
	}

	private void append(SessionEvent event) {
		this.repository.appendEvent(event);
	}

}
