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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.TimeZone;
import java.util.UUID;

import org.mockito.Answers;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.compaction.RecursiveSummarizationCompactionStrategy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Shared assertions for database-specific SQL behaviour, run against every supported
 * database: {@code LIKE}-based filters treat {@code %}, {@code _} and the {@code !} escape
 * character literally, and timestamps are stored as UTC independent of the JVM time zone.
 */
final class DialectScenarios {

	private DialectScenarios() {
	}

	static void timestampsAreStoredAsUtcRegardlessOfJvmTimeZone(JdbcSessionRepository repository,
			JdbcTemplate jdbcTemplate) {
		TimeZone original = TimeZone.getDefault();
		TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
		try {
			Instant instant = Instant.parse("2026-03-08T06:30:00.123456Z");
			String sessionId = UUID.randomUUID().toString();
			repository.save(Session.builder().id(sessionId).userId("user-tz").createdAt(instant).expiresAt(instant).build());
			repository.appendEvent(SessionEvent.builder()
				.sessionId(sessionId)
				.timestamp(instant)
				.message(new UserMessage("tz"))
				.build());

			LocalDateTime utcWallClock = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
			assertThat(jdbcTemplate.queryForObject("SELECT created_at FROM AI_SESSION WHERE id = ?",
					LocalDateTime.class, sessionId))
				.isEqualTo(utcWallClock);

			Session found = repository.findById(sessionId);
			assertThat(found.createdAt()).isEqualTo(instant);
			assertThat(found.expiresAt()).isEqualTo(instant);
			assertThat(repository.findEvents(sessionId, EventFilter.all()).get(0).getTimestamp()).isEqualTo(instant);
			assertThat(repository.findEvents(sessionId, EventFilter.builder().from(instant).to(instant).build()))
				.hasSize(1);
			// The expiry comparison uses the same UTC wall-clock value
			assertThat(repository.deleteExpiredSessions(instant)).isZero();
			assertThat(repository.deleteExpiredSessions(instant.plusMillis(1))).isEqualTo(1);
		}
		finally {
			TimeZone.setDefault(original);
		}
	}

	private static String newSession(JdbcSessionRepository repository) {
		String sessionId = UUID.randomUUID().toString();
		repository.save(Session.builder().id(sessionId).userId("user-like").build());
		return sessionId;
	}

	/**
	 * Three recursive summarization passes never reorder the log: archived events are
	 * flagged in place, a kept system message stays where it was stored, and each summary
	 * goes right before the kept window while the replaced one is archived in place. Pass 3 archives a superseded system message that
	 * sits after the inserted summary, which exercises the re-inserted tail.
	 */
	static void repeatedRecursiveSummarizationNeverReordersTheLog(JdbcSessionRepository repository) {
		ChatClient chatClient = mock(ChatClient.class, Answers.RETURNS_DEEP_STUBS);
		given(chatClient.prompt().system(anyString()).user(anyString()).call().content()).willReturn("summary 1",
				"summary 2", "summary 3");
		RecursiveSummarizationCompactionStrategy strategy = RecursiveSummarizationCompactionStrategy
			.builder(chatClient)
			.maxEventsToKeep(4)
			.build();
		SessionService service = DefaultSessionService.builder()
			.sessionRepository(repository)
			.allowSystemMessages(true)
			.build();
		String id = service.create(CreateSessionRequest.builder().userId("user-order").build()).id();

		appendAll(service, id, "u1", "sys-1", "a1", "u2", "a2", "u3", "a3", "u4", "a4", "u5", "a5");
		service.compact(id, request -> true, strategy);
		assertThat(labels(service.getEvents(id))).containsExactly("u1", "sys-1", "a1", "u2", "a2", "u3", "a3", "Σ?",
				"Σ:summary 1", "u4", "a4", "u5", "a5");
		assertThat(labels(service.getEvents(id, EventFilter.active()))).containsExactly("sys-1", "Σ?",
				"Σ:summary 1", "u4", "a4", "u5", "a5");

		appendAll(service, id, "u6", "a6", "u7", "a7");
		service.compact(id, request -> true, strategy);
		assertThat(labels(service.getEvents(id))).containsExactly("u1", "sys-1", "a1", "u2", "a2", "u3", "a3", "Σ?",
				"Σ:summary 1", "u4", "a4", "u5", "a5", "Σ?", "Σ:summary 2", "u6", "a6", "u7", "a7");

		appendAll(service, id, "u8", "sys-2", "a8", "u9", "sys-3", "a9");
		service.compact(id, request -> true, strategy);
		List<SessionEvent> log = service.getEvents(id);
		assertThat(labels(log)).containsExactly("u1", "sys-1", "a1", "u2", "a2", "u3", "a3", "Σ?", "Σ:summary 1",
				"u4", "a4", "u5", "a5", "Σ?", "Σ:summary 2", "u6", "a6", "u7", "a7", "Σ?", "Σ:summary 3", "u8",
				"sys-2", "a8", "u9", "sys-3", "a9");
		assertThat(labels(log.stream().filter(SessionEvent::isArchived).toList())).containsExactly("u1", "sys-1",
				"a1", "u2", "a2", "u3", "a3", "Σ?", "Σ:summary 1", "u4", "a4", "u5", "a5", "Σ?", "Σ:summary 2",
				"u6", "a6", "u7", "a7", "sys-2");
		assertThat(labels(service.getEvents(id, EventFilter.active()))).containsExactly("Σ?", "Σ:summary 3", "u8",
				"a8", "u9", "sys-3", "a9");
	}

	/**
	 * A replayed append inside a caller-managed transaction must not break it: the replay
	 * is detected before inserting, so no duplicate-key error marks the shared
	 * transaction rollback-only (or, on PostgreSQL, aborts it).
	 */
	static void appendReplayInsideACallerTransactionKeepsItUsable(JdbcSessionRepository repository,
			JdbcTemplate jdbcTemplate) {
		String sessionId = newSession(repository);
		SessionEvent event = SessionEvent.builder().sessionId(sessionId).message(new UserMessage("once")).build();
		repository.appendEvent(event);
		long version = repository.getEventVersion(sessionId);
		TransactionTemplate callerTransaction = new TransactionTemplate(
				new DataSourceTransactionManager(Objects.requireNonNull(jdbcTemplate.getDataSource())));

		callerTransaction.executeWithoutResult(status -> {
			repository.appendEvent(event);
			append(repository, sessionId, "after");
		});

		assertThat(texts(repository, sessionId, EventFilter.all())).containsExactly("once", "after");
		// The replay did not count as an appended event
		assertThat(repository.getEventVersion(sessionId)).isEqualTo(version + 1);
	}

	/** Appends messages by label: "u…" user, "a…" assistant, "sys…" system. */
	private static void appendAll(SessionService service, String sessionId, String... labels) {
		for (String label : labels) {
			Message message = label.startsWith("sys") ? new SystemMessage(label)
					: label.startsWith("u") ? new UserMessage(label) : new AssistantMessage(label);
			service.appendMessage(sessionId, message);
		}
	}

	/** The text, "Σ?" for a synthetic shadow prompt and "Σ:text" for a synthetic summary. */
	private static List<String> labels(List<SessionEvent> events) {
		return events.stream().map(e -> {
			if (!e.isSynthetic()) {
				return e.getMessage().getText();
			}
			return (e.getMessageType() == MessageType.USER) ? "Σ?" : "Σ:" + e.getMessage().getText();
		}).toList();
	}

	private static void append(JdbcSessionRepository repository, String sessionId, String text) {
		repository.appendEvent(SessionEvent.builder().sessionId(sessionId).message(new UserMessage(text)).build());
	}

	private static List<String> texts(JdbcSessionRepository repository, String sessionId, EventFilter filter) {
		return repository.findEvents(sessionId, filter).stream().map(e -> e.getMessage().getText()).toList();
	}

}
