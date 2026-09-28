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

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.compaction.CompactionPlan.Insert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link CompactionPlan}: how a plan is derived from a strategy result and how
 * it is applied to a log held as a list.
 */
class CompactionPlanTests {

	private static final String SESSION_ID = "session-1";

	@Test
	void ofArchivesDeletesAndInsertsBeforeTheNextRetainedExistingEvent() {
		SessionEvent u1 = user("u1");
		SessionEvent a1 = assistant("a1");
		SessionEvent oldSummary = synthetic("old summary");
		SessionEvent u2 = user("u2");
		SessionEvent a2 = assistant("a2");
		SessionEvent newSummary = synthetic("new summary");
		List<SessionEvent> active = List.of(oldSummary, u1, a1, u2, a2);
		CompactionResult result = new CompactionResult(List.of(newSummary, u2, a2), List.of(u1, a1), 0);

		CompactionPlan plan = CompactionPlan.of(SESSION_ID, active, result);

		assertThat(plan.archiveIds()).containsExactlyInAnyOrder(u1.getId(), a1.getId());
		assertThat(plan.deleteIds()).containsExactly(oldSummary.getId());
		assertThat(plan.inserts()).containsExactly(Insert.before(u2.getId(), List.of(newSummary)));
		assertThat(plan.insertedEvents()).containsExactly(newSummary);
		assertThat(plan.isEmpty()).isFalse();
	}

	@Test
	void ofAppendsNewEventsThatNoExistingEventFollows() {
		SessionEvent u1 = user("u1");
		SessionEvent added = synthetic("added");
		CompactionResult result = new CompactionResult(List.of(u1, added), List.of(), 0);

		CompactionPlan plan = CompactionPlan.of(SESSION_ID, List.of(u1), result);

		assertThat(plan.inserts()).containsExactly(Insert.atEnd(List.of(added)));
	}

	@Test
	void ofIsEmptyWhenTheResultKeepsEverything() {
		SessionEvent u1 = user("u1");
		CompactionResult result = new CompactionResult(List.of(u1), List.of(), 0);

		assertThat(CompactionPlan.of(SESSION_ID, List.of(u1), result).isEmpty()).isTrue();
	}

	@Test
	void ofRejectsANewEventOfAnotherSession() {
		SessionEvent u1 = user("u1");
		SessionEvent foreign = SessionEvent.builder()
			.sessionId("another-session")
			.message(new UserMessage("foreign"))
			.build();
		CompactionResult result = new CompactionResult(List.of(foreign, u1), List.of(), 0);

		assertThatThrownBy(() -> CompactionPlan.of(SESSION_ID, List.of(u1), result))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("another-session");
	}

	@Test
	void applyToFlagsArchivesInPlaceDropsDeletesAndInsertsBeforeAnchors() {
		SessionEvent u1 = user("u1");
		SessionEvent a1 = assistant("a1");
		SessionEvent oldSummary = synthetic("old summary");
		SessionEvent u2 = user("u2");
		SessionEvent newSummary = synthetic("new summary");
		SessionEvent tail = synthetic("tail");
		CompactionPlan plan = new CompactionPlan(Set.of(u1.getId(), a1.getId()), Set.of(oldSummary.getId()),
				List.of(Insert.before(u2.getId(), List.of(newSummary)), Insert.atEnd(List.of(tail))));

		List<SessionEvent> log = plan.applyTo(List.of(u1, a1, oldSummary, u2));

		assertThat(log).extracting(e -> e.getMessage().getText())
			.containsExactly("u1", "a1", "new summary", "u2", "tail");
		assertThat(log).extracting(SessionEvent::isArchived).containsExactly(true, true, false, false, false);
	}

	@Test
	void applyToRejectsUnknownArchiveAndAnchorIds() {
		SessionEvent u1 = user("u1");
		CompactionPlan unknownArchive = new CompactionPlan(Set.of("missing"), Set.of(), List.of());
		CompactionPlan unknownAnchor = new CompactionPlan(Set.of(), Set.of(),
				List.of(Insert.before("missing", List.of(synthetic("s")))));

		assertThatThrownBy(() -> unknownArchive.applyTo(List.of(u1))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> unknownAnchor.applyTo(List.of(u1))).isInstanceOf(IllegalArgumentException.class);
	}

	private static SessionEvent user(String text) {
		return SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage(text)).build();
	}

	private static SessionEvent assistant(String text) {
		return SessionEvent.builder().sessionId(SESSION_ID).message(new AssistantMessage(text)).build();
	}

	private static SessionEvent synthetic(String text) {
		return SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(new AssistantMessage(text))
			.metadata(SessionEvent.METADATA_SYNTHETIC, true)
			.build();
	}

}
