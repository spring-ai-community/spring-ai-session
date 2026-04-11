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

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.SessionEvent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CompactionUtils#snapToTurnStart}.
 *
 * @author Christian Tzolov
 */
class CompactionUtilsTests {

	private static final String SESSION_ID = "test-session";

	// --- cut already on a USER message ---

	@Test
	void cutAtUserMessageIsReturnedUnchanged() {
		List<SessionEvent> events = List.of(user("u1"), assistant("a1"), user("u2"), assistant("a2"));

		assertThat(CompactionUtils.snapToTurnStart(events, 0)).isEqualTo(0);
		assertThat(CompactionUtils.snapToTurnStart(events, 2)).isEqualTo(2);
	}

	// --- cut on a non-USER message — advances to next USER ---

	@Test
	void cutOnAssistantMessageSnapsToNextUser() {
		List<SessionEvent> events = List.of(user("u1"), assistant("a1"), user("u2"), assistant("a2"));

		// cut lands on a1 (index 1) — should advance to u2 (index 2)
		assertThat(CompactionUtils.snapToTurnStart(events, 1)).isEqualTo(2);
	}

	@Test
	void cutOnLastAssistantMessageSnapsToEnd() {
		List<SessionEvent> events = List.of(user("u1"), assistant("a1"), user("u2"), assistant("a2"));

		// cut lands on a2 (index 3) — no USER after it, snaps to real.size()
		assertThat(CompactionUtils.snapToTurnStart(events, 3)).isEqualTo(4);
	}

	// --- cut at boundaries ---

	@Test
	void cutAtZeroOnUserMessageReturnsZero() {
		List<SessionEvent> events = List.of(user("u1"), assistant("a1"));

		assertThat(CompactionUtils.snapToTurnStart(events, 0)).isEqualTo(0);
	}

	@Test
	void cutAtSizeIsReturnedUnchanged() {
		List<SessionEvent> events = List.of(user("u1"), assistant("a1"));

		assertThat(CompactionUtils.snapToTurnStart(events, events.size())).isEqualTo(2);
	}

	// --- no USER message at or after the cut ---

	@Test
	void noUserMessageAfterCutReturnsSize() {
		// Only assistant messages — no USER to snap to
		List<SessionEvent> events = List.of(assistant("a1"), assistant("a2"), assistant("a3"));

		assertThat(CompactionUtils.snapToTurnStart(events, 0)).isEqualTo(3);
		assertThat(CompactionUtils.snapToTurnStart(events, 1)).isEqualTo(3);
	}

	// --- multi-step tool interaction inside a turn ---

	@Test
	void cutInMiddleOfMultiStepTurnSnapsToNextTurnStart() {
		// turn 1: u1, a1, a2 (multi-step); turn 2: u2, a3
		List<SessionEvent> events = List.of(user("u1"), assistant("a1"), assistant("a2"), user("u2"), assistant("a3"));

		// cut at a1 (index 1) — must skip a2 (index 2) and land on u2 (index 3)
		assertThat(CompactionUtils.snapToTurnStart(events, 1)).isEqualTo(3);
		// cut at a2 (index 2) — same result
		assertThat(CompactionUtils.snapToTurnStart(events, 2)).isEqualTo(3);
	}

	// --- helpers ---

	private static SessionEvent user(String text) {
		return SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage(text)).build();
	}

	private static SessionEvent assistant(String text) {
		return SessionEvent.builder().sessionId(SESSION_ID).message(new AssistantMessage(text)).build();
	}

}
