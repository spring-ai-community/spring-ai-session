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

package org.springframework.ai.session.test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.EventFilter.MatchMode;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.compaction.CompactionPlan;
import org.springframework.ai.session.compaction.CompactionPlan.Insert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract tests every {@link SessionRepository} implementation must pass. Subclass it,
 * return your repository from {@link #createRepository()}, and run the tests with the
 * JUnit Platform:
 *
 * <pre>{@code
 * class RedisSessionRepositoryContractTests extends AbstractSessionRepositoryContractTests {
 *
 *     &#64;Override
 *     protected SessionRepository createRepository() {
 *         return RedisSessionRepository.builder().connectionFactory(...).build();
 *     }
 *
 * }
 * }</pre>
 *
 * <p>
 * Every test creates its own sessions with random ids, so {@link #createRepository()} may
 * return a shared store. The tests cover session lifecycle, idempotent append, the read
 * contract of {@link SessionRepository#findEvents} (pushdown or
 * {@link EventFilter#apply(List)} must give the same result), cross-session reads, and the
 * ordering rules of {@link SessionRepository#applyCompaction}.
 *
 * @author Christian Tzolov
 * @since 0.10.0
 */
public abstract class AbstractSessionRepositoryContractTests {

	protected SessionRepository repository;

	/**
	 * Returns the repository under test. Called before every test.
	 * @return the repository
	 */
	protected abstract SessionRepository createRepository();

	@BeforeEach
	void setUpRepository() {
		this.repository = createRepository();
	}

	// --- Sessions ---

	@Test
	void saveAndFindByIdRoundTrip() {
		Session session = newSession("user-1");

		Session found = this.repository.findById(session.id());

		assertThat(found).isNotNull();
		assertThat(found.id()).isEqualTo(session.id());
		assertThat(found.userId()).isEqualTo("user-1");
	}

	@Test
	void findByIdReturnsNullForAnUnknownSession() {
		assertThat(this.repository.findById(UUID.randomUUID().toString())).isNull();
	}

	@Test
	void findByUserIdReturnsOnlyThatUsersSessions() {
		String alice = "alice-" + UUID.randomUUID();
		String bob = "bob-" + UUID.randomUUID();
		Session first = newSession(alice);
		Session second = newSession(alice);
		newSession(bob);

		assertThat(this.repository.findByUserId(alice)).extracting(Session::id)
			.containsExactlyInAnyOrder(first.id(), second.id());
		assertThat(this.repository.findByUserId(bob)).hasSize(1);
	}

	@Test
	void saveIfAbsentNeverOverwritesAnExistingSession() {
		String id = UUID.randomUUID().toString();

		assertThat(this.repository.saveIfAbsent(Session.builder().id(id).userId("alice").build())).isTrue();
		assertThat(this.repository.saveIfAbsent(Session.builder().id(id).userId("mallory").build())).isFalse();

		assertThat(this.repository.findById(id).userId()).isEqualTo("alice");
	}

	@Test
	void concurrentSaveIfAbsentOfTheSameIdHasExactlyOneWinner() throws Exception {
		String id = UUID.randomUUID().toString();
		int threads = 8;
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<Boolean>> results = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				String userId = "user-" + i;
				results.add(executor.submit(() -> {
					start.await();
					return this.repository.saveIfAbsent(Session.builder().id(id).userId(userId).build());
				}));
			}
			start.countDown();
			int winners = 0;
			for (Future<Boolean> result : results) {
				if (result.get(30, TimeUnit.SECONDS)) {
					winners++;
				}
			}
			assertThat(winners).isEqualTo(1);
			assertThat(this.repository.findById(id)).isNotNull();
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	void saveOfAnExistingSessionKeepsCreatedAtAndEvents() {
		String id = UUID.randomUUID().toString();
		Instant created = Instant.parse("2026-01-01T00:00:00Z");
		this.repository.save(Session.builder().id(id).userId("user-1").createdAt(created).build());
		append(id, user("kept"));

		Session updated = this.repository.save(Session.builder()
			.id(id)
			.userId("user-2")
			.createdAt(Instant.parse("2026-06-01T00:00:00Z"))
			.metadata(Map.of("key", "value"))
			.build());

		assertThat(updated.createdAt()).isEqualTo(created);
		Session found = this.repository.findById(id);
		assertThat(found.createdAt()).isEqualTo(created);
		assertThat(found.userId()).isEqualTo("user-2");
		assertThat(found.metadata()).containsEntry("key", "value");
		assertThat(texts(id, EventFilter.all())).containsExactly("kept");
	}

	@Test
	void deleteRemovesTheSessionAndItsEvents() {
		Session session = newSession("user-4");
		append(session.id(), user("gone"));

		this.repository.delete(session.id());

		assertThat(this.repository.findById(session.id())).isNull();
		assertThat(this.repository.findEvents(session.id(), EventFilter.all())).isEmpty();
	}

	@Test
	void deleteExpiredSessionsDeletesOnlyExpiredSessionsAndTheirEvents() {
		Instant now = Instant.now();
		String userId = "user-ttl-" + UUID.randomUUID();
		String expired = UUID.randomUUID().toString();
		String extended = UUID.randomUUID().toString();
		String noTtl = UUID.randomUUID().toString();
		this.repository.save(Session.builder().id(expired).userId(userId).expiresAt(now.minusSeconds(60)).build());
		this.repository.save(Session.builder().id(extended).userId(userId).expiresAt(now.plusSeconds(60)).build());
		this.repository.save(Session.builder().id(noTtl).userId(userId).build());
		append(expired, user("gone"));
		append(extended, user("kept"));

		int deleted = this.repository.deleteExpiredSessions(now);

		assertThat(deleted).isGreaterThanOrEqualTo(1);
		assertThat(this.repository.findById(expired)).isNull();
		assertThat(this.repository.findEvents(expired, EventFilter.all())).isEmpty();
		assertThat(this.repository.findById(extended)).isNotNull();
		assertThat(this.repository.findById(noTtl)).isNotNull();
		assertThat(texts(extended, EventFilter.all())).containsExactly("kept");
	}

	// --- Append ---

	@Test
	void appendedEventsAreReturnedInAppendOrder() {
		Session session = newSession("user-order");
		for (int i = 1; i <= 5; i++) {
			append(session.id(), user("msg-" + i));
		}

		assertThat(texts(session.id(), EventFilter.all())).containsExactly("msg-1", "msg-2", "msg-3", "msg-4",
				"msg-5");
	}

	@Test
	void appendOfTheSameEventIdIsAnIdempotentReplay() {
		Session session = newSession("user-replay");
		SessionEvent event = SessionEvent.builder()
			.id(UUID.randomUUID().toString())
			.sessionId(session.id())
			.message(new UserMessage("once"))
			.build();

		this.repository.appendEvent(event);
		long versionAfterFirst = this.repository.getEventVersion(session.id());
		this.repository.appendEvent(event);

		assertThat(texts(session.id(), EventFilter.all())).containsExactly("once");
		assertThat(this.repository.getEventVersion(session.id())).isEqualTo(versionAfterFirst);
	}

	@Test
	void appendWithAnEventIdOfAnotherSessionIsNeverSilentlyDropped() {
		// A store with a global id namespace (JDBC) rejects the clash with
		// IllegalStateException; a store keyed per session stores the event. Either way
		// the event must not be treated as a replay and dropped.
		Session owner = newSession("user-a");
		Session other = newSession("user-b");
		String eventId = UUID.randomUUID().toString();
		this.repository
			.appendEvent(SessionEvent.builder().id(eventId).sessionId(owner.id()).message(user("mine")).build());
		SessionEvent clash = SessionEvent.builder().id(eventId).sessionId(other.id()).message(user("theirs")).build();

		boolean rejected;
		try {
			this.repository.appendEvent(clash);
			rejected = false;
		}
		catch (IllegalStateException ex) {
			rejected = true;
		}

		assertThat(texts(owner.id(), EventFilter.all())).containsExactly("mine");
		if (rejected) {
			assertThat(this.repository.findEvents(other.id(), EventFilter.all())).isEmpty();
		}
		else {
			assertThat(texts(other.id(), EventFilter.all())).containsExactly("theirs");
		}
	}

	@Test
	void appendToAnUnknownSessionIsRejected() {
		SessionEvent event = SessionEvent.builder()
			.sessionId(UUID.randomUUID().toString())
			.message(user("orphan"))
			.build();

		assertThatThrownBy(() -> this.repository.appendEvent(event)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void eventVersionStartsAtZeroAndIncrementsPerNewEvent() {
		Session session = newSession("user-version");

		assertThat(this.repository.getEventVersion(session.id())).isZero();
		append(session.id(), user("a"));
		assertThat(this.repository.getEventVersion(session.id())).isEqualTo(1L);
		append(session.id(), user("b"));
		assertThat(this.repository.getEventVersion(session.id())).isEqualTo(2L);
	}

	// --- Reads ---

	@Test
	void findEventsForAnUnknownSessionReturnsAnEmptyList() {
		assertThat(this.repository.findEvents(UUID.randomUUID().toString(), EventFilter.all())).isEmpty();
	}

	@Test
	void messageTypesFilterKeepsOnlyThoseTypes() {
		Session session = newSession("user-types");
		append(session.id(), user("q"), assistant("a"), user("q2"));

		EventFilter filter = EventFilter.builder().messageTypes(Set.of(MessageType.ASSISTANT)).build();

		assertThat(texts(session.id(), filter)).containsExactly("a");
	}

	@Test
	void timeRangeFilterIsInclusive() {
		Session session = newSession("user-time");
		Instant base = Instant.parse("2026-03-01T10:00:00Z");
		for (int i = 0; i < 4; i++) {
			this.repository.appendEvent(SessionEvent.builder()
				.sessionId(session.id())
				.timestamp(base.plusSeconds(i * 60L))
				.message(user("t" + i))
				.build());
		}

		EventFilter filter = EventFilter.builder().from(base.plusSeconds(60)).to(base.plusSeconds(120)).build();

		assertThat(texts(session.id(), filter)).containsExactly("t1", "t2");
	}

	@Test
	void excludeSyntheticHidesSummaryEvents() {
		Session session = newSession("user-synthetic");
		append(session.id(), user("real"));
		this.repository.appendEvent(synthetic(session.id(), new AssistantMessage("summary")));

		assertThat(texts(session.id(), EventFilter.realOnly())).containsExactly("real");
		assertThat(texts(session.id(), EventFilter.all())).containsExactly("real", "summary");
	}

	@Test
	void excludeArchivedHidesArchivedEvents() {
		Session session = newSession("user-archived");
		append(session.id(), user("old"), user("new"));
		List<SessionEvent> log = this.repository.findEvents(session.id(), EventFilter.all());
		apply(session.id(), plan(Set.of(log.get(0).getId()), List.of()));

		assertThat(texts(session.id(), EventFilter.active())).containsExactly("new");
		assertThat(texts(session.id(), EventFilter.all())).containsExactly("old", "new");
	}

	@Test
	void keywordSearchIsCaseInsensitiveAndMatchesWildcardCharactersLiterally() {
		Session session = newSession("user-keyword");
		for (String text : List.of("100% done", "1000 done", "snake_case", "snakeXcase", "a!b", "ab", "Hello World")) {
			append(session.id(), user(text));
		}

		assertThat(texts(session.id(), EventFilter.keywordSearch("100%"))).containsExactly("100% done");
		assertThat(texts(session.id(), EventFilter.keywordSearch("_case"))).containsExactly("snake_case");
		assertThat(texts(session.id(), EventFilter.keywordSearch("a!b"))).containsExactly("a!b");
		assertThat(texts(session.id(), EventFilter.keywordSearch("hello"))).containsExactly("Hello World");
	}

	@Test
	void keywordsSearchCombinesTermsWithAnyOrAll() {
		Session session = newSession("user-keywords");
		append(session.id(), user("100% done"), user("snake_case"), user("100% snake_case"), user("nothing"));

		assertThat(texts(session.id(), EventFilter.keywordsSearch(List.of("0%", "e_c"), MatchMode.ANY)))
			.containsExactly("100% done", "snake_case", "100% snake_case");
		assertThat(texts(session.id(), EventFilter.keywordsSearch(List.of("0%", "e_c"), MatchMode.ALL)))
			.containsExactly("100% snake_case");
	}

	@Test
	void patternSearchMatchesWithTheJavaRegex() {
		Session session = newSession("user-pattern");
		append(session.id(), user("msg-1"), user("msg-2"), user("msg-13"));

		assertThat(texts(session.id(), EventFilter.patternSearch(Pattern.compile("^msg-1\\d?$"))))
			.containsExactly("msg-1", "msg-13");
	}

	@Test
	void lastNReturnsTheNewestEventsInChronologicalOrder() {
		Session session = newSession("user-lastn");
		for (int i = 1; i <= 5; i++) {
			append(session.id(), user("msg-" + i));
		}

		assertThat(texts(session.id(), EventFilter.lastN(2))).containsExactly("msg-4", "msg-5");
	}

	@Test
	void pagesAreZeroIndexedInChronologicalOrder() {
		Session session = newSession("user-page");
		for (int i = 1; i <= 5; i++) {
			append(session.id(), user("msg-" + i));
		}

		assertThat(texts(session.id(), EventFilter.builder().page(0).pageSize(2).build())).containsExactly("msg-1",
				"msg-2");
		assertThat(texts(session.id(), EventFilter.builder().page(1).pageSize(2).build())).containsExactly("msg-3",
				"msg-4");
		assertThat(texts(session.id(), EventFilter.builder().page(2).pageSize(2).build())).containsExactly("msg-5");
		assertThat(texts(session.id(), EventFilter.builder().page(3).pageSize(2).build())).isEmpty();
	}

	@Test
	void aHugePageNumberReturnsAnEmptyPage() {
		Session session = newSession("user-huge-page");
		append(session.id(), user("hello"));

		EventFilter filter = EventFilter.builder().page(Integer.MAX_VALUE).pageSize(10).build();

		assertThat(this.repository.findEvents(session.id(), filter)).isEmpty();
	}

	@Test
	void findEventsByUserIdOrdersByTimestampAcrossSessionsAndPages() {
		String userId = "user-cross-" + UUID.randomUUID();
		Session first = newSession(userId);
		Session second = newSession(userId);
		Session other = newSession("someone-else-" + UUID.randomUUID());
		Instant base = Instant.parse("2026-04-01T00:00:00Z");
		appendAt(first.id(), base.plusSeconds(10), "first-1");
		appendAt(second.id(), base.plusSeconds(20), "second-1");
		appendAt(first.id(), base.plusSeconds(30), "first-2");
		appendAt(second.id(), base.plusSeconds(40), "second-2");
		appendAt(other.id(), base.plusSeconds(25), "other");

		assertThat(this.repository.findEventsByUserId(userId, EventFilter.all()))
			.extracting(e -> e.getMessage().getText())
			.containsExactly("first-1", "second-1", "first-2", "second-2");
		assertThat(this.repository.findEventsByUserId(userId, EventFilter.builder().page(1).pageSize(3).build()))
			.extracting(e -> e.getMessage().getText())
			.containsExactly("second-2");
		assertThat(this.repository.findEventsByUserId(userId, EventFilter.keywordSearch("second")))
			.extracting(e -> e.getMessage().getText())
			.containsExactly("second-1", "second-2");
	}

	// --- Compaction ---

	@Test
	void archivingFlagsEventsInPlaceAndIncrementsTheVersion() {
		Session session = newSession("user-archive");
		append(session.id(), user("u1"), assistant("a1"), user("u2"), assistant("a2"));
		List<SessionEvent> log = this.repository.findEvents(session.id(), EventFilter.all());
		long version = this.repository.getEventVersion(session.id());

		boolean applied = this.repository.applyCompaction(session.id(),
				plan(Set.of(log.get(0).getId(), log.get(1).getId()), List.of()), version);

		assertThat(applied).isTrue();
		assertThat(this.repository.getEventVersion(session.id())).isEqualTo(version + 1);
		List<SessionEvent> after = this.repository.findEvents(session.id(), EventFilter.all());
		assertThat(after).extracting(e -> e.getMessage().getText()).containsExactly("u1", "a1", "u2", "a2");
		assertThat(after).extracting(SessionEvent::isArchived).containsExactly(true, true, false, false);
		assertThat(texts(session.id(), EventFilter.active())).containsExactly("u2", "a2");
	}

	@Test
	void aReplacedSummaryIsArchivedInPlaceNotRemoved() {
		Session session = newSession("user-replaced-summary");
		append(session.id(), user("u1"));
		this.repository.appendEvent(synthetic(session.id(), new AssistantMessage("old summary")));
		append(session.id(), user("u2"));
		List<SessionEvent> log = this.repository.findEvents(session.id(), EventFilter.all());

		apply(session.id(), plan(Set.of(log.get(1).getId()), List.of()));

		assertThat(texts(session.id(), EventFilter.all())).containsExactly("u1", "old summary", "u2");
		assertThat(texts(session.id(), EventFilter.active())).containsExactly("u1", "u2");
	}

	@Test
	void anInsertGoesImmediatelyBeforeItsAnchor() {
		Session session = newSession("user-insert");
		append(session.id(), user("u1"), assistant("a1"), user("u2"), assistant("a2"));
		List<SessionEvent> log = this.repository.findEvents(session.id(), EventFilter.all());
		SessionEvent summary = synthetic(session.id(), new AssistantMessage("summary"));

		apply(session.id(), plan(Set.of(log.get(0).getId(), log.get(1).getId()),
				List.of(Insert.before(log.get(2).getId(), List.of(summary)))));

		assertThat(texts(session.id(), EventFilter.all())).containsExactly("u1", "a1", "summary", "u2", "a2");
		assertThat(texts(session.id(), EventFilter.active())).containsExactly("summary", "u2", "a2");
	}

	@Test
	void anInsertWithoutAnAnchorIsAppended() {
		Session session = newSession("user-append");
		append(session.id(), user("u1"), assistant("a1"));
		SessionEvent added = synthetic(session.id(), new AssistantMessage("added"));

		apply(session.id(), plan(Set.of(), List.of(Insert.atEnd(List.of(added)))));

		assertThat(texts(session.id(), EventFilter.all())).containsExactly("u1", "a1", "added");
	}

	@Test
	void twoInsertsInOnePlanEachGoBeforeTheirOwnAnchor() {
		Session session = newSession("user-two-inserts");
		append(session.id(), user("u1"), user("u2"), user("u3"));
		List<SessionEvent> log = this.repository.findEvents(session.id(), EventFilter.all());
		SessionEvent x = synthetic(session.id(), new AssistantMessage("x"));
		SessionEvent y = synthetic(session.id(), new AssistantMessage("y"));

		apply(session.id(), plan(Set.of(), List.of(Insert.before(log.get(1).getId(), List.of(x)),
				Insert.before(log.get(2).getId(), List.of(y)))));

		assertThat(texts(session.id(), EventFilter.all())).containsExactly("u1", "x", "u2", "y", "u3");
	}

	@Test
	void repeatedSummarizationPlansNeverReorderTheLog() {
		Session session = newSession("user-summaries");
		String id = session.id();

		// Pass 1: the system message stored inside turn 1 stays in place, active
		append(id, user("u1"), system("sys-1"), assistant("a1"), user("u2"), assistant("a2"), user("u3"),
				assistant("a3"), user("u4"), assistant("a4"), user("u5"), assistant("a5"));
		List<SessionEvent> active = this.repository.findEvents(id, EventFilter.active());
		List<SessionEvent> summary1 = summaryTurn(id, "summary 1");
		apply(id, plan(ids(active, "u1", "a1", "u2", "a2", "u3", "a3"),
				List.of(Insert.before(idOf(active, "u4"), summary1))));

		assertThat(labels(this.repository.findEvents(id, EventFilter.all()))).containsExactly("u1", "sys-1", "a1",
				"u2", "a2", "u3", "a3", "Σ?", "Σ:summary 1", "u4", "a4", "u5", "a5");
		assertThat(labels(this.repository.findEvents(id, EventFilter.active()))).containsExactly("sys-1", "Σ?",
				"Σ:summary 1", "u4", "a4", "u5", "a5");

		// Pass 2: the previous summary is archived in place, the new one precedes the kept
		// window
		append(id, user("u6"), assistant("a6"), user("u7"), assistant("a7"));
		active = this.repository.findEvents(id, EventFilter.active());
		apply(id, plan(ids(active, "Σ?", "Σ:summary 1", "u4", "a4", "u5", "a5"),
				List.of(Insert.before(idOf(active, "u6"), summaryTurn(id, "summary 2")))));

		assertThat(labels(this.repository.findEvents(id, EventFilter.all()))).containsExactly("u1", "sys-1", "a1",
				"u2", "a2", "u3", "a3", "Σ?", "Σ:summary 1", "u4", "a4", "u5", "a5", "Σ?", "Σ:summary 2", "u6", "a6",
				"u7", "a7");
		assertThat(labels(this.repository.findEvents(id, EventFilter.active()))).containsExactly("sys-1", "Σ?",
				"Σ:summary 2", "u6", "a6", "u7", "a7");

		// Pass 3: superseded system messages are archived in place, including one inside
		// the kept window, after the inserted summary
		append(id, user("u8"), system("sys-2"), assistant("a8"), user("u9"), system("sys-3"), assistant("a9"));
		active = this.repository.findEvents(id, EventFilter.active());
		apply(id, plan(ids(active, "Σ?", "Σ:summary 2", "u6", "a6", "u7", "a7", "sys-1", "sys-2"),
				List.of(Insert.before(idOf(active, "u8"), summaryTurn(id, "summary 3")))));

		List<SessionEvent> log = this.repository.findEvents(id, EventFilter.all());
		assertThat(labels(log)).containsExactly("u1", "sys-1", "a1", "u2", "a2", "u3", "a3", "Σ?", "Σ:summary 1",
				"u4", "a4", "u5", "a5", "Σ?", "Σ:summary 2", "u6", "a6", "u7", "a7", "Σ?", "Σ:summary 3", "u8",
				"sys-2", "a8", "u9", "sys-3", "a9");
		assertThat(labels(log.stream().filter(SessionEvent::isArchived).toList())).containsExactly("u1", "sys-1",
				"a1", "u2", "a2", "u3", "a3", "Σ?", "Σ:summary 1", "u4", "a4", "u5", "a5", "Σ?", "Σ:summary 2",
				"u6", "a6", "u7", "a7", "sys-2");
		assertThat(labels(this.repository.findEvents(id, EventFilter.active()))).containsExactly("Σ?", "Σ:summary 3",
				"u8", "a8", "u9", "sys-3", "a9");
	}

	@Test
	void aStaleVersionIsRejectedAndChangesNothing() {
		Session session = newSession("user-stale");
		append(session.id(), user("u1"));
		List<SessionEvent> log = this.repository.findEvents(session.id(), EventFilter.all());
		long version = this.repository.getEventVersion(session.id());
		SessionEvent summary = synthetic(session.id(), new AssistantMessage("should-not-land"));

		boolean applied = this.repository.applyCompaction(session.id(),
				plan(Set.of(log.get(0).getId()), List.of(Insert.atEnd(List.of(summary)))),
				version - 1);

		assertThat(applied).isFalse();
		assertThat(this.repository.getEventVersion(session.id())).isEqualTo(version);
		List<SessionEvent> after = this.repository.findEvents(session.id(), EventFilter.all());
		assertThat(after).extracting(e -> e.getMessage().getText()).containsExactly("u1");
		assertThat(after.get(0).isArchived()).isFalse();
	}

	@Test
	void anUnknownArchiveIdIsRejectedAndChangesNothing() {
		Session session = newSession("user-unknown-archive");
		append(session.id(), user("u1"));
		long version = this.repository.getEventVersion(session.id());
		CompactionPlan plan = plan(Set.of(UUID.randomUUID().toString()), List.of());

		assertThatThrownBy(() -> this.repository.applyCompaction(session.id(), plan, version))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(this.repository.getEventVersion(session.id())).isEqualTo(version);
		assertThat(texts(session.id(), EventFilter.all())).containsExactly("u1");
	}

	@Test
	void anUnknownAnchorIdIsRejectedAndChangesNothing() {
		Session session = newSession("user-unknown-anchor");
		append(session.id(), user("u1"));
		long version = this.repository.getEventVersion(session.id());
		SessionEvent summary = synthetic(session.id(), new AssistantMessage("summary"));
		CompactionPlan plan = plan(Set.of(),
				List.of(Insert.before(UUID.randomUUID().toString(), List.of(summary))));

		assertThatThrownBy(() -> this.repository.applyCompaction(session.id(), plan, version))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(this.repository.getEventVersion(session.id())).isEqualTo(version);
		assertThat(texts(session.id(), EventFilter.all())).containsExactly("u1");
	}

	@Test
	void applyCompactionOnAnUnknownSessionIsRejected() {
		CompactionPlan plan = plan(Set.of(), List.of());

		assertThatThrownBy(() -> this.repository.applyCompaction(UUID.randomUUID().toString(), plan, 0L))
			.isInstanceOf(IllegalArgumentException.class);
	}

	// --- Helpers ---

	/** Saves a new session with a random id for the given user. */
	protected Session newSession(String userId) {
		return this.repository.save(Session.builder().id(UUID.randomUUID().toString()).userId(userId).build());
	}

	/** Appends each message as its own event. */
	protected void append(String sessionId, Message... messages) {
		for (Message message : messages) {
			this.repository.appendEvent(SessionEvent.builder().sessionId(sessionId).message(message).build());
		}
	}

	/** Appends a user message with an explicit timestamp. */
	protected void appendAt(String sessionId, Instant timestamp, String text) {
		this.repository
			.appendEvent(SessionEvent.builder().sessionId(sessionId).timestamp(timestamp).message(user(text)).build());
	}

	/** Applies the plan at the current version and asserts it was accepted. */
	protected void apply(String sessionId, CompactionPlan plan) {
		assertThat(this.repository.applyCompaction(sessionId, plan, this.repository.getEventVersion(sessionId)))
			.isTrue();
	}

	/** The texts of the events matching the filter, in the order returned. */
	protected List<String> texts(String sessionId, EventFilter filter) {
		return this.repository.findEvents(sessionId, filter).stream().map(e -> e.getMessage().getText()).toList();
	}

	protected static CompactionPlan plan(Set<String> archiveIds, List<Insert> inserts) {
		return new CompactionPlan(archiveIds, inserts);
	}

	/** A synthetic event, as a compaction strategy would produce it. */
	protected static SessionEvent synthetic(String sessionId, Message message) {
		return SessionEvent.builder()
			.sessionId(sessionId)
			.message(message)
			.metadata(SessionEvent.METADATA_SYNTHETIC, true)
			.build();
	}

	/** A synthetic shadow-prompt + summary pair, as the summarizing strategy produces. */
	protected static List<SessionEvent> summaryTurn(String sessionId, String summary) {
		return List.of(synthetic(sessionId, new UserMessage("Summarize the conversation we had so far.")),
				synthetic(sessionId, new AssistantMessage(summary)));
	}

	/** Event labels: the text, "Σ?" for a synthetic shadow prompt, "Σ:text" for a summary. */
	protected static List<String> labels(List<SessionEvent> events) {
		return events.stream().map(e -> {
			if (!e.isSynthetic()) {
				return e.getMessage().getText();
			}
			return (e.getMessageType() == MessageType.USER) ? "Σ?" : "Σ:" + e.getMessage().getText();
		}).toList();
	}

	/** The ids of the events with the given labels. */
	protected static Set<String> ids(List<SessionEvent> events, String... labels) {
		List<String> wanted = List.of(labels);
		List<String> eventLabels = labels(events);
		Set<String> ids = new LinkedHashSet<>();
		for (int i = 0; i < events.size(); i++) {
			if (wanted.contains(eventLabels.get(i))) {
				ids.add(events.get(i).getId());
			}
		}
		assertThat(ids).hasSize(labels.length);
		return ids;
	}

	/** The id of the single event with the given label. */
	protected static String idOf(List<SessionEvent> events, String label) {
		return ids(events, label).iterator().next();
	}

	protected static Message user(String text) {
		return new UserMessage(text);
	}

	protected static Message assistant(String text) {
		return new AssistantMessage(text);
	}

	protected static Message system(String text) {
		return new SystemMessage(text);
	}

}
