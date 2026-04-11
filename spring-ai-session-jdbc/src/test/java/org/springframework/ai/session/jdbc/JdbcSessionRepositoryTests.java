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

package org.springframework.ai.session.jdbc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link JdbcSessionRepository} backed by an in-process H2 database.
 *
 * <p>
 * Mirrors the contract of {@code InMemorySessionRepositoryTests} so both implementations
 * are verified against the same specification.
 */
@SpringBootTest
@TestPropertySource(properties = { "spring.datasource.url=jdbc:h2:mem:sessiontest;DB_CLOSE_DELAY=-1" })
@Sql(scripts = "classpath:org/springframework/ai/session/jdbc/schema-h2.sql",
		executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@ContextConfiguration(classes = JdbcSessionRepositoryTests.TestConfig.class)
class JdbcSessionRepositoryTests {

	@Autowired
	private JdbcSessionRepository repository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void cleanUp() {
		this.jdbcTemplate.update("DELETE FROM AI_SESSION");
	}

	// -------------------------------------------------------------------------
	// Session lifecycle
	// -------------------------------------------------------------------------

	@Test
	void saveAndFindByIdRoundTrip() {
		Session session = buildSession("user-1");
		this.repository.save(session);

		Optional<Session> found = this.repository.findById(session.id());
		assertThat(found).isPresent();
		assertThat(found.get().id()).isEqualTo(session.id());
		assertThat(found.get().userId()).isEqualTo("user-1");
	}

	@Test
	void savePreservesExpiresAtAndMetadata() {
		Instant expiry = Instant.ofEpochMilli(Instant.now().plusSeconds(3600).toEpochMilli());
		Session session = Session.builder()
			.id(UUID.randomUUID().toString())
			.userId("user-meta")
			.expiresAt(expiry)
			.metadata(java.util.Map.of("model", "gpt-4o"))
			.build();
		this.repository.save(session);

		Session found = this.repository.findById(session.id()).orElseThrow();
		assertThat(found.expiresAt()).isNotNull();
		assertThat(found.expiresAt().toEpochMilli()).isEqualTo(expiry.toEpochMilli());
		assertThat(found.metadata()).containsEntry("model", "gpt-4o");
	}

	@Test
	void saveUpsertUpdatesMetadataButPreservesEventVersion() {
		Session session = buildSession("user-upsert");
		this.repository.save(session);
		this.repository
			.appendEvent(SessionEvent.builder().sessionId(session.id()).message(new UserMessage("hi")).build());
		long versionAfterAppend = this.repository.getEventVersion(session.id());

		Session updated = Session.builder()
			.id(session.id())
			.userId(session.userId())
			.metadata(java.util.Map.of("newKey", "newVal"))
			.build();
		this.repository.save(updated);

		Session found = this.repository.findById(session.id()).orElseThrow();
		assertThat(found.metadata()).containsEntry("newKey", "newVal");
		assertThat(this.repository.getEventVersion(session.id())).isEqualTo(versionAfterAppend);
		assertThat(this.repository.findEvents(session.id(), EventFilter.all())).hasSize(1);
	}

	@Test
	void findByIdReturnsEmptyWhenNotFound() {
		assertThat(this.repository.findById("no-such-id")).isEmpty();
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
	void deleteRemovesSessionAndCascadesToEvents() {
		Session session = buildSession("user-del");
		this.repository.save(session);
		this.repository
			.appendEvent(SessionEvent.builder().sessionId(session.id()).message(new UserMessage("hi")).build());

		this.repository.delete(session.id());

		assertThat(this.repository.findById(session.id())).isEmpty();
		assertThat(this.repository.findEvents(session.id(), EventFilter.all())).isEmpty();
	}

	@Test
	void findExpiredSessionIdsReturnsOnlyExpiredOnes() {
		Session active = buildSession("user-active");
		Session expired = Session.builder()
			.id(UUID.randomUUID().toString())
			.userId("user-expired")
			.expiresAt(Instant.now().minusSeconds(60))
			.build();
		this.repository.save(active);
		this.repository.save(expired);

		List<String> expiredIds = this.repository.findExpiredSessionIds(Instant.now());
		assertThat(expiredIds).contains(expired.id());
		assertThat(expiredIds).doesNotContain(active.id());
	}

	// -------------------------------------------------------------------------
	// Event append and retrieval
	// -------------------------------------------------------------------------

	@Test
	void appendEventThrowsWhenSessionNotFound() {
		SessionEvent event = SessionEvent.builder().sessionId("ghost-session").message(new UserMessage("hi")).build();
		assertThatThrownBy(() -> this.repository.appendEvent(event)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Session not found");
	}

	@Test
	void appendedEventsAreReturnedInChronologicalOrder() {
		Session session = buildSession("user-order");
		this.repository.save(session);

		for (int i = 1; i <= 4; i++) {
			// Use spaced-out explicit timestamps to guarantee ordering in SQL
			Instant ts = Instant.ofEpochSecond(1_700_000_000L + i);
			this.repository.appendEvent(SessionEvent.builder()
				.sessionId(session.id())
				.timestamp(ts)
				.message(new UserMessage("msg-" + i))
				.build());
		}

		List<SessionEvent> events = this.repository.findEvents(session.id(), EventFilter.all());
		assertThat(events).hasSize(4);
		assertThat(events.get(0).getMessage().getText()).isEqualTo("msg-1");
		assertThat(events.get(3).getMessage().getText()).isEqualTo("msg-4");
	}

	@Test
	void findEventsLastNReturnsOnlyLastNInChronologicalOrder() {
		Session session = buildSession("user-lastn");
		this.repository.save(session);

		for (int i = 1; i <= 5; i++) {
			Instant ts = Instant.ofEpochSecond(1_700_000_000L + i);
			this.repository.appendEvent(SessionEvent.builder()
				.sessionId(session.id())
				.timestamp(ts)
				.message(new UserMessage("msg-" + i))
				.build());
		}

		List<SessionEvent> last2 = this.repository.findEvents(session.id(), EventFilter.lastN(2));
		assertThat(last2).hasSize(2);
		assertThat(last2.get(0).getMessage().getText()).isEqualTo("msg-4");
		assertThat(last2.get(1).getMessage().getText()).isEqualTo("msg-5");
	}

	@Test
	void findEventsRealOnlyExcludesSynthetic() {
		Session session = buildSession("user-synth");
		this.repository.save(session);

		this.repository
			.appendEvent(SessionEvent.builder().sessionId(session.id()).message(new UserMessage("real")).build());
		this.repository.appendEvent(SessionEvent.builder()
			.sessionId(session.id())
			.message(new UserMessage("shadow prompt"))
			.metadata(SessionEvent.METADATA_SYNTHETIC, true)
			.build());
		this.repository.appendEvent(SessionEvent.builder()
			.sessionId(session.id())
			.message(new AssistantMessage("summary"))
			.metadata(SessionEvent.METADATA_SYNTHETIC, true)
			.build());

		List<SessionEvent> real = this.repository.findEvents(session.id(), EventFilter.realOnly());
		assertThat(real).hasSize(1);
		assertThat(real.get(0).getMessage().getText()).isEqualTo("real");
	}

	@Test
	void findEventsFilterByMessageType() {
		Session session = buildSession("user-types");
		this.repository.save(session);

		this.repository
			.appendEvent(SessionEvent.builder().sessionId(session.id()).message(new UserMessage("q")).build());
		this.repository
			.appendEvent(SessionEvent.builder().sessionId(session.id()).message(new AssistantMessage("a")).build());
		this.repository
			.appendEvent(SessionEvent.builder().sessionId(session.id()).message(new UserMessage("q2")).build());

		List<SessionEvent> userOnly = this.repository.findEvents(session.id(),
				EventFilter.builder().messageTypes(Set.of(MessageType.USER)).build());
		assertThat(userOnly).hasSize(2);
		assertThat(userOnly).allMatch(e -> e.getMessageType() == MessageType.USER);
	}

	@Test
	void findEventsKeywordSearch() {
		Session session = buildSession("user-kw");
		this.repository.save(session);

		this.repository.appendEvent(
				SessionEvent.builder().sessionId(session.id()).message(new UserMessage("hello world")).build());
		this.repository.appendEvent(
				SessionEvent.builder().sessionId(session.id()).message(new AssistantMessage("goodbye")).build());
		this.repository.appendEvent(
				SessionEvent.builder().sessionId(session.id()).message(new UserMessage("Hello again")).build());

		List<SessionEvent> results = this.repository.findEvents(session.id(), EventFilter.keywordSearch("hello"));
		assertThat(results).hasSize(2);
		assertThat(results).allMatch(e -> e.getMessage().getText().toLowerCase().contains("hello"));
	}

	@Test
	void findEventsFilterByTimeRange() {
		Session session = buildSession("user-time");
		this.repository.save(session);

		Instant t1 = Instant.parse("2025-01-01T10:00:00Z");
		Instant t2 = Instant.parse("2025-01-01T11:00:00Z");
		Instant t3 = Instant.parse("2025-01-01T12:00:00Z");

		this.repository.appendEvent(
				SessionEvent.builder().sessionId(session.id()).timestamp(t1).message(new UserMessage("early")).build());
		this.repository.appendEvent(
				SessionEvent.builder().sessionId(session.id()).timestamp(t2).message(new UserMessage("mid")).build());
		this.repository.appendEvent(
				SessionEvent.builder().sessionId(session.id()).timestamp(t3).message(new UserMessage("late")).build());

		List<SessionEvent> inRange = this.repository.findEvents(session.id(),
				EventFilter.builder().from(t1).to(t2).build());
		assertThat(inRange).hasSize(2);
		assertThat(inRange.get(0).getMessage().getText()).isEqualTo("early");
		assertThat(inRange.get(1).getMessage().getText()).isEqualTo("mid");
	}

	@Test
	void findEventsPagination() {
		Session session = buildSession("user-page");
		this.repository.save(session);

		for (int i = 1; i <= 6; i++) {
			Instant ts = Instant.ofEpochSecond(1_700_000_000L + i);
			this.repository.appendEvent(SessionEvent.builder()
				.sessionId(session.id())
				.timestamp(ts)
				.message(new UserMessage("msg-" + i))
				.build());
		}

		List<SessionEvent> page0 = this.repository.findEvents(session.id(),
				EventFilter.builder().pageSize(2).page(0).build());
		List<SessionEvent> page1 = this.repository.findEvents(session.id(),
				EventFilter.builder().pageSize(2).page(1).build());
		List<SessionEvent> page2 = this.repository.findEvents(session.id(),
				EventFilter.builder().pageSize(2).page(2).build());

		assertThat(page0).hasSize(2);
		assertThat(page1).hasSize(2);
		assertThat(page2).hasSize(2);
		assertThat(page0.get(0).getMessage().getText()).isEqualTo("msg-1");
		assertThat(page1.get(0).getMessage().getText()).isEqualTo("msg-3");
		assertThat(page2.get(0).getMessage().getText()).isEqualTo("msg-5");
	}

	@Test
	void findEventsReturnsEmptyListForNonExistentSession() {
		assertThat(this.repository.findEvents("ghost", EventFilter.all())).isEmpty();
	}

	// -------------------------------------------------------------------------
	// Message type round-trips
	// -------------------------------------------------------------------------

	@Test
	void assistantMessageWithToolCallsRoundTrip() {
		Session session = buildSession("user-tc");
		this.repository.save(session);

		List<AssistantMessage.ToolCall> toolCalls = List
			.of(new AssistantMessage.ToolCall("call-1", "function", "get_weather", "{\"location\":\"Paris\"}"));
		AssistantMessage msg = AssistantMessage.builder().content("").toolCalls(toolCalls).build();

		this.repository.appendEvent(SessionEvent.builder().sessionId(session.id()).message(msg).build());

		List<SessionEvent> events = this.repository.findEvents(session.id(), EventFilter.all());
		assertThat(events).hasSize(1);
		assertThat(events.get(0).hasToolCalls()).isTrue();
		AssistantMessage retrieved = (AssistantMessage) events.get(0).getMessage();
		assertThat(retrieved.getToolCalls()).hasSize(1);
		assertThat(retrieved.getToolCalls().get(0).name()).isEqualTo("get_weather");
	}

	@Test
	void toolResponseMessageRoundTrip() {
		Session session = buildSession("user-tr");
		this.repository.save(session);

		ToolResponseMessage.ToolResponse response = new ToolResponseMessage.ToolResponse("call-1", "get_weather",
				"{\"temp\":\"22C\"}");
		ToolResponseMessage msg = ToolResponseMessage.builder().responses(List.of(response)).build();

		this.repository.appendEvent(SessionEvent.builder().sessionId(session.id()).message(msg).build());

		List<SessionEvent> events = this.repository.findEvents(session.id(), EventFilter.all());
		assertThat(events).hasSize(1);
		ToolResponseMessage retrieved = (ToolResponseMessage) events.get(0).getMessage();
		assertThat(retrieved.getResponses()).hasSize(1);
		assertThat(retrieved.getResponses().get(0).name()).isEqualTo("get_weather");
	}

	// -------------------------------------------------------------------------
	// Versioning and CAS
	// -------------------------------------------------------------------------

	@Test
	void getEventVersionStartsAtZeroAndIncrementsOnAppend() {
		Session session = buildSession("user-ver");
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
	void replaceEventsIncrementsVersion() {
		Session session = buildSession("user-rv");
		this.repository.save(session);
		this.repository
			.appendEvent(SessionEvent.builder().sessionId(session.id()).message(new UserMessage("original")).build());

		long versionBefore = this.repository.getEventVersion(session.id());
		this.repository.replaceEvents(session.id(), List.of());
		assertThat(this.repository.getEventVersion(session.id())).isEqualTo(versionBefore + 1);
	}

	@Test
	void replaceEventsWithCorrectVersionSucceeds() {
		Session session = buildSession("user-cas-ok");
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
		Session session = buildSession("user-cas-fail");
		this.repository.save(session);
		this.repository
			.appendEvent(SessionEvent.builder().sessionId(session.id()).message(new UserMessage("msg-1")).build());

		long staleVersion = this.repository.getEventVersion(session.id()) - 1;
		List<SessionEvent> replacement = List
			.of(SessionEvent.builder().sessionId(session.id()).message(new UserMessage("should-not-land")).build());

		boolean replaced = this.repository.replaceEvents(session.id(), replacement, staleVersion);

		assertThat(replaced).isFalse();
		assertThat(this.repository.findEvents(session.id(), EventFilter.all())).hasSize(1);
		assertThat(this.repository.findEvents(session.id(), EventFilter.all()).get(0).getMessage().getText())
			.isEqualTo("msg-1");
	}

	// -------------------------------------------------------------------------
	// Branch filtering
	// -------------------------------------------------------------------------

	@Test
	void findEventsWithBranchFilterIsolatesPeerAgents() {
		Session session = buildSession("user-branch");
		this.repository.save(session);

		this.repository.appendEvent(
				SessionEvent.builder().sessionId(session.id()).message(new UserMessage("root")).branch(null).build());
		this.repository.appendEvent(SessionEvent.builder()
			.sessionId(session.id())
			.message(new UserMessage("by orchestrator"))
			.branch("orch")
			.build());
		this.repository.appendEvent(SessionEvent.builder()
			.sessionId(session.id())
			.message(new UserMessage("by researcher"))
			.branch("orch.researcher")
			.build());
		this.repository.appendEvent(SessionEvent.builder()
			.sessionId(session.id())
			.message(new UserMessage("by writer"))
			.branch("orch.writer")
			.build());

		List<SessionEvent> forResearcher = this.repository.findEvents(session.id(),
				EventFilter.forBranch("orch.researcher"));

		assertThat(forResearcher).hasSize(3);
		assertThat(forResearcher).noneMatch(e -> "by writer".equals(e.getMessage().getText()));
	}

	// -------------------------------------------------------------------------
	// Helpers
	// -------------------------------------------------------------------------

	private Session buildSession(String userId) {
		return Session.builder().id(UUID.randomUUID().toString()).userId(userId).build();
	}

	// -------------------------------------------------------------------------
	// Spring test configuration
	// -------------------------------------------------------------------------

	@Import({ DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class })
	static class TestConfig {

		@Bean
		JdbcSessionRepository jdbcSessionRepository(DataSource dataSource) {
			return JdbcSessionRepository.builder()
				.dataSource(dataSource)
				.dialect(new H2JdbcSessionRepositoryDialect())
				.build();
		}

	}

}
