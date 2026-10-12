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
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.part.OpaquePayload;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolResultPart;
import org.springframework.ai.content.Media;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.EventType;
import org.springframework.ai.session.EventFilter.MatchMode;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.compaction.CompactionBoundary;
import org.springframework.util.MimeTypeUtils;

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
 * {@link EventFilter#apply(List)} must give the same result, including a {@code lastN}
 * window that keeps turns whole), cross-session reads, the atomic
 * {@link SessionRepository#appendEvents} and the compacted view a boundary defines.
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
	void compactedViewHidesCoveredEvents() {
		Session session = newSession("user-covered");
		append(session.id(), user("old"), user("new"));
		List<SessionEvent> log = this.repository.findEvents(session.id(), EventFilter.all());
		compact(session.id(), List.of(), List.of(), List.of(log.get(1)));

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

	// --- Windows keep turns whole ---

	@Test
	void lastNNeverSplitsAToolCallFromItsResults() {
		Session session = newSession("user-tool-window");
		append(session.id(), user("u1"), assistant("a1"), user("u2"), toolCall("call-1", "get_weather"),
				toolResponse("call-1", "get_weather", "22C"), assistant("a2"));

		// The cut lands on the tool result, the tool call, or the final reply: the whole
		// turn comes back every time, so a tool call is never returned without its result
		assertThat(labels(this.repository.findEvents(session.id(), EventFilter.lastN(1))))
			.containsExactly("u2", "A[get_weather]", "T[get_weather]", "a2");
		assertThat(labels(this.repository.findEvents(session.id(), EventFilter.lastN(2))))
			.containsExactly("u2", "A[get_weather]", "T[get_weather]", "a2");
		assertThat(labels(this.repository.findEvents(session.id(), EventFilter.lastN(3))))
			.containsExactly("u2", "A[get_weather]", "T[get_weather]", "a2");
		assertThat(labels(this.repository.findEvents(session.id(), EventFilter.lastN(4))))
			.containsExactly("u2", "A[get_weather]", "T[get_weather]", "a2");
		assertThat(labels(this.repository.findEvents(session.id(), EventFilter.lastN(5))))
			.containsExactly("u1", "a1", "u2", "A[get_weather]", "T[get_weather]", "a2");
	}

	@Test
	void lastNNeverSplitsASummaryPair() {
		Session session = newSession("user-summary-window");
		String id = session.id();
		summaryTurn(id, "summary").forEach(this.repository::appendEvent);
		append(id, user("u3"), assistant("a3"));

		assertThat(labels(this.repository.findEvents(id, EventFilter.lastN(3)))).containsExactly("Σ?", "Σ:summary",
				"u3", "a3");
	}

	@Test
	void lastNLandingOnAnAssistantExtendsToTheTurnStart() {
		Session session = newSession("user-assistant-cut");
		append(session.id(), user("u1"), assistant("a1"), user("u2"), assistant("a2"));

		assertThat(texts(session.id(), EventFilter.lastN(1))).containsExactly("u2", "a2");
		assertThat(texts(session.id(), EventFilter.lastN(2))).containsExactly("u2", "a2");
		assertThat(texts(session.id(), EventFilter.lastN(3))).containsExactly("u1", "a1", "u2", "a2");
	}

	@Test
	void lastNWithNoTurnStartBeforeTheCutIsPlain() {
		Session session = newSession("user-preamble");
		append(session.id(), system("s1"), system("s2"), user("u1"), assistant("a1"));

		// The cut lands in the preamble of stored system messages: nothing to extend to
		assertThat(texts(session.id(), EventFilter.lastN(3))).containsExactly("s2", "u1", "a1");
		// A filter that hides USER events has no turn start to extend to
		EventFilter assistantsOnly = EventFilter.builder().messageTypes(Set.of(MessageType.ASSISTANT)).lastN(1).build();
		assertThat(texts(session.id(), assistantsOnly)).containsExactly("a1");
	}

	@Test
	void lastNWithTextCriteriaAndPagesArePlain() {
		Session session = newSession("user-search-window");
		append(session.id(), user("u1 x"), assistant("a1 x"), user("u2 x"), assistant("a2 x"));

		EventFilter search = EventFilter.builder().keyword("x").lastN(1).build();
		assertThat(texts(session.id(), search)).containsExactly("a2 x");
		assertThat(texts(session.id(), EventFilter.builder().page(1).pageSize(1).build())).containsExactly("a1 x");
	}

	@Test
	void findEventsByUserIdWindowsArePlain() {
		String userId = "user-cross-window-" + UUID.randomUUID();
		Session session = newSession(userId);
		append(session.id(), user("u1"), assistant("a1"));

		assertThat(this.repository.findEventsByUserId(userId, EventFilter.lastN(1)))
			.extracting(e -> e.getMessage().getText())
			.containsExactly("a1");
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

	// --- Compaction: an append-only log and a projected view ---

	@Test
	void appendEventsAppendsAtomicallyAndIncrementsTheVersionOnce() {
		Session session = newSession("user-append-events");
		append(session.id(), user("u1"), assistant("a1"));
		long version = this.repository.getEventVersion(session.id());

		boolean appended = this.repository.appendEvents(session.id(),
				List.of(event(session.id(), user("u2")), event(session.id(), assistant("a2"))), version);

		assertThat(appended).isTrue();
		assertThat(this.repository.getEventVersion(session.id())).isEqualTo(version + 1);
		assertThat(texts(session.id(), EventFilter.all())).containsExactly("u1", "a1", "u2", "a2");
	}

	@Test
	void appendEventsWithAStaleVersionWritesNothing() {
		Session session = newSession("user-stale");
		append(session.id(), user("u1"));
		long version = this.repository.getEventVersion(session.id());

		boolean appended = this.repository.appendEvents(session.id(),
				List.of(event(session.id(), assistant("should-not-land"))), version - 1);

		assertThat(appended).isFalse();
		assertThat(this.repository.getEventVersion(session.id())).isEqualTo(version);
		assertThat(texts(session.id(), EventFilter.all())).containsExactly("u1");
	}

	@Test
	void appendEventsRejectsAnEventOfAnotherSessionAndADuplicateIdAndChangesNothing() {
		Session session = newSession("user-reject");
		Session other = newSession("user-reject");
		append(session.id(), user("u1"));
		long version = this.repository.getEventVersion(session.id());
		SessionEvent existing = this.repository.findEvents(session.id(), EventFilter.all()).get(0);

		assertThatThrownBy(() -> this.repository.appendEvents(session.id(),
				List.of(event(other.id(), user("wrong session"))), version))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> this.repository.appendEvents(session.id(), List.of(event(session.id(), user("fresh")),
				SessionEvent.builder().id(existing.getId()).sessionId(session.id()).message(user("dup")).build()),
				version))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(this.repository.getEventVersion(session.id())).isEqualTo(version);
		assertThat(texts(session.id(), EventFilter.all())).containsExactly("u1");
	}

	@Test
	void appendEventsRejectsADuplicateIdWithinTheBatchAndAnEmptyBatch() {
		Session session = newSession("user-batch-dup");
		append(session.id(), user("u1"));
		long version = this.repository.getEventVersion(session.id());
		SessionEvent fresh = event(session.id(), user("fresh"));
		SessionEvent twin = SessionEvent.builder().id(fresh.getId()).sessionId(session.id()).message(user("twin")).build();

		assertThatThrownBy(() -> this.repository.appendEvents(session.id(), List.of(fresh, twin), version))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> this.repository.appendEvents(session.id(), List.of(), version))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(this.repository.getEventVersion(session.id())).isEqualTo(version);
		assertThat(texts(session.id(), EventFilter.all())).containsExactly("u1");
	}

	@Test
	void appendEventsOnAnUnknownSessionIsRejected() {
		String unknown = UUID.randomUUID().toString();
		assertThatThrownBy(() -> this.repository.appendEvents(unknown, List.of(event(unknown, user("u1"))), 0L))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void theCompactedViewIsPinnedThenSummaryThenPreservedThenWhatFollows() {
		Session session = newSession("user-view");
		String id = session.id();
		append(id, user("u1"), system("rules"), assistant("a1"), user("u2"), assistant("a2"));
		List<SessionEvent> log = this.repository.findEvents(id, EventFilter.all());
		compact(id, List.of(log.get(1)), summaryTurn(id, "summary 1"), List.of(log.get(3), log.get(4)));
		append(id, user("u3"));

		assertThat(labels(this.repository.findEvents(id, EventFilter.active()))).containsExactly("rules", "Σ?",
				"Σ:summary 1", "u2", "a2", "u3");
		assertThat(labels(this.repository.findEvents(id, EventFilter.all()))).containsExactly("u1", "rules", "a1",
				"u2", "a2", "⊥", "Σ?", "Σ:summary 1", "u3");
	}

	@Test
	void aLaterBoundaryWinsAndTheLogOnlyGrows() {
		Session session = newSession("user-chain");
		String id = session.id();
		append(id, user("u1"), user("u2"));
		List<SessionEvent> log = this.repository.findEvents(id, EventFilter.all());
		compact(id, List.of(), summaryTurn(id, "summary 1"), List.of(log.get(1)));
		append(id, user("u3"), user("u4"));
		List<String> logAfterFirst = labels(this.repository.findEvents(id, EventFilter.all()));
		List<SessionEvent> view = this.repository.findEvents(id, EventFilter.active());
		// The second compaction covers summary 1, u2 and u3 and keeps u4
		compact(id, List.of(), summaryTurn(id, "summary 2"), List.of(view.get(view.size() - 1)));
		append(id, user("u5"));

		assertThat(labels(this.repository.findEvents(id, EventFilter.active()))).containsExactly("Σ?", "Σ:summary 2",
				"u4", "u5");
		assertThat(labels(this.repository.findEvents(id, EventFilter.all())))
			.startsWith(logAfterFirst.toArray(String[]::new))
			.endsWith("⊥", "Σ?", "Σ:summary 2", "u5");
	}

	@Test
	void aPinnedEventOlderThanAnEarlierBoundaryIsStillInTheView() {
		Session session = newSession("user-pinned");
		String id = session.id();
		append(id, system("rules"), user("u1"), assistant("a1"));
		List<SessionEvent> log = this.repository.findEvents(id, EventFilter.all());
		SessionEvent rules = log.get(0);
		compact(id, List.of(rules), List.of(), List.of(log.get(1), log.get(2)));
		append(id, user("u2"), assistant("a2"));
		List<SessionEvent> view = this.repository.findEvents(id, EventFilter.active());
		compact(id, List.of(rules), List.of(), view.subList(view.size() - 2, view.size()));

		assertThat(labels(this.repository.findEvents(id, EventFilter.active()))).containsExactly("rules", "u2", "a2");
	}

	@Test
	void windowsAndCriteriaApplyToTheView() {
		Session session = newSession("user-view-window");
		String id = session.id();
		append(id, user("u1 secret"), assistant("a1"), user("u2"), assistant("a2"), user("u3"), assistant("a3"));
		List<SessionEvent> log = this.repository.findEvents(id, EventFilter.all());
		compact(id, List.of(), summaryTurn(id, "summary"), log.subList(2, 6));

		// lastN on the view keeps turns whole and never reaches into covered events
		assertThat(texts(id, EventFilter.active().merge(EventFilter.lastN(1)))).containsExactly("u3", "a3");
		assertThat(texts(id, EventFilter.active().merge(EventFilter.lastN(5))))
			.containsExactly("Summarize the conversation we had so far.", "summary", "u2", "a2", "u3", "a3");
		// a message-type filter on the view
		assertThat(texts(id, EventFilter.builder().compactedView(true).messageTypes(Set.of(MessageType.USER)).build()))
			.containsExactly("Summarize the conversation we had so far.", "u2", "u3");
		// text search: the covered event is found in the whole log, not in the view
		assertThat(texts(id, EventFilter.keywordSearch("secret"))).containsExactly("u1 secret");
		assertThat(texts(id, EventFilter.builder().compactedView(true).keyword("secret").build())).isEmpty();
	}

	@Test
	void timeRangesAndPagesApplyToTheView() {
		Session session = newSession("user-view-page");
		String id = session.id();
		Instant t0 = Instant.parse("2025-01-01T00:00:00Z");
		for (int i = 1; i <= 6; i++) {
			appendAt(id, t0.plusSeconds(i), "u" + i);
		}
		List<SessionEvent> log = this.repository.findEvents(id, EventFilter.all());
		compact(id, List.of(), List.of(), log.subList(2, 6));

		// a time range on the view does not reach the covered u2
		assertThat(texts(id,
				EventFilter.builder().compactedView(true).from(t0.plusSeconds(2)).to(t0.plusSeconds(4)).build()))
			.containsExactly("u3", "u4");
		assertThat(texts(id, EventFilter.builder().from(t0.plusSeconds(2)).to(t0.plusSeconds(4)).build()))
			.containsExactly("u2", "u3", "u4");
		// pages count from the oldest event of the view
		assertThat(texts(id, EventFilter.builder().compactedView(true).page(0).pageSize(2).build()))
			.containsExactly("u3", "u4");
		assertThat(texts(id, EventFilter.builder().compactedView(true).page(1).pageSize(2).build()))
			.containsExactly("u5", "u6");
		assertThat(texts(id, EventFilter.builder().page(0).pageSize(2).build())).containsExactly("u1", "u2");
	}

	@Test
	void aBoundaryNamingIdsTheLogDoesNotHoldSkipsThem() {
		Session session = newSession("user-ghost-ids");
		String id = session.id();
		append(id, user("u1"), user("u2"));
		List<SessionEvent> log = this.repository.findEvents(id, EventFilter.all());
		// Named by the boundary, never written
		SessionEvent ghost = event(id, user("ghost"));
		compact(id, List.of(ghost), List.of(), List.of(log.get(1), ghost));
		append(id, user("u3"));

		assertThat(texts(id, EventFilter.active())).containsExactly("u2", "u3");
	}

	@Test
	void anEventThatIsNotAMessageIsInTheViewOnlyWhenItFollowsTheBoundary() {
		Session session = newSession("user-typed-view");
		String id = session.id();
		append(id, user("u1"));
		this.repository.appendEvent(status(id, "RUNNING"));
		append(id, user("u2"));
		List<SessionEvent> log = this.repository.findEvents(id, EventFilter.all());
		// The service builds the boundary from the conversation: it names u2 only
		compact(id, List.of(), List.of(), List.of(log.get(2)));
		this.repository.appendEvent(status(id, "IDLE"));

		EventFilter statuses = EventFilter.builder().eventTypes(Set.of(EventType.STATUS)).build();
		assertThat(this.repository.findEvents(id, statuses)).extracting(e -> e.getPayload().get("status"))
			.containsExactly("RUNNING", "IDLE");
		assertThat(this.repository.findEvents(id, statuses.merge(EventFilter.builder().compactedView(true).build())))
			.extracting(e -> e.getPayload().get("status"))
			.containsExactly("IDLE");
		// The boundary itself is not part of the view it defines
		assertThat(labels(this.repository.findEvents(id, EventFilter.builder().compactedView(true).build())))
			.containsExactly("u2", "IDLE");
	}

	@Test
	void aViewReadAcrossSessionsIsPerSession() {
		String userId = "user-cross-view-" + UUID.randomUUID();
		Session first = newSession(userId);
		Session second = newSession(userId);
		append(first.id(), user("f1"), user("f2"));
		append(second.id(), user("s1"));
		List<SessionEvent> log = this.repository.findEvents(first.id(), EventFilter.all());
		compact(first.id(), List.of(), List.of(), List.of(log.get(1)));

		assertThat(this.repository.findEventsByUserId(userId, EventFilter.active()))
			.extracting(e -> e.getMessage().getText())
			.containsExactlyInAnyOrder("f2", "s1");
		assertThat(this.repository.findEventsByUserId(userId, EventFilter.all()).stream().filter(SessionEvent::hasMessage))
			.extracting(e -> e.getMessage().getText())
			.containsExactlyInAnyOrder("f1", "f2", "s1");
	}

	// --- Typed events ---

	@Test
	void anEventThatIsNotAMessageRoundTripsItsTypeAndPayload() {
		Session session = newSession("user-typed");
		append(session.id(), user("hello"));
		this.repository.appendEvent(SessionEvent.builder()
			.sessionId(session.id())
			.type(EventType.STATUS)
			.payload(Map.of("status", "IDLE", "stopReason", "END_TURN", "turn", 1))
			.build());

		List<SessionEvent> all = this.repository.findEvents(session.id(), EventFilter.all());
		assertThat(all).extracting(SessionEvent::getType).containsExactly(EventType.MESSAGE, EventType.STATUS);
		SessionEvent status = all.get(1);
		assertThat(status.hasMessage()).isFalse();
		assertThat(status.getPayload()).containsEntry("status", "IDLE")
			.containsEntry("stopReason", "END_TURN")
			.containsEntry("turn", 1);
	}

	@Test
	void activeKeepsOnlyMessageEventsInLogOrderBeforeAnyBoundary() {
		Session session = newSession("user-active");
		append(session.id(), user("q1"));
		this.repository.appendEvent(SessionEvent.builder().sessionId(session.id()).type(EventType.USAGE).build());
		append(session.id(), assistant("a1"));

		assertThat(texts(session.id(), EventFilter.active())).containsExactly("q1", "a1");
		assertThat(this.repository.findEvents(session.id(), EventFilter.all())).extracting(SessionEvent::getType)
			.containsExactly(EventType.MESSAGE, EventType.USAGE, EventType.MESSAGE);
	}

	@Test
	void eventTypesFilterSelectsTypesAndMessageAndTextFiltersSkipNonMessageEvents() {
		Session session = newSession("user-types");
		append(session.id(), user("hello"));
		this.repository.appendEvent(SessionEvent.builder()
			.sessionId(session.id())
			.type(EventType.STATUS)
			.payload("status", "hello")
			.build());
		this.repository.appendEvent(SessionEvent.builder().sessionId(session.id()).type(EventType.USAGE).build());

		assertThat(this.repository.findEvents(session.id(),
				EventFilter.builder().eventTypes(Set.of(EventType.STATUS, EventType.USAGE)).build()))
			.extracting(SessionEvent::getType)
			.containsExactly(EventType.STATUS, EventType.USAGE);
		assertThat(texts(session.id(), EventFilter.builder().messageTypes(Set.of(MessageType.USER)).build()))
			.containsExactly("hello");
		// "hello" sits in the STATUS payload too, but a payload is not message text
		assertThat(texts(session.id(), EventFilter.keywordSearch("hello"))).containsExactly("hello");
	}

	@Test
	void messagePartsAndMetadataSurviveARoundTrip() {
		Session session = newSession("user-parts");
		Media image = Media.builder()
			.mimeType(MimeTypeUtils.IMAGE_PNG)
			.data(java.net.URI.create("https://example.com/cat.png"))
			.build();
		OpaquePayload signature = new OpaquePayload("anthropic", "signature", "sig-1");
		append(session.id(), UserMessage.builder().text("look").media(image).metadata(Map.of("source", "web")).build(),
				AssistantMessage.builder()
					.parts(List.of(new ReasoningPart("thinking", null, signature, Map.of()), TextPart.of("a cat")))
					.build(),
				ToolResponseMessage.builder()
					.results(List.of(new ToolResultPart("c1", "lookup", List.of(TextPart.of("boom")), true, null,
							Map.of())))
					.build());

		List<SessionEvent> events = this.repository.findEvents(session.id(), EventFilter.all());
		UserMessage user = (UserMessage) events.get(0).getMessage();
		assertThat(user.getText()).isEqualTo("look");
		assertThat(user.getMedia()).hasSize(1);
		assertThat(user.getMedia().get(0).getMimeType()).isEqualTo(MimeTypeUtils.IMAGE_PNG);
		assertThat(user.getMetadata()).containsEntry("source", "web");
		AssistantMessage assistant = (AssistantMessage) events.get(1).getMessage();
		assertThat(assistant.getText()).isEqualTo("a cat");
		assertThat(assistant.getReasoning()).hasSize(1);
		assertThat(assistant.getReasoning().get(0).payload()).isEqualTo(signature);
		ToolResponseMessage tool = (ToolResponseMessage) events.get(2).getMessage();
		assertThat(tool.getResults()).hasSize(1);
		assertThat(tool.getResults().get(0).isError()).isTrue();
		assertThat(tool.getResponses().get(0).responseData()).isEqualTo("boom");
	}

	@Test
	void lastNExtendsPastNonMessageEventsToTheTurnStart() {
		Session session = newSession("user-window-typed");
		append(session.id(), user("u1"), assistant("a1"));
		this.repository.appendEvent(SessionEvent.builder().sessionId(session.id()).type(EventType.STATUS).build());
		append(session.id(), user("u2"));
		this.repository.appendEvent(SessionEvent.builder().sessionId(session.id()).type(EventType.USAGE).build());
		append(session.id(), assistant("a2"));

		List<SessionEvent> window = this.repository.findEvents(session.id(), EventFilter.lastN(1));
		assertThat(window).extracting(SessionEvent::getType)
			.containsExactly(EventType.MESSAGE, EventType.USAGE, EventType.MESSAGE);
		assertThat(window.stream().filter(SessionEvent::hasMessage).map(e -> e.getMessage().getText()))
			.containsExactly("u2", "a2");
		assertThat(texts(session.id(), EventFilter.active().merge(EventFilter.lastN(1)))).containsExactly("u2",
				"a2");
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

	/** The texts of the message events matching the filter, in the order returned. */
	protected List<String> texts(String sessionId, EventFilter filter) {
		return this.repository.findEvents(sessionId, filter)
			.stream()
			.filter(SessionEvent::hasMessage)
			.map(e -> e.getMessage().getText())
			.toList();
	}

	protected static SessionEvent event(String sessionId, Message message) {
		return SessionEvent.builder().sessionId(sessionId).message(message).build();
	}

	/** A status event, the simplest event that is not a message. */
	protected static SessionEvent status(String sessionId, String status) {
		return SessionEvent.builder().sessionId(sessionId).type(EventType.STATUS).payload("status", status).build();
	}

	/**
	 * Records a compaction the way the service does: appends a boundary naming the pinned,
	 * summary and preserved events, followed by the summary events, under the current
	 * version.
	 */
	protected void compact(String sessionId, List<SessionEvent> pinned, List<SessionEvent> summary,
			List<SessionEvent> preserved) {
		String boundaryId = UUID.randomUUID().toString();
		CompactionBoundary boundary = new CompactionBoundary(boundaryId, ids(pinned), ids(summary), ids(preserved),
				0, 0, "test");
		List<SessionEvent> events = new ArrayList<>();
		events.add(SessionEvent.builder()
			.id(boundaryId)
			.sessionId(sessionId)
			.type(EventType.COMPACTION)
			.payload(boundary.toPayload())
			.build());
		events.addAll(summary);
		assertThat(this.repository.appendEvents(sessionId, events, this.repository.getEventVersion(sessionId)))
			.isTrue();
	}

	private static List<String> ids(List<SessionEvent> events) {
		return events.stream().map(SessionEvent::getId).toList();
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

	/**
	 * Event labels: the text, "Σ?" for a synthetic shadow prompt, "Σ:text" for a summary,
	 * "A[name]" for an assistant tool call, "T[name]" for a tool result, "⊥" for a
	 * compaction boundary and the status (or the type name) for any other event.
	 */
	protected static List<String> labels(List<SessionEvent> events) {
		return events.stream().map(e -> {
			if (e.getType() == EventType.COMPACTION) {
				return "⊥";
			}
			if (!e.hasMessage()) {
				return String.valueOf(e.getPayload().getOrDefault("status", e.getType().name()));
			}
			if (e.isSynthetic()) {
				return (e.getMessageType() == MessageType.USER) ? "Σ?" : "Σ:" + e.getMessage().getText();
			}
			if (e.getMessage() instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
				return "A[" + assistant.getToolCalls().get(0).name() + "]";
			}
			if (e.getMessage() instanceof ToolResponseMessage responses) {
				return "T[" + responses.getResponses().get(0).name() + "]";
			}
			return e.getMessage().getText();
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

	/** An assistant message that calls one tool. */
	protected static Message toolCall(String callId, String toolName) {
		return AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall(callId, "function", toolName, "{}")))
			.build();
	}

	/** The result of one tool call. */
	protected static Message toolResponse(String callId, String toolName, String data) {
		return ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse(callId, toolName, data)))
			.build();
	}

}
