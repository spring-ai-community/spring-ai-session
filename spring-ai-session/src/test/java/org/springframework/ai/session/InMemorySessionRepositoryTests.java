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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link InMemorySessionRepository}.
 */
class InMemorySessionRepositoryTests {

	private InMemorySessionRepository repository;

	@BeforeEach
	void setUp() {
		this.repository = InMemorySessionRepository.builder().build();
	}

	@Test
	void saveAndFindByIdRoundTrip() {
		Session session = buildSession("user-1");

		Session saved = this.repository.save(session);
		Session found = this.repository.findById(saved.id());

		assertThat(found).isNotNull();
		assertThat(found.id()).isEqualTo(saved.id());
		assertThat(found.userId()).isEqualTo("user-1");
	}

	@Test
	void findByIdReturnsNullWhenNotFound() {
		assertThat(this.repository.findById("no-such-id")).isNull();
	}

	@Test
	void appendEventThrowsWhenSessionNotFound() {
		InMemorySessionRepository repo = this.repository;
		SessionEvent event = SessionEvent.builder().sessionId("ghost-session").message(new UserMessage("hi")).build();
		assertThatThrownBy(() -> repo.appendEvent(event)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Session not found");
	}

	@Test
	void findEventsLastNReturnsOnlyLastNEvents() {
		Session session = buildSession("user-2");
		this.repository.save(session);

		for (int i = 1; i <= 5; i++) {
			this.repository.appendEvent(
					SessionEvent.builder().sessionId(session.id()).message(new UserMessage("msg-" + i)).build());
		}

		List<SessionEvent> last2 = this.repository.findEvents(session.id(), EventFilter.lastN(2));
		assertThat(last2).hasSize(2);
		assertThat(last2.get(0).getMessage().getText()).isEqualTo("msg-4");
		assertThat(last2.get(1).getMessage().getText()).isEqualTo("msg-5");
	}

	@Test
	void findEventsRealOnlyExcludesSynthetic() {
		Session session = buildSession("user-3");
		this.repository.save(session);

		this.repository
			.appendEvent(SessionEvent.builder().sessionId(session.id()).message(new UserMessage("real")).build());
		this.repository.appendEvent(SessionEvent.builder()
			.sessionId(session.id())
			.message(new UserMessage("Summarize the conversation we had so far."))
			.metadata(SessionEvent.METADATA_SYNTHETIC, true)
			.metadata(SessionEvent.METADATA_COMPACTION_SOURCE, "sliding-window")
			.build());
		this.repository.appendEvent(SessionEvent.builder()
			.sessionId(session.id())
			.message(new AssistantMessage("summary text"))
			.metadata(SessionEvent.METADATA_SYNTHETIC, true)
			.metadata(SessionEvent.METADATA_COMPACTION_SOURCE, "sliding-window")
			.build());

		List<SessionEvent> real = this.repository.findEvents(session.id(), EventFilter.realOnly());
		assertThat(real).hasSize(1);
		assertThat(real.get(0).getMessage().getText()).isEqualTo("real");
	}

	@Test
	void deleteRemovesSession() {
		Session session = buildSession("user-4");
		this.repository.save(session);

		this.repository.delete(session.id());

		assertThat(this.repository.findById(session.id())).isNull();
	}

	@Test
	void findExpiredSessionIdsReturnsExpiredOnes() {
		Session active = buildSession("user-5");
		Session expired = Session.builder()
			.id(UUID.randomUUID().toString())
			.userId("user-5")
			.expiresAt(Instant.now().minusSeconds(60))
			.build();

		this.repository.save(active);
		this.repository.save(expired);

		List<String> expiredIds = this.repository.findExpiredSessionIds(Instant.now());
		assertThat(expiredIds).containsExactly(expired.id());
		assertThat(expiredIds).doesNotContain(active.id());
	}

	@Test
	void findByUserIdReturnsAllSessionsForUser() {
		this.repository.save(buildSession("alice"));
		this.repository.save(buildSession("alice"));
		this.repository.save(buildSession("bob"));

		assertThat(this.repository.findByUserId("alice")).hasSize(2);
		assertThat(this.repository.findByUserId("bob")).hasSize(1);
	}

	@Test
	void getEventVersionStartsAtZeroAndIncrementsOnAppend() {
		Session session = buildSession("user-6");
		this.repository.save(session);

		assertThat(this.repository.getEventVersion(session.id())).isZero();

		this.repository
			.appendEvent(SessionEvent.builder().sessionId(session.id()).message(new UserMessage("a")).build());
		assertThat(this.repository.getEventVersion(session.id())).isEqualTo(1L);

		this.repository
			.appendEvent(SessionEvent.builder().sessionId(session.id()).message(new UserMessage("b")).build());
		assertThat(this.repository.getEventVersion(session.id())).isEqualTo(2L);
	}

	@Test
	void compactEventsWithCorrectVersionSucceeds() {
		Session session = buildSession("user-7");
		this.repository.save(session);
		SessionEvent e1 = SessionEvent.builder().sessionId(session.id()).message(new UserMessage("msg-1")).build();
		SessionEvent e2 = SessionEvent.builder().sessionId(session.id()).message(new UserMessage("msg-2")).build();
		this.repository.appendEvent(e1);
		this.repository.appendEvent(e2);

		long version = this.repository.getEventVersion(session.id());
		SessionEvent summary = SessionEvent.builder()
			.sessionId(session.id())
			.message(new UserMessage("summary"))
			.build();

		boolean replaced = this.repository.compactEvents(session.id(), List.of(e1), List.of(summary, e2), version);

		assertThat(replaced).isTrue();
		assertThat(this.repository.getEventVersion(session.id())).isEqualTo(version + 1);
		// Active view excludes the archived event
		assertThat(this.repository.findEvents(session.id(), EventFilter.active()))
			.extracting(e -> e.getMessage().getText())
			.containsExactly("summary", "msg-2");
		// Full view still contains the archived event (Recall Storage), ahead of the
		// active window, and it is flagged archived.
		List<SessionEvent> all = this.repository.findEvents(session.id(), EventFilter.all());
		assertThat(all).extracting(e -> e.getMessage().getText()).containsExactly("msg-1", "summary", "msg-2");
		assertThat(all.get(0).isArchived()).isTrue();
		assertThat(all.get(1).isArchived()).isFalse();
	}

	@Test
	void compactEventsWithStaleVersionFails() {
		Session session = buildSession("user-8");
		this.repository.save(session);
		SessionEvent e1 = SessionEvent.builder().sessionId(session.id()).message(new UserMessage("msg-1")).build();
		this.repository.appendEvent(e1);

		long staleVersion = this.repository.getEventVersion(session.id()) - 1;
		SessionEvent summary = SessionEvent.builder()
			.sessionId(session.id())
			.message(new UserMessage("should-not-land"))
			.build();

		boolean replaced = this.repository.compactEvents(session.id(), List.of(e1), List.of(summary), staleVersion);

		assertThat(replaced).isFalse();
		// Original event is still there and is not archived
		List<SessionEvent> all = this.repository.findEvents(session.id(), EventFilter.all());
		assertThat(all).hasSize(1);
		assertThat(all.get(0).getMessage().getText()).isEqualTo("msg-1");
		assertThat(all.get(0).isArchived()).isFalse();
	}

	@Test
	void compactEventsPreservesPreviouslyArchivedEvents() {
		Session session = buildSession("user-9");
		this.repository.save(session);
		SessionEvent e1 = SessionEvent.builder().sessionId(session.id()).message(new UserMessage("e1")).build();
		SessionEvent e2 = SessionEvent.builder().sessionId(session.id()).message(new UserMessage("e2")).build();
		SessionEvent e3 = SessionEvent.builder().sessionId(session.id()).message(new UserMessage("e3")).build();
		this.repository.appendEvent(e1);
		this.repository.appendEvent(e2);
		this.repository.appendEvent(e3);

		// First pass: archive e1, keep summary-1 + e2 + e3
		SessionEvent summary1 = SessionEvent.builder().sessionId(session.id()).message(new UserMessage("s1")).build();
		long v1 = this.repository.getEventVersion(session.id());
		this.repository.compactEvents(session.id(), List.of(e1), List.of(summary1, e2, e3), v1);

		// Second pass: archive e2, drop the superseded summary-1, keep summary-2 + e3
		SessionEvent summary2 = SessionEvent.builder().sessionId(session.id()).message(new UserMessage("s2")).build();
		long v2 = this.repository.getEventVersion(session.id());
		this.repository.compactEvents(session.id(), List.of(e2), List.of(summary2, e3), v2);

		// e1 (previously archived) is preserved; e2 newly archived; superseded s1 dropped
		assertThat(this.repository.findEvents(session.id(), EventFilter.all()))
			.extracting(e -> e.getMessage().getText())
			.containsExactly("e1", "e2", "s2", "e3");
		assertThat(this.repository.findEvents(session.id(), EventFilter.active()))
			.extracting(e -> e.getMessage().getText())
			.containsExactly("s2", "e3");
	}

	private Session buildSession(String userId) {
		return Session.builder().id(UUID.randomUUID().toString()).userId(userId).build();
	}

}
