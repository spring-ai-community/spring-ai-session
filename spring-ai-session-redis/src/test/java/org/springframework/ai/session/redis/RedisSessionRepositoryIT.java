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
import org.junit.jupiter.api.Nested;
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
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.compaction.CompactionPlan;
import org.springframework.ai.session.compaction.CompactionPlan.Insert;
import org.springframework.ai.session.test.AbstractSessionRepositoryContractTests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Tests for {@link RedisSessionRepository} against a real Redis server started by
 * Testcontainers. {@link Contract} runs the shared repository contract; the tests in
 * this class cover what only this backend can get wrong: the key layout and its
 * crash-backstop deadlines, the atomicity of the Lua scripts, and tolerance of damaged
 * records.
 *
 * <p>
 * Every test uses a fresh random session id, so tests never share state — the two index
 * structures are shared across the keyspace by design.
 */
@Testcontainers
class RedisSessionRepositoryIT {

	private static final String USER = "alice";

	private static final String KEY_PREFIX = "test-session:";

	private static final List<String> PER_SESSION_SUFFIXES = List.of("", ":log", ":active", ":event-data",
			":archived");

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

	@Nested
	class Contract extends AbstractSessionRepositoryContractTests {

		@Override
		protected SessionRepository createRepository() {
			// Qualified: the base class has its own `repository` field.
			return RedisSessionRepositoryIT.repository;
		}

	}

	@Test
	void appendOfAnExistingIdNeverOverwritesTheStoredEvent() {
		String sessionId = saveSession();
		repository.appendEvent(userEvent(sessionId, "e1", "one"));

		// Same id, different text: replaying the identical event would pass just as well
		// against an implementation that overwrote the stored copy.
		repository.appendEvent(userEvent(sessionId, "e1", "one, rewritten"));

		assertThat(repository.findEvents(sessionId, EventFilter.all()))
			.extracting(SessionEvent::getId, event -> event.getMessage().getText())
			.containsExactly(tuple("e1", "one"));
	}

	@Test
	void appendToAnUnknownSessionCreatesNoKeys() {
		String sessionId = newSessionId();

		assertThatThrownBy(() -> repository.appendEvent(userEvent(sessionId, "e1", "one")))
			.isInstanceOf(IllegalArgumentException.class);

		assertThat(PER_SESSION_SUFFIXES)
			.allSatisfy(suffix -> assertThat(jedisClient.exists(KEY_PREFIX + sessionId + suffix)).isFalse());
	}

	@Test
	void concurrentCompactionsAtTheSameVersionHaveExactlyOneWinner() {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			for (int iteration = 0; iteration < 50; iteration++) {
				String sessionId = saveSession();
				repository.appendEvent(userEvent(sessionId, "e1", "one"));
				repository.appendEvent(userEvent(sessionId, "e2", "two"));
				long version = repository.getEventVersion(sessionId);
				List<SessionEvent> summaries = List.of(userEvent(sessionId, "summary-a", "summary a"),
						userEvent(sessionId, "summary-b", "summary b"));
				// The barrier only aligns the start of two synchronous calls.
				CyclicBarrier barrier = new CyclicBarrier(summaries.size());

				List<CompletableFuture<Boolean>> futures = summaries.stream()
					.map(summary -> CompletableFuture.supplyAsync(() -> {
						awaitQuietly(barrier);
						return repository.applyCompaction(sessionId,
								new CompactionPlan(Set.of("e1"), List.of(Insert.before("e2", List.of(summary)))),
								version);
					}, executor))
					.toList();
				List<Boolean> results = futures.stream().map(CompletableFuture::join).toList();

				assertThat(results).as("iteration %d", iteration).containsExactlyInAnyOrder(true, false);
				assertThat(repository.getEventVersion(sessionId)).isEqualTo(version + 1);
				assertThat(repository.findEvents(sessionId, EventFilter.active())).extracting(SessionEvent::getId)
					.containsExactly(summaries.get(results.indexOf(true)).getId(), "e2");
			}
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	void compactionRejectsInsertingAnExistingEventId() {
		String sessionId = saveSession();
		repository.appendEvent(userEvent(sessionId, "e1", "one"));
		repository.appendEvent(userEvent(sessionId, "e2", "two"));
		CompactionPlan plan = new CompactionPlan(Set.of(),
				List.of(Insert.atEnd(List.of(userEvent(sessionId, "e1", "rewritten")))));

		assertThatThrownBy(() -> repository.applyCompaction(sessionId, plan, 2L))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(repository.findEvents(sessionId, EventFilter.all()))
			.extracting(SessionEvent::getId, event -> event.getMessage().getText())
			.containsExactly(tuple("e1", "one"), tuple("e2", "two"));
		assertThat(repository.getEventVersion(sessionId)).isEqualTo(2L);
	}

	@Test
	void eventsAreReturnedInLogOrderNotTimestampOrder() {
		// The summary carries the oldest timestamp, yet the log puts it after both archived
		// events. Sorting by timestamp would put it first and corrupt the prompt window.
		String sessionId = saveSession();
		Instant base = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		repository.appendEvent(timestampedEvent(sessionId, "e1", "one", base.plusSeconds(100)));
		repository.appendEvent(timestampedEvent(sessionId, "e2", "two", base.plusSeconds(200)));
		repository.appendEvent(timestampedEvent(sessionId, "e3", "three", base.plusSeconds(300)));
		SessionEvent summary = timestampedEvent(sessionId, "summary", "summary", base.plusSeconds(1));

		assertThat(repository.applyCompaction(sessionId,
				new CompactionPlan(Set.of("e1", "e2"), List.of(Insert.before("e3", List.of(summary)))), 3L))
			.isTrue();

		assertThat(repository.findEvents(sessionId, EventFilter.all()))
			.extracting(SessionEvent::getId, SessionEvent::getTimestamp)
			.containsExactly(tuple("e1", base.plusSeconds(100)), tuple("e2", base.plusSeconds(200)),
					tuple("summary", base.plusSeconds(1)), tuple("e3", base.plusSeconds(300)));
	}

	@Test
	void toolCallsAndToolResponsesRoundTrip() {
		String sessionId = saveSession();
		List<ToolCall> toolCalls = List.of(new ToolCall("call-1", "function", "getWeather", "{\"city\":\"Paris\"}"),
				new ToolCall("call-2", "function", "getTime", "{}"));
		repository.appendEvent(SessionEvent.builder()
			.sessionId(sessionId)
			.id("assistant")
			.message(AssistantMessage.builder().content("calling tools").toolCalls(toolCalls).build())
			.build());
		repository.appendEvent(SessionEvent.builder()
			.sessionId(sessionId)
			.id("tool")
			.message(ToolResponseMessage.builder()
				.responses(List.of(new ToolResponse("call-1", "getWeather", "{\"temp\":12}")))
				.build())
			.build());
		repository.appendEvent(SessionEvent.builder()
			.sessionId(sessionId)
			.id("synthetic")
			.message(new UserMessage("summary prompt"))
			.metadata(SessionEvent.METADATA_SYNTHETIC, true)
			.build());

		List<SessionEvent> events = repository.findEvents(sessionId, EventFilter.all());

		assertThat(events).extracting(SessionEvent::getId, SessionEvent::getMessageType)
			.containsExactly(tuple("assistant", MessageType.ASSISTANT), tuple("tool", MessageType.TOOL),
					tuple("synthetic", MessageType.USER));
		AssistantMessage assistant = (AssistantMessage) events.get(0).getMessage();
		assertThat(assistant.getText()).isEqualTo("calling tools");
		assertThat(assistant.getToolCalls())
			.extracting(ToolCall::id, ToolCall::type, ToolCall::name, ToolCall::arguments)
			.containsExactly(tuple("call-1", "function", "getWeather", "{\"city\":\"Paris\"}"),
					tuple("call-2", "function", "getTime", "{}"));
		ToolResponseMessage toolResponse = (ToolResponseMessage) events.get(1).getMessage();
		assertThat(toolResponse.getResponses())
			.extracting(ToolResponse::id, ToolResponse::name, ToolResponse::responseData)
			.containsExactly(tuple("call-1", "getWeather", "{\"temp\":12}"));
		assertThat(events.get(2).isSynthetic()).isTrue();
	}

	@Test
	void malformedStoredRecordsAreSkippedAndTheRestIsStillRead() {
		// Written straight into the event keys, because no public API can produce these.
		String sessionId = saveSession();
		repository.appendEvent(userEvent(sessionId, "good-1", "first"));
		writeRawEvent(sessionId, "bad-type", """
				{"id":"bad-type","session_id":"%s","timestamp":"2026-09-07T10:00:00Z",\
				"message_type":"NOT_A_TYPE","message_content":"x","metadata":{}}""".formatted(sessionId));
		writeRawEvent(sessionId, "bad-timestamp", """
				{"id":"bad-timestamp","session_id":"%s","timestamp":"not-an-instant",\
				"message_type":"USER","message_content":"x","metadata":{}}""".formatted(sessionId));
		writeRawEvent(sessionId, "truncated", "{\"id\":\"truncated\",\"session_id\":\"" + sessionId + "\"");
		writeRawEvent(sessionId, "empty-ish", "{\"id\":\"empty-ish\"}");
		// Converts fine but throws from SessionEvent#isSynthetic, which EventFilter#apply
		// calls for every event.
		writeRawEvent(sessionId, "bad-synthetic", """
				{"id":"bad-synthetic","session_id":"%s","timestamp":"2026-09-07T10:00:00Z",\
				"message_type":"USER","message_content":"x","metadata":{"synthetic":"yes"}}""".formatted(sessionId));
		repository.appendEvent(userEvent(sessionId, "good-2", "second"));

		assertThat(List.of(EventFilter.all(), EventFilter.realOnly(), EventFilter.active()))
			.allSatisfy(filter -> assertThat(repository.findEvents(sessionId, filter)).extracting(SessionEvent::getId)
				.containsExactly("good-1", "good-2"));
	}

	@Test
	void findByIdKeepsNanosecondInstantsAndMetadata() {
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
		// The hash stores ISO-8601, not the millisecond ZSET score.
		assertThat(found.createdAt()).isEqualTo(createdAt);
		assertThat(found.expiresAt()).isEqualTo(expiresAt);
		assertThat(found.metadata()).isEqualTo(metadata);
	}

	@Test
	void deleteExpiredSessionsIsStrictlyBefore() {
		Instant bound = Instant.now().minus(Duration.ofDays(1)).truncatedTo(ChronoUnit.MILLIS);
		String early = saveSessionExpiringAt(bound.minusMillis(1));
		String atBound = saveSessionExpiringAt(bound);

		repository.deleteExpiredSessions(bound);

		assertThat(repository.findById(early)).isNull();
		assertThat(repository.findById(atBound)).isNotNull();
	}

	@Test
	void backstopDeadlineLandsOnEveryPerSessionKeyAndNeverOnTheSharedIndexes() {
		// save() runs before the event keys exist, and EXPIREAT on a missing key is a
		// no-op, so the scripts that create those keys must stamp them.
		String sessionId = sessionCompactedOnce();

		assertThat(PER_SESSION_SUFFIXES)
			.allSatisfy(suffix -> assertThat(jedisClient.ttl(KEY_PREFIX + sessionId + suffix)).as(suffix).isPositive());
		assertThat(jedisClient.ttl(KEY_PREFIX + "by-user:" + USER)).isEqualTo(-1L);
		assertThat(jedisClient.ttl(KEY_PREFIX + "expiry")).isEqualTo(-1L);
	}

	@Test
	void resaveWithoutExpiryPersistsEveryPerSessionKey() {
		// Clearing the expiry must also clear the earlier EXPIREAT, or Redis deletes a live
		// session after it has left the expiry ZSET, where no sweep can find it.
		String sessionId = sessionCompactedOnce();

		repository.save(Session.builder().id(sessionId).userId(USER).expiresAt(null).build());

		// -1 is "exists, no TTL"; -2 would mean the key is already gone.
		assertThat(PER_SESSION_SUFFIXES)
			.allSatisfy(suffix -> assertThat(jedisClient.ttl(KEY_PREFIX + sessionId + suffix)).as(suffix).isEqualTo(-1L));
		assertThat(jedisClient.zrangeByScore(KEY_PREFIX + "expiry", Double.NEGATIVE_INFINITY, Double.MAX_VALUE))
			.doesNotContain(sessionId);
	}

	@Test
	void deleteLeavesNoKeysBehind() {
		String sessionId = sessionCompactedOnce();

		repository.delete(sessionId);

		List<String> scanned = new ArrayList<>();
		String cursor = ScanParams.SCAN_POINTER_START;
		do {
			ScanResult<String> page = jedisClient.scan(cursor, new ScanParams().match(KEY_PREFIX + "*").count(1000));
			scanned.addAll(page.getResult());
			cursor = page.getCursor();
		}
		while (!ScanParams.SCAN_POINTER_START.equals(cursor));

		assertThat(scanned).noneMatch(key -> key.contains(sessionId));
		assertThat(jedisClient.zrangeByScore(KEY_PREFIX + "expiry", Double.NEGATIVE_INFINITY, Double.MAX_VALUE))
			.doesNotContain(sessionId);
		assertThat(jedisClient.smembers(KEY_PREFIX + "by-user:" + USER)).doesNotContain(sessionId);
	}

	// -------------------------------------------------------------------------
	// Fixtures
	// -------------------------------------------------------------------------

	private static String newSessionId() {
		return "sess-" + UUID.randomUUID();
	}

	private static String saveSession() {
		String sessionId = newSessionId();
		repository.save(Session.builder().id(sessionId).userId(USER).build());
		return sessionId;
	}

	private static String saveSessionExpiringAt(Instant expiresAt) {
		String sessionId = newSessionId();
		repository.save(Session.builder().id(sessionId).userId(USER).expiresAt(expiresAt).build());
		return sessionId;
	}

	/** Bypasses the append script, so the record lands in the log exactly as given. */
	private static void writeRawEvent(String sessionId, String eventId, String json) {
		jedisClient.hset(KEY_PREFIX + sessionId + ":event-data", eventId, json);
		jedisClient.rpush(KEY_PREFIX + sessionId + ":log", eventId);
		jedisClient.rpush(KEY_PREFIX + sessionId + ":active", eventId);
	}

	/**
	 * Appends {@code e1..e3} and archives {@code e1} with a summary inserted before
	 * {@code e2}, so all five per-session keys and both shared indexes exist.
	 */
	private static String sessionCompactedOnce() {
		String sessionId = newSessionId();
		repository.save(
				Session.builder().id(sessionId).userId(USER).expiresAt(Instant.now().plus(Duration.ofDays(1))).build());
		repository.appendEvent(userEvent(sessionId, "e1", "one"));
		repository.appendEvent(userEvent(sessionId, "e2", "two"));
		repository.appendEvent(userEvent(sessionId, "e3", "three"));
		SessionEvent summary = userEvent(sessionId, "summary", "summary of one");
		assertThat(repository.applyCompaction(sessionId,
				new CompactionPlan(Set.of("e1"), List.of(Insert.before("e2", List.of(summary)))), 3L))
			.isTrue();
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

	private static void awaitQuietly(CyclicBarrier barrier) {
		try {
			barrier.await(30, TimeUnit.SECONDS);
		}
		catch (Exception ex) {
			throw new IllegalStateException("race barrier failed", ex);
		}
	}

}
