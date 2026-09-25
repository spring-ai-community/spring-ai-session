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
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.EventFilter.MatchMode;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared assertions for database-specific SQL behaviour, run against every supported
 * database: {@code LIKE}-based filters treat {@code %}, {@code _} and the {@code !} escape
 * character literally, and timestamps are stored as UTC independent of the JVM time zone.
 */
final class DialectScenarios {

	private DialectScenarios() {
	}

	static void keywordWildcardCharactersMatchLiterally(JdbcSessionRepository repository) {
		String sessionId = newSession(repository);
		for (String text : List.of("100% done", "1000 done", "snake_case", "snakeXcase", "a!b", "ab")) {
			append(repository, sessionId, text, null);
		}

		assertThat(texts(repository, sessionId, EventFilter.keywordSearch("100%"))).containsExactly("100% done");
		assertThat(texts(repository, sessionId, EventFilter.keywordSearch("_case"))).containsExactly("snake_case");
		assertThat(texts(repository, sessionId, EventFilter.keywordSearch("a!b"))).containsExactly("a!b");
		assertThat(texts(repository, sessionId, EventFilter.keywordsSearch(List.of("0%", "e_c"), MatchMode.ANY)))
			.containsExactly("100% done", "snake_case");
	}

	static void branchWildcardCharactersMatchLiterally(JdbcSessionRepository repository) {
		String sessionId = newSession(repository);
		append(repository, sessionId, "root", null);
		append(repository, sessionId, "underscore", "a_b");
		append(repository, sessionId, "percent", "a%");

		// "a_b" / "a%" must not act as wildcards matching the unrelated branch "axb.c".
		assertThat(texts(repository, sessionId, EventFilter.forBranch("axb.c"))).containsExactly("root");
		assertThat(texts(repository, sessionId, EventFilter.forBranch("a_b.c"))).containsExactly("root",
				"underscore");
		assertThat(texts(repository, sessionId, EventFilter.forBranch("a%.child"))).containsExactly("root",
				"percent");
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
			assertThat(repository.findExpiredSessionIds(instant.plusMillis(1))).contains(sessionId);
			assertThat(repository.findExpiredSessionIds(instant)).doesNotContain(sessionId);
		}
		finally {
			TimeZone.setDefault(original);
		}
	}

	static void upsertKeepsCreatedAtAndEvents(JdbcSessionRepository repository) {
		String sessionId = UUID.randomUUID().toString();
		Instant created = Instant.parse("2026-01-01T00:00:00Z");
		repository.save(Session.builder().id(sessionId).userId("user-1").createdAt(created).build());
		append(repository, sessionId, "kept", null);

		Instant laterExpiry = Instant.parse("2027-01-01T00:00:00Z");
		Session saved = repository.save(Session.builder()
			.id(sessionId)
			.userId("user-2")
			.createdAt(Instant.parse("2026-06-01T00:00:00Z"))
			.expiresAt(laterExpiry)
			.build());

		assertThat(saved.createdAt()).as("save returns the stored session").isEqualTo(created);
		Session found = repository.findById(sessionId);
		assertThat(found.createdAt()).isEqualTo(created);
		assertThat(found.userId()).isEqualTo("user-2");
		assertThat(found.expiresAt()).isEqualTo(laterExpiry);
		assertThat(texts(repository, sessionId, EventFilter.all())).containsExactly("kept");
	}

	static void concurrentCreatesOfSameIdHaveExactlyOneWinner(JdbcSessionRepository repository) throws Exception {
		String sessionId = UUID.randomUUID().toString();
		int callers = 8;
		ExecutorService executor = Executors.newFixedThreadPool(callers);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<Boolean>> results = new ArrayList<>();
			for (int i = 0; i < callers; i++) {
				String userId = "user-" + i;
				results.add(executor.submit(() -> {
					start.await();
					return repository.saveIfAbsent(Session.builder().id(sessionId).userId(userId).build());
				}));
			}
			start.countDown();
			List<String> winners = new ArrayList<>();
			for (int i = 0; i < callers; i++) {
				if (results.get(i).get(30, TimeUnit.SECONDS)) {
					winners.add("user-" + i);
				}
			}
			assertThat(winners).hasSize(1);
			assertThat(repository.findById(sessionId).userId()).isEqualTo(winners.get(0));
		}
		finally {
			executor.shutdownNow();
		}
	}

	private static String newSession(JdbcSessionRepository repository) {
		String sessionId = UUID.randomUUID().toString();
		repository.save(Session.builder().id(sessionId).userId("user-like").build());
		return sessionId;
	}

	private static void append(JdbcSessionRepository repository, String sessionId, String text, String branch) {
		repository
			.appendEvent(SessionEvent.builder().sessionId(sessionId).message(new UserMessage(text)).branch(branch).build());
	}

	private static List<String> texts(JdbcSessionRepository repository, String sessionId, EventFilter filter) {
		return repository.findEvents(sessionId, filter).stream().map(e -> e.getMessage().getText()).toList();
	}

}
