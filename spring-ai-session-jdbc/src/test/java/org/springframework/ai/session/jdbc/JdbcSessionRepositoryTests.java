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
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.compaction.SlidingWindowCompactionStrategy;
import org.springframework.ai.session.test.AbstractSessionRepositoryContractTests;
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

	@Autowired
	private DataSource dataSource;

	@BeforeEach
	void cleanUp() {
		this.jdbcTemplate.update("DELETE FROM AI_SESSION");
	}

	// -------------------------------------------------------------------------
	// Session lifecycle
	// -------------------------------------------------------------------------

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

		Session found = this.repository.findById(session.id());
		assertThat(found).isNotNull();
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

		Session found = this.repository.findById(session.id());
		assertThat(found).isNotNull();
		assertThat(found.metadata()).containsEntry("newKey", "newVal");
		assertThat(this.repository.getEventVersion(session.id())).isEqualTo(versionAfterAppend);
		assertThat(this.repository.findEvents(session.id(), EventFilter.all())).hasSize(1);
	}

	// -------------------------------------------------------------------------
	// Event append and retrieval
	// -------------------------------------------------------------------------

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
	void timestampsAreStoredAsUtcRegardlessOfJvmTimeZone() {
		DialectScenarios.timestampsAreStoredAsUtcRegardlessOfJvmTimeZone(this.repository, this.jdbcTemplate);
	}

	@Test
	void appendReplayInsideACallerTransactionKeepsItUsable() {
		DialectScenarios.appendReplayInsideACallerTransactionKeepsItUsable(this.repository, this.jdbcTemplate);
	}

	@Test
	void repeatedRecursiveSummarizationNeverReordersTheLog() {
		DialectScenarios.repeatedRecursiveSummarizationNeverReordersTheLog(this.repository);
	}

	@Test
	void storedSystemMessageSurvivesCompactionAndIsReadBackFirst() {
		SessionService service = DefaultSessionService.builder()
			.sessionRepository(this.repository)
			.allowSystemMessages(true)
			.build();
		Session session = service.create(CreateSessionRequest.builder().userId("user-sys").build());
		service.appendMessage(session.id(), new SystemMessage("Answer in French."));
		for (int i = 1; i <= 3; i++) {
			service.appendMessage(session.id(), new UserMessage("question " + i));
			service.appendMessage(session.id(), new AssistantMessage("answer " + i));
		}

		service.compact(session.id(), req -> true, SlidingWindowCompactionStrategy.builder().maxEvents(2).build());

		List<SessionEvent> active = this.repository.findEvents(session.id(), EventFilter.active());
		assertThat(active).extracting(e -> e.getMessage().getText())
			.containsExactly("Answer in French.", "question 3", "answer 3");
		assertThat(active.get(0).getMessageType()).isEqualTo(MessageType.SYSTEM);
	}

	@Test
	void compactionWithoutNewEventsDoesNotReinsertKeptRows() {
		Session session = buildSession("user-seq");
		this.repository.save(session);
		for (String text : List.of("u1", "a1", "u2", "a2", "u3", "a3")) {
			this.repository
				.appendEvent(SessionEvent.builder().sessionId(session.id()).message(new UserMessage(text)).build());
		}
		List<Long> seqsBefore = this.jdbcTemplate.queryForList(
				"SELECT seq FROM AI_SESSION_EVENT WHERE session_id = ? ORDER BY seq", Long.class, session.id());
		DefaultSessionService service = DefaultSessionService.builder().sessionRepository(this.repository).build();

		service.compact(session.id(), request -> true, SlidingWindowCompactionStrategy.builder().maxEvents(2).build());

		// Nothing was inserted, so every row keeps its seq: archiving happens in place
		assertThat(this.jdbcTemplate.queryForList("SELECT seq FROM AI_SESSION_EVENT WHERE session_id = ? ORDER BY seq",
				Long.class, session.id())).isEqualTo(seqsBefore);
		assertThat(this.repository.findEvents(session.id(), EventFilter.active()))
			.extracting(e -> e.getMessage().getText())
			.containsExactly("u3", "a3");
	}

	// -------------------------------------------------------------------------
	// Helpers
	// -------------------------------------------------------------------------

	private Session buildSession(String userId) {
		return Session.builder().id(UUID.randomUUID().toString()).userId(userId).build();
	}

	private void appendEntry(String sessionId, int n) {
		this.repository
			.appendEvent(SessionEvent.builder().sessionId(sessionId).message(new UserMessage("entry " + n)).build());
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

	/** The repository contract, run against H2. */
	@Nested
	class Contract extends AbstractSessionRepositoryContractTests {

		@Override
		protected SessionRepository createRepository() {
			return JdbcSessionRepositoryTests.this.repository;
		}

	}

}
