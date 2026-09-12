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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.RedisClient;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;
import org.springframework.util.Assert;

/**
 * Redis-backed implementation of {@link SessionRepository}.
 *
 * <h2>Key layout</h2>
 * <p>
 * Five keys are owned exclusively by a single session, and two shared index structures
 * keep {@link #findByUserId} and {@link #findExpiredSessionIds} off {@code SCAN}:
 * <ul>
 * <li>{@code <prefix><sessionId>} — HASH of session metadata, including
 * {@code eventVersion}</li>
 * <li>{@code <prefix><sessionId>:events} — LIST, the active event window</li>
 * <li>{@code <prefix><sessionId>:archive} — LIST, the archived events</li>
 * <li>{@code <prefix><sessionId>:active-ids} — SET of active event ids</li>
 * <li>{@code <prefix><sessionId>:archived-ids} — SET of archived event ids</li>
 * <li>{@code <prefix>by-user:<userId>} — shared SET, the by-user index</li>
 * <li>{@code <prefix>expiry} — shared ZSET scored by {@code expiresAt} epoch-milli</li>
 * </ul>
 * A session id must not end in one of the reserved suffixes ({@code :events},
 * {@code :archive}, {@code :active-ids}, {@code :archived-ids}) — keys are built by
 * concatenation, so such an id would make one session's metadata hash collide with
 * another session's event list. Ids may otherwise contain any character, including
 * {@code :}.
 *
 * <h2>Event ordering</h2>
 * <p>
 * Events are returned in LIST insertion order, never sorted by
 * {@link SessionEvent#getTimestamp()}. Insertion order is the Redis equivalent of the
 * monotonic {@code seq} column the JDBC implementation orders by, and the distinction is
 * load-bearing: a synthetic compaction summary carries the compaction wall-clock time but
 * must sit ahead of the older active-window events it introduces. Archived events are
 * always the oldest prefix of the log, so {@code archive ++ active} is already
 * chronological.
 *
 * <h2>Optimistic concurrency (CAS)</h2>
 * <p>
 * The {@code eventVersion} field of the session hash is incremented on every
 * {@link #appendEvent} and {@link #compactEvents}. Both mutating operations run as a
 * single Lua script so the compare-and-swap is atomic; splitting compaction into a
 * read-check-then-write in Java would let two concurrent compactions both succeed and
 * double the active window.
 *
 * <h2>Idempotent append</h2>
 * <p>
 * The two event-id SETs play the role of the JDBC primary key: {@link #appendEvent} is a
 * no-op, and does not bump the version, when the id is already present in either set.
 *
 * <h2>Expiry</h2>
 * <p>
 * Session lifetime is an application-driven sweep — the caller reads
 * {@link #findExpiredSessionIds(Instant)} and calls {@link #delete(String)} for each id —
 * so the expiry ZSET, not the Redis keyspace, is the source of truth. Keys deliberately
 * do <em>not</em> carry a native TTL matched to {@link Session#expiresAt()}: they would
 * evaporate the moment they expired and the sweep would never observe them in the window
 * where deletion is meaningful.
 * <p>
 * A crash backstop still bounds the keyspace when a sweeper never runs. {@link #save}
 * stores {@code expiresAt} plus {@link Builder#keyTtlGrace(Duration)} as an absolute
 * epoch-second in the {@code ttlDeadline} hash field, and both {@code save()} and the two
 * mutating Lua scripts {@code EXPIREAT} the session-scoped keys they touch to exactly
 * that instant. The deadline is absolute rather than a relative TTL precisely so this
 * split coverage is sound: {@code save()} runs once per session lifetime, when the four
 * list/set keys do not yet exist and {@code EXPIRE} on a missing key is a silent no-op,
 * so whichever writer creates a key must stamp it with the same instant. A deadline
 * already in the past is never applied, in Java or in Lua — {@code EXPIREAT} with a past
 * timestamp deletes the key immediately, which would delete the session behind the
 * sweep's back. Choose a grace that dwarfs the sweep interval. The two shared index keys
 * never get a deadline.
 *
 * <h2>Self-healing indexes</h2>
 * <p>
 * {@code save()} writes the indexes before the hash, so a crash part-way through leaves
 * them over-reporting rather than under-reporting: {@link #findByUserId} skips members
 * whose hash is gone, and {@code delete()} on a ZSET member with no hash is an idempotent
 * no-op. The reverse order would risk a session in no index at all, which
 * {@code findExpiredSessionIds} could never name — a permanent leak.
 *
 * <h2>Message serialization</h2>
 * <p>
 * Each event is stored as one JSON document whose fields mirror the JDBC
 * {@code AI_SESSION_EVENT} columns, {@code messageData} included — a String holding JSON,
 * double-encoded inside the outer document, exactly like the column. Timestamps are
 * ISO-8601 strings so the stored format carries no dependency on the mapper's
 * java-time-module configuration. A record that cannot be read back is logged at WARN and
 * skipped rather than failing the whole read: without that, a single bad record would
 * make an entire session permanently unreadable.
 *
 * <h2>Unbounded archive reads</h2>
 * <p>
 * When {@link EventFilter#excludeArchived()} is {@code false}, {@link #findEvents} reads
 * the whole archive list. That is the contract — the SPI requires <em>all</em> matching
 * events, and both the compaction input and the prompt window are built from this output,
 * so truncating would silently yield a wrong context window. The live chat path is
 * unaffected: {@code SessionMemoryAdvisor} force-merges {@link EventFilter#active()}, so
 * the archive is not read at all. Callers that do search the archive should pass
 * {@code lastN} or {@code pageSize}. Reading the archive in bounded {@code LRANGE}
 * windows is not an option: that is by definition several round trips, which breaks the
 * atomic point-in-time snapshot {@code findEvents} depends on.
 *
 * <h2>Thread safety</h2>
 * <p>
 * The class is stateless beyond its configuration and is thread-safe as long as the
 * supplied {@link RedisClient} is.
 *
 * @author David J. M. Karlsen
 * @since 0.9.0
 */
public final class RedisSessionRepository implements SessionRepository {

	/** Default prefix for every key this repository owns. */
	public static final String DEFAULT_KEY_PREFIX = "spring-ai:session:";

	/**
	 * Default grace added to {@link Session#expiresAt()} to form the crash-backstop
	 * deadline.
	 */
	public static final Duration DEFAULT_KEY_TTL_GRACE = Duration.ofDays(30);

	private static final Logger logger = LoggerFactory.getLogger(RedisSessionRepository.class);

	/** The session hash does not exist. */
	private static final int SESSION_NOT_FOUND = -1;

	/**
	 * Duplicate event id (append), or event-log version mismatch (compaction). Nothing
	 * mutated.
	 */
	private static final int NOT_APPLIED = 0;

	/** The mutation was applied and the event-log version incremented. */
	private static final int APPLIED = 1;

	private static final String FIELD_ID = "id";

	private static final String FIELD_USER_ID = "userId";

	private static final String FIELD_CREATED_AT = "createdAt";

	private static final String FIELD_EXPIRES_AT = "expiresAt";

	private static final String FIELD_METADATA = "metadata";

	/**
	 * The event-log version lives as a field of the session hash so the existence check,
	 * the version read and the CAS increment are a single-key operation inside Lua —
	 * mirroring the JDBC implementation, where {@code event_version} is a column of the
	 * session row.
	 * <p>
	 * Coupled to both Lua scripts: Lua cannot reference a Java constant, so
	 * {@link #APPEND_EVENT_SCRIPT} and {@link #COMPACT_EVENTS_SCRIPT} hard-code the
	 * literal {@code 'eventVersion'}. Renaming this constant's value means renaming it in
	 * both scripts too; nothing fails at compile time and the drift silently breaks the
	 * compare-and-swap.
	 */
	private static final String FIELD_EVENT_VERSION = "eventVersion";

	/**
	 * Absolute epoch-second at which the crash backstop expires every key of this session
	 * — {@code expiresAt} plus the configured grace, computed once by
	 * {@link #save(Session)}. Absent means the session has no {@code expiresAt} and
	 * therefore no backstop.
	 * <p>
	 * Coupled to both Lua scripts for the same reason as {@link #FIELD_EVENT_VERSION}:
	 * they hard-code the literal {@code 'ttlDeadline'}.
	 */
	private static final String FIELD_TTL_DEADLINE = "ttlDeadline";

	private static final String EVENTS_SUFFIX = ":events";

	private static final String ARCHIVE_SUFFIX = ":archive";

	private static final String ACTIVE_IDS_SUFFIX = ":active-ids";

	private static final String ARCHIVED_IDS_SUFFIX = ":archived-ids";

	private static final String BY_USER_INFIX = "by-user:";

	private static final String EXPIRY_SUFFIX = "expiry";

	// @formatter:off

	/**
	 * Prepended to both mutating scripts. Re-stamps the crash-backstop deadline onto every
	 * session-scoped key the calling script was handed, so a key created by {@code RPUSH} or
	 * {@code SADD} long after {@code save()} still carries an expiry. Declared {@code local}
	 * because a global function declaration is rejected by the Redis Lua sandbox.
	 * <p>
	 * This helper requires {@code KEYS[1]} to be the session hash — it reads the deadline with
	 * {@code HGET KEYS[1]}. Any future script that prepends it must pass the session hash first;
	 * a different {@code KEYS[1]} yields a runtime {@code WRONGTYPE}, not a compile error.
	 */
	private static final String BACKSTOP_DEADLINE_LUA = """
			local function applyBackstopDeadline()
			  -- KEYS[1] (the session hash) holds the absolute epoch-second save() computed as
			  -- expiresAt + keyTtlGrace. No field means the session has no expiry, hence no
			  -- backstop. A deadline already in the past is deliberately NOT applied: EXPIREAT
			  -- with a past timestamp deletes the key immediately, which would delete the session
			  -- behind the sweep's back. Every KEYS entry is session-scoped -- the shared by-user
			  -- SET and expiry ZSET are never passed in and must never get a deadline.
			  local deadline = tonumber(redis.call('HGET', KEYS[1], 'ttlDeadline'))
			  if deadline == nil or deadline <= tonumber(redis.call('TIME')[1]) then
			    return
			  end
			  for i = 1, #KEYS do
			    redis.call('EXPIREAT', KEYS[i], deadline)
			  end
			end
			""";

	private static final String APPEND_EVENT_SCRIPT = BACKSTOP_DEADLINE_LUA + """
			-- Idempotent append. KEYS: 1=session hash, 2=active list, 3=active-id set,
			-- 4=archived-id set, 5=archive list. KEYS[5] is never read or written here; it is
			-- passed so the backstop deadline covers every per-session key, including one an
			-- earlier compaction created.
			-- ARGV: 1=event id, 2=event JSON. Returns -1 (no session), 0 (duplicate id), 1 (appended).
			if redis.call('EXISTS', KEYS[1]) == 0 then
			  return -1
			end
			if redis.call('SISMEMBER', KEYS[3], ARGV[1]) == 1
			    or redis.call('SISMEMBER', KEYS[4], ARGV[1]) == 1 then
			  -- Idempotent replay: an event with this id was already committed (e.g. a retried
			  -- append after a crash). No-op: do not duplicate it and do not bump the version.
			  return 0
			end
			redis.call('RPUSH', KEYS[2], ARGV[2])
			redis.call('SADD', KEYS[3], ARGV[1])
			redis.call('HINCRBY', KEYS[1], 'eventVersion', 1)
			applyBackstopDeadline()
			return 1
			""";

	private static final String COMPACT_EVENTS_SCRIPT = BACKSTOP_DEADLINE_LUA + """
			-- Atomic compare-and-swap compaction. KEYS: 1=session hash, 2=active list,
			-- 3=archive list, 4=active-id set, 5=archived-id set. ARGV: 1=expectedVersion,
			-- 2=archivedCount, 3=retainedCount, 4..=archived (id,json) pairs followed by
			-- retained (id,json) pairs.
			-- Returns -1 (no session), 0 (version mismatch, nothing mutated), 1 (applied).
			if redis.call('EXISTS', KEYS[1]) == 0 then
			  return -1
			end
			local current = tonumber(redis.call('HGET', KEYS[1], 'eventVersion')) or 0
			if current ~= tonumber(ARGV[1]) then
			  -- Another writer mutated the log between the caller's read and this write.
			  -- Mutate NOTHING.
			  return 0
			end
			local archivedCount = tonumber(ARGV[2])
			local retainedCount = tonumber(ARGV[3])
			local cursor = 4
			-- (a) Events already in KEYS[3] are left untouched, preserving their original order.
			-- (b) Append the newly archived events; the caller has already flipped their archived
			-- flag. One RPUSH per event, never a variadic RPUSH with unpack(), which overflows the
			-- Lua C stack (LUAI_MAXCSTACK) on a large compaction.
			for _ = 1, archivedCount do
			  redis.call('RPUSH', KEYS[3], ARGV[cursor + 1])
			  redis.call('SADD', KEYS[5], ARGV[cursor])
			  cursor = cursor + 2
			end
			-- (c) Replace the entire active window. The DEL is what removes any previously-active
			-- event that appears in NEITHER list (e.g. a superseded synthetic summary) -- it
			-- mirrors the JDBC DELETE_ACTIVE_EVENTS. Dropping the active-id set in the same breath
			-- frees those ids for a future append, matching the JDBC behaviour where the rows are
			-- gone and the primary key no longer collides. The newly-archived events were pushed
			-- before this DEL so they are briefly in both lists, but the script is atomic: no
			-- reader can observe that intermediate state.
			redis.call('DEL', KEYS[2])
			redis.call('DEL', KEYS[4])
			for _ = 1, retainedCount do
			  redis.call('RPUSH', KEYS[2], ARGV[cursor + 1])
			  redis.call('SADD', KEYS[4], ARGV[cursor])
			  cursor = cursor + 2
			end
			redis.call('HINCRBY', KEYS[1], 'eventVersion', 1)
			applyBackstopDeadline()
			return 1
			""";

	private static final String READ_EVENTS_SCRIPT = """
			-- Atomic two-list snapshot. KEYS: 1=archive list, 2=active list. No ARGV.
			-- Returns a two-element array: {archive entries, active entries}, both in insertion
			-- order. One EVAL, so the two LRANGEs observe the same instant and no compaction can
			-- commit between them.
			return { redis.call('LRANGE', KEYS[1], 0, -1), redis.call('LRANGE', KEYS[2], 0, -1) }
			""";

	// @formatter:on

	private final RedisClient jedisClient;

	private final String keyPrefix;

	private final Duration keyTtlGrace;

	private final JsonMapper jsonMapper;

	private RedisSessionRepository(RedisClient jedisClient, String keyPrefix, Duration keyTtlGrace,
			JsonMapper jsonMapper) {
		this.jedisClient = jedisClient;
		this.keyPrefix = keyPrefix;
		this.keyTtlGrace = keyTtlGrace;
		this.jsonMapper = jsonMapper;
	}

	// -------------------------------------------------------------------------
	// SessionRepository — session lifecycle
	// -------------------------------------------------------------------------

	/**
	 * Writes the session's metadata fields, refreshes both index structures and stamps
	 * the crash-backstop deadline.
	 * <p>
	 * The field map is built explicitly and written with {@code HSET}. Never {@code DEL}
	 * + {@code HSET}, and never {@code HSET} a map derived from an {@code HGETALL}:
	 * either would reset {@link #FIELD_EVENT_VERSION} and turn every in-flight
	 * {@link #compactEvents} compare-and-swap into a silent no-op. An existing session's
	 * event log and version survive a re-save, per the interface contract.
	 * <p>
	 * The read-then-{@code SREM} that fixes up the by-user SET after a {@code userId}
	 * change is not atomic, so a concurrent re-save under a different {@code userId} may
	 * leave a stale member. Benign: {@link #findByUserId} drops members whose hash is
	 * missing.
	 */
	@Override
	public Session save(Session session) {
		Assert.notNull(session, "session must not be null");
		String sessionKey = sessionKey(session.id());
		String previousUserId = this.jedisClient.hget(sessionKey, FIELD_USER_ID);
		Instant expiresAt = session.expiresAt();

		// The index writes deliberately come BEFORE the HSET -- see the self-healing note
		// in the class javadoc.
		if (expiresAt != null) {
			this.jedisClient.zadd(expiryKey(), expiresAt.toEpochMilli(), session.id());
		}
		else {
			this.jedisClient.zrem(expiryKey(), session.id());
		}
		this.jedisClient.sadd(byUserKey(session.userId()), session.id());

		Map<String, String> fields = new HashMap<>();
		fields.put(FIELD_ID, session.id());
		fields.put(FIELD_USER_ID, session.userId());
		fields.put(FIELD_CREATED_AT, session.createdAt().toString());
		fields.put(FIELD_METADATA, toJson(session.metadata()));
		if (expiresAt != null) {
			fields.put(FIELD_EXPIRES_AT, expiresAt.toString());
			fields.put(FIELD_TTL_DEADLINE, Long.toString(backstopDeadline(expiresAt)));
		}
		this.jedisClient.hset(sessionKey, fields);
		if (expiresAt == null) {
			this.jedisClient.hdel(sessionKey, FIELD_EXPIRES_AT, FIELD_TTL_DEADLINE);
		}

		if (previousUserId != null && !previousUserId.equals(session.userId())) {
			this.jedisClient.srem(byUserKey(previousUserId), session.id());
		}

		applyBackstopDeadline(session.id(), expiresAt);
		return session;
	}

	@Override
	public @Nullable Session findById(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		Map<String, String> fields = this.jedisClient.hgetAll(sessionKey(sessionId));
		return fields.isEmpty() ? null : toSession(fields);
	}

	/**
	 * Members whose session hash is gone are skipped — see the self-healing note in the
	 * class javadoc. Order is unspecified by the contract.
	 */
	@Override
	public List<Session> findByUserId(String userId) {
		Assert.hasText(userId, "userId must not be null or empty");
		return this.jedisClient.smembers(byUserKey(userId))
			.stream()
			.map(this::findById)
			.filter(Objects::nonNull)
			.toList();
	}

	/**
	 * The {@code (} prefix makes the upper bound exclusive, which is the contract: the
	 * in-memory and JDBC implementations both use strictly-before, so a session whose
	 * {@code expiresAt} equals {@code before} is not expired.
	 * <p>
	 * Millisecond scores with an exclusive bound can only under-report, never
	 * over-report, which is the safe direction: an under-reported session is swept on the
	 * next pass, whereas an over-reported one would be deleted while still live.
	 * Sub-millisecond {@code expiresAt} fidelity is preserved separately in the hash's
	 * ISO-8601 field, so {@link #findById} still round-trips the exact instant.
	 */
	@Override
	public List<String> findExpiredSessionIds(Instant before) {
		Assert.notNull(before, "before must not be null");
		return this.jedisClient.zrangeByScore(expiryKey(), "-inf", "(" + before.toEpochMilli());
	}

	@Override
	public void delete(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		// The userId has to be read before the hash goes away -- it names the by-user SET
		// to clean up.
		String userId = this.jedisClient.hget(sessionKey(sessionId), FIELD_USER_ID);
		if (userId != null) {
			this.jedisClient.srem(byUserKey(userId), sessionId);
		}
		this.jedisClient.zrem(expiryKey(), sessionId);
		this.jedisClient.del(perSessionKeys(sessionId));
	}

	// -------------------------------------------------------------------------
	// SessionRepository — event log
	// -------------------------------------------------------------------------

	@Override
	public void appendEvent(SessionEvent event) {
		Assert.notNull(event, "event must not be null");
		String sessionId = event.getSessionId();
		int code = evalStatusCode(APPEND_EVENT_SCRIPT, List.of(sessionKey(sessionId), eventsKey(sessionId),
				activeIdsKey(sessionId), archivedIdsKey(sessionId), archiveKey(sessionId)),
				List.of(event.getId(), toJson(event)));
		switch (code) {
			case SESSION_NOT_FOUND -> throw new IllegalArgumentException("Session not found: " + sessionId);
			case NOT_APPLIED ->
				logger.debug("appendEvent: event {} already exists for session {}; treating as an idempotent replay",
						event.getId(), sessionId);
			case APPLIED -> {
				// Appended; nothing further to do.
			}
			default -> throw unexpectedStatusCode(code);
		}
	}

	/**
	 * Archived events are stored as {@link SessionEvent#asArchived()} copies, which is
	 * what upholds the "every archive entry is archived" invariant the
	 * {@link #findEvents} fast path depends on.
	 * <p>
	 * Retained events are stored exactly as the caller supplied them, matching the
	 * in-memory and JDBC implementations, neither of which normalises the archived flag
	 * of a retained event.
	 */
	@Override
	public boolean compactEvents(String sessionId, List<SessionEvent> archivedEvents, List<SessionEvent> retainedEvents,
			long expectedVersion) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		Assert.notNull(archivedEvents, "archivedEvents must not be null");
		Assert.notNull(retainedEvents, "retainedEvents must not be null");

		List<String> args = new ArrayList<>();
		args.add(Long.toString(expectedVersion));
		args.add(Integer.toString(archivedEvents.size()));
		args.add(Integer.toString(retainedEvents.size()));
		archivedEvents.stream().map(SessionEvent::asArchived).forEach(event -> addIdAndJson(args, event));
		retainedEvents.forEach(event -> addIdAndJson(args, event));

		int code = evalStatusCode(COMPACT_EVENTS_SCRIPT, List.of(sessionKey(sessionId), eventsKey(sessionId),
				archiveKey(sessionId), activeIdsKey(sessionId), archivedIdsKey(sessionId)), args);
		return switch (code) {
			case SESSION_NOT_FOUND -> throw new IllegalArgumentException("Session not found: " + sessionId);
			case NOT_APPLIED -> false;
			case APPLIED -> true;
			default -> throw unexpectedStatusCode(code);
		};
	}

	/** Returns {@code 0} for both an unknown session and one with no events yet. */
	@Override
	public long getEventVersion(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		String version = this.jedisClient.hget(sessionKey(sessionId), FIELD_EVENT_VERSION);
		return version != null ? Long.parseLong(version) : 0L;
	}

	/**
	 * There is deliberately no {@code EXISTS} check for the unknown-session case: an
	 * unknown session has no list keys, so {@code LRANGE} returns empty and the result is
	 * an empty list — identical to the explicit empty return of the other
	 * implementations, but one round trip cheaper.
	 */
	@Override
	public List<SessionEvent> findEvents(String sessionId, EventFilter filter) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		Assert.notNull(filter, "filter must not be null");

		List<String> raw = readEventsSnapshot(sessionId, filter.excludeArchived());

		// List insertion order is the ordering. Deliberately NOT sorted by timestamp --
		// see the class javadoc. A record that cannot be converted, or that throws while
		// being matched, is skipped rather than propagated.
		List<SessionEvent> matched = new ArrayList<>();
		for (String json : raw) {
			SessionEvent event = toEventOrNull(json);
			if (event != null && matchesOrSkip(event, filter)) {
				matched.add(event);
			}
		}

		return Collections.unmodifiableList(applyPagination(matched, filter));
	}

	/**
	 * Applies {@code lastN} / {@code page}+{@code pageSize} slicing to an already
	 * fully-filtered, insertion-ordered event list, mirroring
	 * {@code InMemorySessionRepository} so all backends behave identically. Both slices
	 * are taken from the chronological list, so page 0 holds the oldest matching events.
	 */
	private static List<SessionEvent> applyPagination(List<SessionEvent> matched, EventFilter filter) {
		if (filter.lastN() != null) {
			if (matched.size() > filter.lastN()) {
				return new ArrayList<>(matched.subList(matched.size() - filter.lastN(), matched.size()));
			}
			return matched;
		}
		if (filter.pageSize() != null) {
			int page = filter.page() != null ? filter.page() : 0;
			long fromIndexLong = (long) page * filter.pageSize();
			if (fromIndexLong >= matched.size()) {
				return new ArrayList<>();
			}
			int fromIndex = (int) fromIndexLong;
			int toIndex = (int) Math.min(fromIndexLong + filter.pageSize(), matched.size());
			return new ArrayList<>(matched.subList(fromIndex, toIndex));
		}
		return matched;
	}

	// -------------------------------------------------------------------------
	// Keys
	// -------------------------------------------------------------------------

	private String sessionKey(String sessionId) {
		return this.keyPrefix + sessionId;
	}

	private String eventsKey(String sessionId) {
		return sessionKey(sessionId) + EVENTS_SUFFIX;
	}

	private String archiveKey(String sessionId) {
		return sessionKey(sessionId) + ARCHIVE_SUFFIX;
	}

	private String activeIdsKey(String sessionId) {
		return sessionKey(sessionId) + ACTIVE_IDS_SUFFIX;
	}

	private String archivedIdsKey(String sessionId) {
		return sessionKey(sessionId) + ARCHIVED_IDS_SUFFIX;
	}

	/** Shared across every session of one user, so it never gets a TTL. */
	private String byUserKey(String userId) {
		return this.keyPrefix + BY_USER_INFIX + userId;
	}

	/** Shared by every session, so it never gets a TTL. */
	private String expiryKey() {
		return this.keyPrefix + EXPIRY_SUFFIX;
	}

	/**
	 * The five keys owned exclusively by one session — everything {@link #delete}
	 * removes.
	 */
	private String[] perSessionKeys(String sessionId) {
		return new String[] { sessionKey(sessionId), eventsKey(sessionId), archiveKey(sessionId),
				activeIdsKey(sessionId), archivedIdsKey(sessionId) };
	}

	/**
	 * Stamps, or clears, the crash-backstop deadline on the five per-session keys.
	 * <p>
	 * The {@code expiresAt == null} branch must {@code PERSIST} rather than do nothing. A
	 * session re-saved with no expiry after having had one would otherwise keep the
	 * earlier {@code EXPIREAT}, so Redis would delete a live, explicitly-no-expiry
	 * session at that stale deadline — and because {@code save()} has already
	 * {@code ZREM}'d it from the expiry ZSET, the sweep could never name it.
	 */
	private void applyBackstopDeadline(String sessionId, @Nullable Instant expiresAt) {
		if (expiresAt == null) {
			for (String key : perSessionKeys(sessionId)) {
				this.jedisClient.persist(key);
			}
			return;
		}
		long deadline = backstopDeadline(expiresAt);
		if (deadline <= Instant.now().getEpochSecond()) {
			// Already past expiry + grace. Leave the keys alone so the sweep can still
			// see and delete them; EXPIREAT with a past timestamp would delete the
			// session behind the sweep's back.
			return;
		}
		for (String key : perSessionKeys(sessionId)) {
			this.jedisClient.expireAt(key, deadline);
		}
	}

	private long backstopDeadline(Instant expiresAt) {
		return expiresAt.plus(this.keyTtlGrace).getEpochSecond();
	}

	// -------------------------------------------------------------------------
	// Lua
	// -------------------------------------------------------------------------

	/**
	 * Reads the event log as a single point-in-time snapshot, archive entries first.
	 * <p>
	 * Two separate {@code LRANGE} round trips would not be equivalent, and the difference
	 * is silent data loss rather than a nicety. With {@code archive=[a1]},
	 * {@code active=[e2, e3]}: a reader takes the archive, a compaction then commits
	 * (archiving {@code e2}, retaining {@code [summary]}), the reader takes the active
	 * list and returns {@code [a1, summary]} — {@code e2} is absent from the returned
	 * history even though it is durably stored. Swapping the order is not a fix: reading
	 * the active list first turns silent loss into silent duplication.
	 * <p>
	 * The {@code excludeArchived} fast path stays a single {@code LRANGE} — one key,
	 * already atomic, and it is the hot path. It is sound only because every entry of the
	 * archive list is archived, an invariant upheld by {@link #compactEvents} being the
	 * list's only writer and always storing {@link SessionEvent#asArchived()} copies.
	 */
	private List<String> readEventsSnapshot(String sessionId, boolean excludeArchived) {
		if (excludeArchived) {
			return this.jedisClient.lrange(eventsKey(sessionId), 0, -1);
		}
		Object result = this.jedisClient.eval(READ_EVENTS_SCRIPT, List.of(archiveKey(sessionId), eventsKey(sessionId)),
				List.of());
		if (!(result instanceof List<?> lists) || lists.size() != 2) {
			throw new IllegalStateException(
					"Redis event-snapshot script returned " + (result != null ? result.getClass().getName() : "null")
							+ "; expected a two-element array of lists");
		}
		List<String> raw = new ArrayList<>();
		for (Object list : lists) {
			if (!(list instanceof List<?> entries)) {
				throw new IllegalStateException(
						"Redis event-snapshot script returned a non-list element; expected LRANGE results");
			}
			for (Object entry : entries) {
				if (!(entry instanceof String json)) {
					throw new IllegalStateException(
							"Redis event-snapshot script returned a non-string entry; expected event JSON");
				}
				raw.add(json);
			}
		}
		// Archived events are always the oldest prefix of the log, so archive ++ active
		// is already chronological -- no merge sort needed.
		return raw;
	}

	/**
	 * Guards {@link EventFilter#matches} for the same reason {@link #toEventOrNull}
	 * guards conversion: {@code matches} calls {@link SessionEvent#isSynthetic()}, which
	 * casts a metadata value to {@code boolean}, so a stored {@code synthetic} value of
	 * the wrong JSON type throws from outside the conversion.
	 */
	private static boolean matchesOrSkip(SessionEvent event, EventFilter filter) {
		try {
			return filter.matches(event);
		}
		catch (RuntimeException ex) {
			logger.warn("Skipping stored session event {} that could not be matched: {}", event.getId(),
					ex.getMessage());
			return false;
		}
	}

	private int evalStatusCode(String script, List<String> keys, List<String> args) {
		Object result = this.jedisClient.eval(script, keys, args);
		if (!(result instanceof Long code)) {
			throw new IllegalStateException("Redis session script returned " + result + " of type "
					+ (result != null ? result.getClass().getName() : "null") + "; expected an integer status code");
		}
		return code.intValue();
	}

	private static IllegalStateException unexpectedStatusCode(int code) {
		return new IllegalStateException("Redis session script returned unknown status code " + code);
	}

	// -------------------------------------------------------------------------
	// Serialization
	// -------------------------------------------------------------------------

	/**
	 * The persisted shape of a {@link SessionEvent} — one field per
	 * {@code AI_SESSION_EVENT} column of the JDBC implementation, {@code messageData}
	 * included: a String holding JSON, double-encoded inside the outer document, exactly
	 * like the column.
	 * <p>
	 * {@code synthetic} is redundant with {@code metadata["synthetic"]}. The JDBC
	 * implementation carries both — a column plus the map — and merges the column back
	 * into the map on read; the redundancy is kept so behaviour is identical.
	 */
	private record EventJson(String id, String sessionId, String timestamp, String messageType,
			@Nullable String messageContent, @Nullable String messageData, @Nullable Map<String, Object> metadata,
			@Nullable String branch, boolean archived, boolean synthetic) {
	}

	private void addIdAndJson(List<String> args, SessionEvent event) {
		args.add(event.getId());
		args.add(toJson(event));
	}

	private String toJson(SessionEvent event) {
		Message message = event.getMessage();
		return toJson(new EventJson(event.getId(), event.getSessionId(), event.getTimestamp().toString(),
				message.getMessageType().name(), message.getText(), messageDataToJson(message), event.getMetadata(),
				event.getBranch(), event.isArchived(), event.isSynthetic()));
	}

	private String toJson(Object value) {
		try {
			return this.jsonMapper.writeValueAsString(value);
		}
		catch (JacksonException ex) {
			throw new IllegalStateException("Failed to serialize value to JSON", ex);
		}
	}

	/**
	 * Serializes type-specific {@link Message} payload to JSON:
	 * <ul>
	 * <li>{@link AssistantMessage} with tool calls → JSON array of tool calls</li>
	 * <li>{@link ToolResponseMessage} → JSON array of tool responses</li>
	 * <li>All other types → {@code null}</li>
	 * </ul>
	 */
	private @Nullable String messageDataToJson(Message message) {
		if (message instanceof AssistantMessage am && am.hasToolCalls()) {
			return toJson(am.getToolCalls());
		}
		if (message instanceof ToolResponseMessage trm) {
			return toJson(trm.getResponses());
		}
		return null;
	}

	/**
	 * Best-effort per-event conversion: an unreadable record is logged at WARN and
	 * skipped, never propagated. {@link #findEvents} converts every stored record in a
	 * loop, so an exception escaping that loop would make the entire session permanently
	 * unreadable over a single bad record — and records can go bad without any bug of
	 * ours (a partially-written value, a manual {@code redis-cli} edit, a
	 * {@link MessageType} constant renamed out from under already-persisted data).
	 * <p>
	 * The catch is deliberately {@link RuntimeException} rather than an enumerated list,
	 * because the conversion has more throw sites than are obvious: malformed JSON,
	 * {@link Instant#parse}, {@link MessageType#valueOf}, and
	 * {@code SessionEvent.Builder#build()}'s own assertion on a blank stored id — which
	 * throws {@link IllegalArgumentException}, the very type this class uses for "Session
	 * not found", so letting it escape would surface a misleading error.
	 * <p>
	 * Only the event id and the exception message are logged; message content and
	 * metadata may hold sensitive data.
	 */
	private @Nullable SessionEvent toEventOrNull(String json) {
		EventJson parsed;
		try {
			parsed = this.jsonMapper.readValue(json, EventJson.class);
		}
		catch (RuntimeException ex) {
			// The id lives inside the unparseable document, so there is nothing safe to
			// name it by.
			logger.warn("Skipping unparseable stored session event: {}", ex.getMessage());
			return null;
		}
		try {
			return toEvent(parsed);
		}
		catch (RuntimeException ex) {
			logger.warn("Skipping unconvertible stored session event {}: {}", parsed.id(), ex.getMessage());
			return null;
		}
	}

	private SessionEvent toEvent(EventJson parsed) {
		// Validated rather than dereferenced blindly so a record missing a required field
		// surfaces as a clear IllegalArgumentException rather than a bare NPE.
		Assert.hasText(parsed.timestamp(), "timestamp must not be null or empty");
		Assert.hasText(parsed.messageType(), "messageType must not be null or empty");
		Map<String, Object> metadata = new HashMap<>(Objects.requireNonNullElse(parsed.metadata(), Map.of()));
		if (parsed.synthetic()) {
			metadata.put(SessionEvent.METADATA_SYNTHETIC, true);
		}
		return SessionEvent.builder()
			.id(parsed.id())
			.sessionId(parsed.sessionId())
			.timestamp(Instant.parse(parsed.timestamp()))
			.message(
					toMessage(MessageType.valueOf(parsed.messageType()), parsed.messageContent(), parsed.messageData()))
			.branch(parsed.branch())
			.archived(parsed.archived())
			.metadata(metadata)
			.build();
	}

	private Message toMessage(MessageType type, @Nullable String content, @Nullable String messageData) {
		return switch (type) {
			case USER -> new UserMessage(content != null ? content : "");
			case SYSTEM -> new SystemMessage(content != null ? content : "");
			case ASSISTANT -> {
				if (hasData(messageData)) {
					yield AssistantMessage.builder().content(content).toolCalls(parseToolCalls(messageData)).build();
				}
				yield new AssistantMessage(content != null ? content : "");
			}
			case TOOL -> {
				if (hasData(messageData)) {
					yield ToolResponseMessage.builder().responses(parseToolResponses(messageData)).build();
				}
				yield ToolResponseMessage.builder().responses(List.of()).build();
			}
		};
	}

	private static boolean hasData(@Nullable String messageData) {
		return messageData != null && !messageData.isBlank();
	}

	private List<AssistantMessage.ToolCall> parseToolCalls(String json) {
		try {
			return this.jsonMapper.readValue(json, new TypeReference<List<AssistantMessage.ToolCall>>() {
			});
		}
		catch (JacksonException ex) {
			logger.warn("Failed to deserialize tool calls from JSON; returning empty list", ex);
			return List.of();
		}
	}

	private List<ToolResponseMessage.ToolResponse> parseToolResponses(String json) {
		try {
			return this.jsonMapper.readValue(json, new TypeReference<List<ToolResponseMessage.ToolResponse>>() {
			});
		}
		catch (JacksonException ex) {
			logger.warn("Failed to deserialize tool responses from JSON; returning empty list", ex);
			return List.of();
		}
	}

	private Session toSession(Map<String, String> fields) {
		Session.Builder builder = Session.builder()
			.id(fields.get(FIELD_ID))
			.userId(fields.get(FIELD_USER_ID))
			.createdAt(Instant.parse(fields.get(FIELD_CREATED_AT)))
			.metadata(fromJsonMap(fields.get(FIELD_METADATA)));
		String expiresAt = fields.get(FIELD_EXPIRES_AT);
		// Only call the setter when the field is present: expiresAt(null) would override
		// the builder's own default rather than mean "no expiry".
		if (expiresAt != null) {
			builder.expiresAt(Instant.parse(expiresAt));
		}
		return builder.build();
	}

	private Map<String, Object> fromJsonMap(@Nullable String json) {
		if (json == null || json.isBlank()) {
			return Map.of();
		}
		try {
			return this.jsonMapper.readValue(json, new TypeReference<Map<String, Object>>() {
			});
		}
		catch (JacksonException ex) {
			logger.warn("Failed to deserialize metadata JSON; returning empty map", ex);
			return new HashMap<>();
		}
	}

	/** Returns a new {@link Builder}. */
	public static Builder builder() {
		return new Builder();
	}

	// -------------------------------------------------------------------------
	// Builder
	// -------------------------------------------------------------------------

	/**
	 * Builder for {@link RedisSessionRepository}.
	 * <p>
	 * Minimum required: {@link #jedisClient(RedisClient)}. All other fields default to
	 * sensible values.
	 */
	public static final class Builder {

		@Nullable
		private RedisClient jedisClient;

		private String keyPrefix = DEFAULT_KEY_PREFIX;

		private Duration keyTtlGrace = DEFAULT_KEY_TTL_GRACE;

		private JsonMapper jsonMapper = JsonMapper.builder().build();

		private Builder() {
		}

		/** Sets the Jedis client. Required. */
		public Builder jedisClient(RedisClient jedisClient) {
			this.jedisClient = jedisClient;
			return this;
		}

		/**
		 * Overrides the prefix every key of this repository is built under. Defaults to
		 * {@value #DEFAULT_KEY_PREFIX}.
		 */
		public Builder keyPrefix(String keyPrefix) {
			this.keyPrefix = keyPrefix;
			return this;
		}

		/**
		 * Overrides the grace added to {@link Session#expiresAt()} to form the
		 * crash-backstop deadline at which Redis expires the session's keys by itself.
		 * Must dwarf the interval at which the application sweeps
		 * {@link #findExpiredSessionIds(Instant)}, so the backstop can never pre-empt the
		 * sweep. Defaults to 30 days.
		 */
		public Builder keyTtlGrace(Duration keyTtlGrace) {
			this.keyTtlGrace = keyTtlGrace;
			return this;
		}

		/**
		 * Overrides the {@link JsonMapper} used for metadata and event serialization.
		 * Defaults to {@code JsonMapper.builder().build()}.
		 */
		public Builder jsonMapper(JsonMapper jsonMapper) {
			this.jsonMapper = jsonMapper;
			return this;
		}

		/** Builds the repository. */
		public RedisSessionRepository build() {
			Assert.notNull(this.jedisClient, "A RedisClient is required — set via jedisClient()");
			Assert.hasText(this.keyPrefix, "keyPrefix must not be null or empty");
			Assert.notNull(this.keyTtlGrace, "keyTtlGrace must not be null");
			Assert.isTrue(!this.keyTtlGrace.isNegative(), "keyTtlGrace must not be negative");
			return new RedisSessionRepository(this.jedisClient, this.keyPrefix, this.keyTtlGrace, this.jsonMapper);
		}

	}

}
