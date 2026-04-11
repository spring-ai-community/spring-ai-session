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
import java.util.Optional;
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
		Optional<Session> found = this.repository.findById(saved.id());

		assertThat(found).isPresent();
		assertThat(found.get().id()).isEqualTo(saved.id());
		assertThat(found.get().userId()).isEqualTo("user-1");
	}

	@Test
	void findByIdReturnsEmptyWhenNotFound() {
		assertThat(this.repository.findById("no-such-id")).isEmpty();
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

		assertThat(this.repository.findById(session.id())).isEmpty();
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
	void replaceEventsWithCorrectVersionSucceeds() {
		Session session = buildSession("user-7");
		this.repository.save(session);
		this.repository
			.appendEvent(SessionEvent.builder().sessionId(session.id()).message(new UserMessage("msg-1")).build());

		long version = this.repository.getEventVersion(session.id());
		List<SessionEvent> replacement = List
			.of(SessionEvent.builder().sessionId(session.id()).message(new UserMessage("compacted")).build());

		boolean replaced = this.repository.replaceEvents(session.id(), replacement, version);

		assertThat(replaced).isTrue();
		assertThat(this.repository.getEventVersion(session.id())).isEqualTo(version + 1);
		assertThat(this.repository.findEvents(session.id(), EventFilter.all())).hasSize(1);
		assertThat(this.repository.findEvents(session.id(), EventFilter.all()).get(0).getMessage().getText())
			.isEqualTo("compacted");
	}

	@Test
	void replaceEventsWithStaleVersionFails() {
		Session session = buildSession("user-8");
		this.repository.save(session);
		this.repository
			.appendEvent(SessionEvent.builder().sessionId(session.id()).message(new UserMessage("msg-1")).build());

		long staleVersion = this.repository.getEventVersion(session.id()) - 1;
		List<SessionEvent> replacement = List
			.of(SessionEvent.builder().sessionId(session.id()).message(new UserMessage("should-not-land")).build());

		boolean replaced = this.repository.replaceEvents(session.id(), replacement, staleVersion);

		assertThat(replaced).isFalse();
		// Original event is still there
		assertThat(this.repository.findEvents(session.id(), EventFilter.all())).hasSize(1);
		assertThat(this.repository.findEvents(session.id(), EventFilter.all()).get(0).getMessage().getText())
			.isEqualTo("msg-1");
	}

	@Test
	void replaceEventsVersionIncrementedOnUnconditionalReplace() {
		Session session = buildSession("user-9");
		this.repository.save(session);
		this.repository
			.appendEvent(SessionEvent.builder().sessionId(session.id()).message(new UserMessage("original")).build());

		long versionBefore = this.repository.getEventVersion(session.id());
		this.repository.replaceEvents(session.id(), List.of());

		assertThat(this.repository.getEventVersion(session.id())).isEqualTo(versionBefore + 1);
	}

	private Session buildSession(String userId) {
		return Session.builder().id(UUID.randomUUID().toString()).userId(userId).build();
	}

}
