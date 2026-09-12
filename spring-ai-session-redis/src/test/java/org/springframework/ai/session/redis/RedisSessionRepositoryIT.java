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

package org.springframework.ai.session.redis;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Tests for {@link RedisSessionRepository} against a real Redis server started by
 * Testcontainers.
 *
 * <p>
 * Mirrors the contract of {@code InMemorySessionRepositoryTests} and
 * {@code JdbcSessionRepositoryTests} so all three implementations are verified against
 * the same specification, and adds the guarantees that are properties of the Redis server
 * rather than of the Java code: the atomicity of the compaction compare-and-swap, the
 * exclusive {@code ZRANGEBYSCORE} bound implementing strictly-before expiry, the
 * {@code DEL} of the active-id set that frees a compaction-dropped event id, and the
 * crash-backstop key deadlines. A mocked client would accept all of them.
 *
 * <p>
 * Every test uses a fresh random session id, so tests never share state — the two index
 * structures are shared across the keyspace by design.
 */
@Testcontainers
class RedisSessionRepositoryIT {

	private static final String USER = "alice";

	private static final String KEY_PREFIX = "test-session:";

	private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

	private static RedisClient jedisClient;

	private static RedisSessionRepository repository;

	@BeforeAll
	static void startRedis() {
		REDIS.start();
		jedisClient = RedisClient.builder().hostAndPort(REDIS.getHost(), REDIS.getFirstMappedPort()).build();
		repository = RedisSessionRepository.builder().jedisClient(jedisClient).keyPrefix(KEY_PREFIX).build();
	}

	@AfterAll
	static void stopRedis() {
		if (jedisClient != null) {
			jedisClient.close();
		}
		REDIS.stop();
	}

	// -------------------------------------------------------------------------
	// Session lifecycle
	// -------------------------------------------------------------------------

	@Test
	void findByIdRoundTripsMetadataAndInstants() {
		String sessionId = newSessionId();
		Instant createdAt = Instant.parse("2026-09-07T10:15:30.123456789Z");
		Instant expiresAt = Instant.parse("2026-10-07T10:15:30.987654321Z");
		Map<String, Object> metadata = Map.of("agentType", "researcher", "turns", 7);
		repository.save(Session.builder()
			.id(sessionId)
			.userId(USER)
			.createdAt(createdAt)
			.expiresAt(expiresAt)
			.metadata(metadata)
			.build());

		Session found = repository.findById(sessionId);

		assertThat(found).isNotNull();
		assertThat(found.id()).isEqualTo(sessionId);
		assertThat(found.userId()).isEqualTo(USER);
		// Nanosecond fidelity survives because the hash stores ISO-8601, not the
		// millisecond ZSET score.
		assertThat(found.createdAt()).isEqualTo(createdAt);
		assertThat(found.expiresAt()).isEqualTo(expiresAt);
		assertThat(found.metadata()).isEqualTo(metadata);
		assertThat(repository.findById(newSessionId())).isNull();
	}

	@Test
	void findByUserIdReturnsOnlyThatUsersSessions() {
		// Unique user ids: the by-user SET is shared, so a literal "alice" would
		// accumulate members from every other test in this class.
		String alice = "alice-" + UUID.randomUUID();
		String bob = "bob-" + UUID.randomUUID();
		String firstAlice = saveSessionFor(alice);
		String secondAlice = saveSessionFor(alice);
		String onlyBob = saveSessionFor(bob);

		assertThat(repository.findByUserId(alice)).extracting(Session::id)
			.containsExactlyInAnyOrder(firstAlice, secondAlice);
		assertThat(repository.findByUserId(bob)).extracting(Session::id).containsExactly(onlyBob);
		assertThat(repository.findByUserId("carol-" + UUID.randomUUID())).isEmpty();
	}

	@Test
	void findExpiredSessionIdsIsStrictlyBefore() {
		// Truncated to milliseconds so the assertion is about the bound, not about ZSET
		// score rounding.
		Instant bound = Instant.now().minus(Duration.ofDays(1)).truncatedTo(ChronoUnit.MILLIS);
		String early = saveSessionExpiringAt(bound.minusMillis(1));
		String atBound = saveSessionExpiringAt(bound);
		String late = saveSessionExpiringAt(bound.plusMillis(1));
		String farFuture = saveSession();

		// The expiry ZSET is shared by every session in the keyspace (that is what makes
		// the sweep a single ZRANGEBYSCORE), so assert on our four ids rather than on the
		// whole result.
		assertThat(repository.findExpiredSessionIds(bound))
			.as("the upper bound is exclusive: expiresAt == before is NOT expired")
			.contains(early)
			.doesNotContain(atBound, late, farFuture);
	}

	@Test
	void deleteLeavesZeroOrphanKeys() {
		String sessionId = sessionCompactedOnce();
		assertThat(repository.findById(sessionId)).isNotNull();

		repository.delete(sessionId);

		List<String> scanned = new ArrayList<>();
		String cursor = ScanParams.SCAN_POINTER_START;
		do {
			ScanResult<String> page = jedisClient.scan(cursor, new ScanParams().match(KEY_PREFIX + "*").count(1000));
			scanned.addAll(page.getResult());
			cursor = page.getCursor();
		}
		while (!ScanParams.SCAN_POINTER_START.equals(cursor));

		assertThat(scanned).as("delete() must leave zero keys naming the session")
			.noneMatch(key -> key.contains(sessionId));
		assertThat(jedisClient.zrangeByScore(KEY_PREFIX + "expiry", Double.NEGATIVE_INFINITY, Double.MAX_VALUE))
			.doesNotContain(sessionId);
		assertThat(jedisClient.smembers(KEY_PREFIX + "by-user:" + USER)).doesNotContain(sessionId);
		assertThat(repository.findById(sessionId)).isNull();
	}

	// -------------------------------------------------------------------------
	// appendEvent
	// -------------------------------------------------------------------------

	@Test
	void appendEventWithSameIdIsIdempotent() {
		String sessionId = saveSession();
		repository.appendEvent(userEvent(sessionId, "e1", "one"));
		assertThat(repository.getEventVersion(sessionId)).isEqualTo(1L);

		// Same id, DIFFERENT text: a replay must be skipped outright. Replaying the
		// identical event would pass just as well against an implementation that
		// overwrote the stored copy.
		repository.appendEvent(userEvent(sessionId, "e1", "one, rewritten"));

		assertThat(repository.findEvents(sessionId, EventFilter.all()))
			.as("a replayed id must neither append a second copy nor overwrite the stored one")
			.extracting(SessionEvent::getId, event -> event.getMessage().getText())
			.containsExactly(tuple("e1", "one"));
		assertThat(repository.getEventVersion(sessionId)).as("a replayed id must not increment the event-log version")
			.isEqualTo(1L);
	}

	@Test
	void appendEventThrowsWhenSessionNotFound() {
		String sessionId = newSessionId();
		SessionEvent event = userEvent(sessionId, "e1", "one");

		assertThatThrownBy(() -> repository.appendEvent(event)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Session not found");

		// The script returns before writing anything, so the rejected append must not
		// have created the list/set keys as a side effect.
		assertThat(List.of("", ":events", ":archive", ":active-ids", ":archived-ids"))
			.allSatisfy(suffix -> assertThat(jedisClient.exists(KEY_PREFIX + sessionId + suffix)).isFalse());
	}

	@Test
	void appendEventCanReuseAnIdThatCompactionDropped() {
		// The shared compaction fixture leaves e3 in neither list, so compaction dropped
		// it along with the active-id set. Its id must therefore be appendable again --
		// matching JDBC, where the row is gone and the primary key no longer collides. An
		// archived id, by contrast, is still "seen".
		String sessionId = sessionCompactedOnce();
		long version = repository.getEventVersion(sessionId);

		repository.appendEvent(userEvent(sessionId, "e3", "three again"));

		assertThat(repository.getEventVersion(sessionId)).isEqualTo(version + 1);
		assertThat(repository.findEvents(sessionId, EventFilter.active())).extracting(SessionEvent::getId)
			.containsExactly("summary", "e4", "e3");

		repository.appendEvent(userEvent(sessionId, "e1", "one again"));

		assertThat(repository.getEventVersion(sessionId))
			.as("re-appending an ARCHIVED id must still be an idempotent no-op")
			.isEqualTo(version + 1);
	}

	// -------------------------------------------------------------------------
	// compactEvents
	// -------------------------------------------------------------------------

	@Test
	void compactEventsWithStaleVersionFailsAndMutatesNothing() {
		String sessionId = saveSession();
		SessionEvent e1 = userEvent(sessionId, "e1", "one");
		repository.appendEvent(e1);
		repository.appendEvent(userEvent(sessionId, "e2", "two"));
		repository.appendEvent(userEvent(sessionId, "e3", "three"));

		boolean applied = repository.compactEvents(sessionId, List.of(e1),
				List.of(userEvent(sessionId, "summary", "summary")), 99L);

		assertThat(applied).isFalse();
		assertThat(repository.findEvents(sessionId, EventFilter.all()))
			.as("a mismatched version must leave the log byte-identical")
			.extracting(SessionEvent::getId, SessionEvent::isArchived)
			.containsExactly(tuple("e1", false), tuple("e2", false), tuple("e3", false));
		assertThat(repository.getEventVersion(sessionId)).isEqualTo(3L);
	}

	@Test
	void compactEventsThrowsWhenSessionNotFound() {
		String sessionId = newSessionId();
		assertThatThrownBy(() -> repository.compactEvents(sessionId, List.of(), List.of(), 0L))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Session not found");
	}

	@Test
	void compactEventsOrdersArchivedThenRetainedAndDropsEventsInNeitherList() {
		String sessionId = sessionCompactedOnce();

		assertThat(repository.findEvents(sessionId, EventFilter.all()))
			.extracting(SessionEvent::getId, SessionEvent::isArchived)
			.containsExactly(tuple("e1", true), tuple("e2", true), tuple("summary", false), tuple("e4", false));
		assertThat(repository.getEventVersion(sessionId)).isEqualTo(5L);
	}

	@Test
	void compactEventsPreservesEarlierArchivePrefix() {
		String sessionId = sessionCompactedOnce();
		SessionEvent e4 = userEvent(sessionId, "e4", "four");

		boolean applied = repository.compactEvents(sessionId, List.of(e4),
				List.of(userEvent(sessionId, "summary2", "second summary")), 5L);

		assertThat(applied).isTrue();
		// e1/e2 keep their original order at the head, e4 is now archived and follows
		// them, and the first summary -- active but in neither list -- is gone.
		assertThat(repository.findEvents(sessionId, EventFilter.all()))
			.extracting(SessionEvent::getId, SessionEvent::isArchived)
			.containsExactly(tuple("e1", true), tuple("e2", true), tuple("e4", true), tuple("summary2", false));
		assertThat(repository.getEventVersion(sessionId)).isEqualTo(6L);
	}

	@Test
	void compactEventsRetainsTheCallerSuppliedArchivedFlag() {
		// Neither the in-memory nor the JDBC implementation normalises a retained event's
		// archived flag, so an already-archived event handed back as retained must STAY
		// archived. Every other fixture retains archived=false events, so an
		// implementation that wrongly forced the flag would pass the whole suite but for
		// this test.
		String sessionId = saveSession();
		SessionEvent e1 = userEvent(sessionId, "e1", "one");
		SessionEvent alreadyArchived = userEvent(sessionId, "e2", "two").asArchived();
		repository.appendEvent(e1);
		repository.appendEvent(alreadyArchived);

		assertThat(repository.compactEvents(sessionId, List.of(e1), List.of(alreadyArchived), 2L)).isTrue();

		assertThat(repository.findEvents(sessionId, EventFilter.all()))
			.extracting(SessionEvent::getId, SessionEvent::isArchived)
			.containsExactly(tuple("e1", true), tuple("e2", true));
		assertThat(repository.findEvents(sessionId, EventFilter.active()))
			.as("a retained event that was already archived stays excluded from the active window")
			.isEmpty();
	}

	@Test
	void concurrentCompactionsAtTheSameVersionLetExactlyOneWin() {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			for (int iteration = 0; iteration < 20; iteration++) {
				String sessionId = saveSession();
				SessionEvent e1 = userEvent(sessionId, "e1", "one");
				repository.appendEvent(e1);
				repository.appendEvent(userEvent(sessionId, "e2", "two"));
				long version = repository.getEventVersion(sessionId);

				List<SessionEvent> summaries = List.of(userEvent(sessionId, "summary-a", "summary from thread a"),
						userEvent(sessionId, "summary-b", "summary from thread b"));
				// The barrier only aligns the two threads' start; both calls are
				// synchronous, so there is no async outcome to await.
				CyclicBarrier barrier = new CyclicBarrier(summaries.size());
				List<Boolean> results = summaries.stream().map(summary -> CompletableFuture.supplyAsync(() -> {
					try {
						barrier.await(30, TimeUnit.SECONDS);
					}
					catch (Exception ex) {
						throw new IllegalStateException("race barrier failed", ex);
					}
					return repository.compactEvents(sessionId, List.of(e1), List.of(summary), version);
				}, executor)).toList().stream().map(CompletableFuture::join).toList();

				assertThat(results)
					.as("iteration %d: exactly one compaction may win at the same expected version", iteration)
					.containsExactlyInAnyOrder(true, false);
				assertThat(repository.getEventVersion(sessionId))
					.as("iteration %d: the loser must not have incremented the version", iteration)
					.isEqualTo(version + 1);
				assertThat(repository.findEvents(sessionId, EventFilter.active()))
					.as("iteration %d: the active window is the winner's summary alone", iteration)
					.extracting(SessionEvent::getId)
					.containsExactly(summaries.get(results.indexOf(true)).getId());
			}
		}
		finally {
			executor.shutdownNow();
		}
	}

	// -------------------------------------------------------------------------
	// getEventVersion
	// -------------------------------------------------------------------------

	@Test
	void getEventVersionIsZeroForUnknownSessionAndForOneWithNoEvents() {
		assertThat(repository.getEventVersion(newSessionId())).isZero();
		assertThat(repository.getEventVersion(saveSession())).isZero();
	}

	@Test
	void saveUpdatesMetadataButPreservesEventVersionAndEvents() {
		String sessionId = saveSession();
		repository.appendEvent(userEvent(sessionId, "e1", "one"));
		repository.appendEvent(userEvent(sessionId, "e2", "two"));

		repository.save(Session.builder().id(sessionId).userId(USER).metadata(Map.of("tag", "resaved")).build());

		assertThat(repository.getEventVersion(sessionId))
			.as("save() must never write or clear the eventVersion hash field")
			.isEqualTo(2L);
		assertThat(repository.findEvents(sessionId, EventFilter.all()))
			.as("the stored events must survive a re-save untouched, not merely still number two")
			.extracting(SessionEvent::getId, event -> event.getMessage().getText())
			.containsExactly(tuple("e1", "one"), tuple("e2", "two"));
		assertThat(repository.findById(sessionId)).isNotNull()
			.extracting(Session::metadata)
			.isEqualTo(Map.of("tag", "resaved"));
	}

	// -------------------------------------------------------------------------
	// findEvents
	// -------------------------------------------------------------------------

	@Test
	void findEventsReturnsEmptyListForNonExistentSession() {
		assertThat(repository.findEvents(newSessionId(), EventFilter.all())).isEmpty();
	}

	@Test
	void appendedEventsAreReturnedInInsertionOrderNotTimestampOrder() {
		String sessionId = saveSession();
		Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		// Timestamps deliberately DESCENDING: a timestamp sort would return e3, e2, e1.
		repository.appendEvent(timestampedEvent(sessionId, "e1", "first appended", now.plusSeconds(10)));
		repository.appendEvent(timestampedEvent(sessionId, "e2", "second appended", now.plusSeconds(5)));
		repository.appendEvent(timestampedEvent(sessionId, "e3", "third appended", now));

		assertThat(repository.findEvents(sessionId, EventFilter.all())).extracting(SessionEvent::getId)
			.containsExactly("e1", "e2", "e3");
		assertThat(repository.findEvents(sessionId, EventFilter.all()))
			.as("the stored timestamp must round-trip, not just the ordering")
			.extracting(SessionEvent::getTimestamp)
			.containsExactly(now.plusSeconds(10), now.plusSeconds(5), now);
	}

	@Test
	void findEventsAfterCompactionUsesCompactionOrderNotTimestampOrder() {
		// The other ordering guard covers a single list; this one covers the
		// reconstruction ACROSS the archive and active keys, which is where a timestamp
		// sort does its real damage. Timestamps are adversarial by construction: the
		// synthetic summary carries the OLDEST timestamp of the three, yet compaction
		// order puts it after both archived events. Sort by timestamp and you get
		// [summary, e1, e2, ...] -- a corrupted prompt window.
		String sessionId = saveSession();
		Instant base = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		SessionEvent e1 = timestampedEvent(sessionId, "e1", "one", base.plusSeconds(100));
		SessionEvent e2 = timestampedEvent(sessionId, "e2", "two", base.plusSeconds(200));
		SessionEvent e4 = timestampedEvent(sessionId, "e4", "four", base.plusSeconds(400));
		repository.appendEvent(e1);
		repository.appendEvent(e2);
		repository.appendEvent(timestampedEvent(sessionId, "e3", "three", base.plusSeconds(300)));
		repository.appendEvent(e4);
		SessionEvent summary = timestampedEvent(sessionId, "summary", "summary of one and two", base.plusSeconds(1));

		assertThat(repository.compactEvents(sessionId, List.of(e1, e2), List.of(summary, e4), 4L)).isTrue();

		assertThat(repository.findEvents(sessionId, EventFilter.all()))
			.as("compaction order wins over timestamp order across the archive/active split")
			.extracting(SessionEvent::getId)
			.containsExactly("e1", "e2", "summary", "e4");
	}

	@Test
	void findEventsLastNReturnsOnlyLastNInChronologicalOrder() {
		String sessionId = saveSession();
		for (int i = 1; i <= 5; i++) {
			repository.appendEvent(userEvent(sessionId, "e" + i, "event number " + i));
		}

		assertThat(repository.findEvents(sessionId, EventFilter.lastN(2))).extracting(SessionEvent::getId)
			.containsExactly("e4", "e5");
	}

	@Test
	void findEventsActiveExcludesArchived() {
		String sessionId = sessionCompactedOnce();

		assertThat(repository.findEvents(sessionId, EventFilter.active())).extracting(SessionEvent::getId)
			.containsExactly("summary", "e4");
		assertThat(repository.findEvents(sessionId, EventFilter.all())).extracting(SessionEvent::getId)
			.containsExactly("e1", "e2", "summary", "e4");
	}

	@Test
	void findEventsPaginationStartsAtTheOldestPage() {
		String sessionId = saveSession();
		for (int i = 1; i <= 5; i++) {
			repository.appendEvent(userEvent(sessionId, "e" + i, "needle number " + i));
		}

		assertThat(repository.findEvents(sessionId, EventFilter.keywordSearch("needle", 0, 2)))
			.extracting(SessionEvent::getId)
			.containsExactly("e1", "e2");
		assertThat(repository.findEvents(sessionId, EventFilter.keywordSearch("needle", 1, 2)))
			.extracting(SessionEvent::getId)
			.containsExactly("e3", "e4");
		assertThat(repository.findEvents(sessionId, EventFilter.keywordSearch("needle", 3, 2)))
			.as("a page past the end is empty, not an exception")
			.isEmpty();
	}

	@Test
	void findEventsFilterByMessageTypeAndSynthetic() {
		String sessionId = saveSession();
		repository.appendEvent(userEvent(sessionId, "u1", "a user turn"));
		repository.appendEvent(SessionEvent.builder()
			.sessionId(sessionId)
			.id("a1")
			.message(new AssistantMessage("an assistant turn"))
			.build());
		repository.appendEvent(SessionEvent.builder()
			.sessionId(sessionId)
			.id("s1")
			.message(new UserMessage("a synthetic summary"))
			.metadata(SessionEvent.METADATA_SYNTHETIC, true)
			.build());

		assertThat(repository.findEvents(sessionId,
				EventFilter.builder().messageTypes(Set.of(MessageType.ASSISTANT)).build()))
			.extracting(SessionEvent::getId)
			.containsExactly("a1");
		assertThat(repository.findEvents(sessionId, EventFilter.realOnly())).extracting(SessionEvent::getId)
			.containsExactly("u1", "a1");
	}

	@Test
	void findEventsWithBranchFilterIsolatesPeerAgents() {
		String sessionId = saveSession();
		repository.appendEvent(
				SessionEvent.builder().sessionId(sessionId).message(new UserMessage("root")).branch(null).build());
		repository.appendEvent(SessionEvent.builder()
			.sessionId(sessionId)
			.message(new UserMessage("by orchestrator"))
			.branch("orch")
			.build());
		repository.appendEvent(SessionEvent.builder()
			.sessionId(sessionId)
			.message(new UserMessage("by researcher"))
			.branch("orch.researcher")
			.build());
		repository.appendEvent(SessionEvent.builder()
			.sessionId(sessionId)
			.message(new UserMessage("by writer"))
			.branch("orch.writer")
			.build());

		List<SessionEvent> forResearcher = repository.findEvents(sessionId, EventFilter.forBranch("orch.researcher"));

		assertThat(forResearcher).hasSize(3);
		assertThat(forResearcher).noneMatch(event -> "by writer".equals(event.getMessage().getText()));
	}

	@Test
	void findEventsRoundTripsToolCallsAndToolResponses() {
		String sessionId = saveSession();
		List<ToolCall> toolCalls = List.of(new ToolCall("call-1", "function", "getWeather", "{\"city\":\"Oslo\"}"),
				new ToolCall("call-2", "function", "getTime", "{\"zone\":\"UTC\"}"));
		repository.appendEvent(SessionEvent.builder()
			.sessionId(sessionId)
			.id("assistant")
			.message(AssistantMessage.builder().content("calling tools").toolCalls(toolCalls).build())
			.branch("orch.researcher")
			.build());
		repository.appendEvent(SessionEvent.builder()
			.sessionId(sessionId)
			.id("tool")
			.message(ToolResponseMessage.builder()
				.responses(List.of(new ToolResponse("call-1", "getWeather", "{\"temp\":7}")))
				.build())
			.build());

		List<SessionEvent> events = repository.findEvents(sessionId, EventFilter.all());

		assertThat(events).extracting(SessionEvent::getId, SessionEvent::getMessageType)
			.containsExactly(tuple("assistant", MessageType.ASSISTANT), tuple("tool", MessageType.TOOL));

		AssistantMessage assistant = (AssistantMessage) events.get(0).getMessage();
		assertThat(assistant.hasToolCalls()).isTrue();
		assertThat(assistant.getText()).isEqualTo("calling tools");
		assertThat(assistant.getToolCalls())
			.extracting(ToolCall::id, ToolCall::type, ToolCall::name, ToolCall::arguments)
			.containsExactly(tuple("call-1", "function", "getWeather", "{\"city\":\"Oslo\"}"),
					tuple("call-2", "function", "getTime", "{\"zone\":\"UTC\"}"));
		assertThat(events.get(0).getBranch()).isEqualTo("orch.researcher");

		ToolResponseMessage toolResponse = (ToolResponseMessage) events.get(1).getMessage();
		assertThat(toolResponse.getResponses())
			.extracting(ToolResponse::id, ToolResponse::name, ToolResponse::responseData)
			.containsExactly(tuple("call-1", "getWeather", "{\"temp\":7}"));
	}

	// -------------------------------------------------------------------------
	// Degradation on corrupt stored records
	// -------------------------------------------------------------------------

	@Test
	void findEventsSkipsUnreadableStoredRecords() {
		// findEvents converts every stored record in a loop, so an exception escaping
		// that loop would make the WHOLE session permanently unreadable over a single bad
		// record. Records can go bad without a bug of ours: a partial write, a manual
		// redis-cli edit, or a MessageType constant renamed by a future Spring AI.
		// Written straight into the list because no public API can produce these.
		String sessionId = saveSession();
		repository.appendEvent(userEvent(sessionId, "good-1", "first good event"));
		jedisClient.rpush(KEY_PREFIX + sessionId + ":events",
				// An enum constant this Spring AI version does not know.
				"""
						{"id":"bad-type","sessionId":"%s","timestamp":"2026-09-07T10:00:00Z",\
						"messageType":"NOT_A_TYPE","messageContent":"x","metadata":{},"archived":false,\
						"synthetic":false}""".formatted(sessionId),
				// An unparseable timestamp.
				"""
						{"id":"bad-timestamp","sessionId":"%s","timestamp":"not-an-instant",\
						"messageType":"USER","messageContent":"x","metadata":{},"archived":false,\
						"synthetic":false}""".formatted(sessionId),
				// Truncated JSON -- fails before the id can even be recovered.
				"{\"id\":\"truncated\",\"sessionId\":\"" + sessionId + "\",\"timestamp\"",
				// Required fields absent entirely.
				"{\"id\":\"empty-ish\"}");
		repository.appendEvent(userEvent(sessionId, "good-2", "second good event"));

		assertThat(repository.findEvents(sessionId, EventFilter.all()))
			.as("one bad record must not make the whole session unreadable")
			.extracting(SessionEvent::getId)
			.containsExactly("good-1", "good-2");
	}

	@Test
	void findEventsSkipsRecordsThatThrowWhileBeingMatched() {
		// EventFilter#matches calls SessionEvent#isSynthetic, which casts a metadata
		// value
		// to boolean -- i.e. it throws from OUTSIDE the conversion, so guarding
		// conversion
		// alone would still let one bad record poison the whole loop. Only a filter that
		// consults isSynthetic (realOnly, not all) reaches that cast.
		String sessionId = saveSession();
		repository.appendEvent(userEvent(sessionId, "good-1", "first good event"));
		jedisClient.rpush(KEY_PREFIX + sessionId + ":events", """
				{"id":"bad-synthetic","sessionId":"%s","timestamp":"2026-09-07T10:00:00Z",\
				"messageType":"USER","messageContent":"x","metadata":{"synthetic":"yes"},\
				"archived":false,"synthetic":false}""".formatted(sessionId));
		repository.appendEvent(userEvent(sessionId, "good-2", "second good event"));

		assertThat(repository.findEvents(sessionId, EventFilter.realOnly()))
			.as("an event that throws while being matched must be skipped, not propagated")
			.extracting(SessionEvent::getId)
			.containsExactly("good-1", "good-2");
		// EventFilter.all() never consults isSynthetic, so the bad record converts fine.
		assertThat(repository.findEvents(sessionId, EventFilter.all())).extracting(SessionEvent::getId)
			.containsExactly("good-1", "bad-synthetic", "good-2");
	}

	// -------------------------------------------------------------------------
	// Crash-backstop key deadlines
	// -------------------------------------------------------------------------

	@Test
	void backstopDeadlineLandsOnEveryPerSessionKeyAndNeverOnTheSharedIndexes() {
		// save() runs once per session lifetime, so a backstop applied only there reaches
		// the hash and nothing else: the four list/set keys do not exist yet at that
		// moment, and EXPIREAT on a missing key is a silent no-op. The keys created later
		// by appendEvent and compactEvents must therefore be stamped by the scripts that
		// create them -- otherwise an un-swept session leaves four keys behind that
		// nothing can reconcile, delete() included, since it reads userId from the hash
		// that just expired.
		String sessionId = saveSession();
		SessionEvent e1 = userEvent(sessionId, "e1", "one");
		repository.appendEvent(e1);

		assertThat(jedisClient.ttl(KEY_PREFIX + sessionId + ":events"))
			.as("the append path must stamp the active list it just created")
			.isPositive();
		assertThat(jedisClient.ttl(KEY_PREFIX + sessionId + ":active-ids"))
			.as("the append path must stamp the active-id set it just created")
			.isPositive();

		repository.appendEvent(userEvent(sessionId, "e2", "two"));
		assertThat(repository.compactEvents(sessionId, List.of(e1), List.of(userEvent(sessionId, "summary", "summary")),
				2L))
			.isTrue();

		assertThat(List.of("", ":events", ":archive", ":active-ids", ":archived-ids"))
			.allSatisfy(suffix -> assertThat(jedisClient.ttl(KEY_PREFIX + sessionId + suffix))
				.as("per-session key '%s' must carry the crash-backstop deadline", suffix)
				.isPositive());
		assertThat(jedisClient.ttl(KEY_PREFIX + "by-user:" + USER))
			.as("the by-user SET is shared across sessions and must never expire")
			.isEqualTo(-1L);
		assertThat(jedisClient.ttl(KEY_PREFIX + "expiry"))
			.as("the expiry ZSET is the sweep's source of truth and must never expire")
			.isEqualTo(-1L);
	}

	@Test
	void saveWithoutExpiryPersistsEveryPerSessionKeyAndLeavesTheExpiryZset() {
		// Re-saving with no expiry after having had one must CLEAR the earlier EXPIREAT,
		// not just skip stamping a new one. Otherwise Redis deletes a live,
		// explicitly-no-expiry session at the stale deadline -- and since save() has
		// already ZREM'd it, the sweep can never name it and delete() never runs.
		String sessionId = newSessionId();
		repository.save(
				Session.builder().id(sessionId).userId(USER).expiresAt(Instant.now().plus(Duration.ofDays(1))).build());
		// Append AND compact so all five per-session keys genuinely exist and have been
		// stamped: appending creates :events and :active-ids, and only a compaction
		// creates :archive and :archived-ids. Without the compaction those two would read
		// -2 (absent) rather than -1.
		SessionEvent e1 = userEvent(sessionId, "e1", "one");
		repository.appendEvent(e1);
		repository.appendEvent(userEvent(sessionId, "e2", "two"));
		assertThat(repository.compactEvents(sessionId, List.of(e1), List.of(userEvent(sessionId, "summary", "summary")),
				2L))
			.isTrue();
		assertThat(List.of("", ":events", ":archive", ":active-ids", ":archived-ids"))
			.as("precondition: every per-session key exists and carries the first save's deadline")
			.allSatisfy(suffix -> assertThat(jedisClient.ttl(KEY_PREFIX + sessionId + suffix)).isPositive());

		repository.save(Session.builder().id(sessionId).userId(USER).expiresAt(null).build());

		// -1 means "exists, no TTL". Asserting merely "not positive" would pass on -2,
		// which means the key is already gone -- the very failure this guards against.
		assertThat(List.of("", ":events", ":archive", ":active-ids", ":archived-ids"))
			.allSatisfy(suffix -> assertThat(jedisClient.ttl(KEY_PREFIX + sessionId + suffix))
				.as("per-session key '%s' must be PERSISTed, not left on the stale deadline", suffix)
				.isEqualTo(-1L));
		assertThat(jedisClient.zrangeByScore(KEY_PREFIX + "expiry", Double.NEGATIVE_INFINITY, Double.MAX_VALUE))
			.as("a session with no expiry does not belong in the sweep's ZSET")
			.doesNotContain(sessionId);
	}

	// -------------------------------------------------------------------------
	// Builder
	// -------------------------------------------------------------------------

	@Test
	void builderRequiresAClient() {
		assertThatThrownBy(() -> RedisSessionRepository.builder().build()).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("RedisClient is required");
	}

	// -------------------------------------------------------------------------
	// Fixtures
	// -------------------------------------------------------------------------

	private static String newSessionId() {
		return "sess-" + UUID.randomUUID();
	}

	private static String saveSession() {
		return saveSessionFor(USER);
	}

	private static String saveSessionFor(String userId) {
		String sessionId = newSessionId();
		repository.save(Session.builder().id(sessionId).userId(userId).build());
		return sessionId;
	}

	private static String saveSessionExpiringAt(Instant expiresAt) {
		String sessionId = newSessionId();
		repository.save(Session.builder().id(sessionId).userId(USER).expiresAt(expiresAt).build());
		return sessionId;
	}

	/**
	 * The shared compaction fixture, used by every test that asserts on post-compaction
	 * state: append {@code e1..e4}, then compact with {@code archived=[e1, e2]} and
	 * {@code retained=[summary, e4]} at version 4. {@code e3} is in neither list, so
	 * compaction drops it.
	 */
	private static String sessionCompactedOnce() {
		String sessionId = saveSession();
		SessionEvent e1 = userEvent(sessionId, "e1", "one");
		SessionEvent e2 = userEvent(sessionId, "e2", "two");
		SessionEvent e4 = userEvent(sessionId, "e4", "four");
		repository.appendEvent(e1);
		repository.appendEvent(e2);
		repository.appendEvent(userEvent(sessionId, "e3", "three"));
		repository.appendEvent(e4);

		boolean applied = repository.compactEvents(sessionId, List.of(e1, e2),
				List.of(userEvent(sessionId, "summary", "summary of one and two"), e4), 4L);

		assertThat(applied).as("fixture compaction must apply at the observed version").isTrue();
		return sessionId;
	}

	private static SessionEvent userEvent(String sessionId, String eventId, String text) {
		return SessionEvent.builder().sessionId(sessionId).id(eventId).message(new UserMessage(text)).build();
	}

	private static SessionEvent timestampedEvent(String sessionId, String eventId, String text, Instant timestamp) {
		return SessionEvent.builder()
			.sessionId(sessionId)
			.id(eventId)
			.timestamp(timestamp)
			.message(new UserMessage(text))
			.build();
	}

}
