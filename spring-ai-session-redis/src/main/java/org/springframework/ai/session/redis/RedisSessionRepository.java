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
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.RedisClient;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.compaction.CompactionPlan;
import org.springframework.ai.session.support.SessionEventCodec;
import org.springframework.util.Assert;

/**
 * Redis-backed {@link SessionRepository}. Plain Redis 6.2+ — no schema and no Redis
 * modules. Five keys per session plus two shared index structures, so neither
 * {@link #findByUserId} nor {@link #deleteExpiredSessions} has to {@code SCAN} the
 * keyspace:
 *
 * <pre>
 * &lt;prefix&gt;&lt;sessionId&gt;              HASH  session metadata, eventVersion, ttlDeadline
 * &lt;prefix&gt;&lt;sessionId&gt;:log          LIST  every event id, in log order
 * &lt;prefix&gt;&lt;sessionId&gt;:active       LIST  the non-archived event ids, in log order
 * &lt;prefix&gt;&lt;sessionId&gt;:event-data   HASH  event id → event JSON
 * &lt;prefix&gt;&lt;sessionId&gt;:archived     SET   archived event ids
 * &lt;prefix&gt;by-user:&lt;userId&gt;         SET   shared by-user index
 * &lt;prefix&gt;expiry                   ZSET  shared, scored by expiresAt epoch-milli
 * </pre>
 *
 * <p>
 * <b>Ordering is the {@code :log} list, never {@link SessionEvent#getTimestamp()}.</b> A
 * compaction summary carries the compaction wall-clock time but must sit <em>ahead</em>
 * of the older events it introduces, so sorting by timestamp would mis-order the prompt
 * window. {@link #applyCompaction} archives events in place and inserts new ones before
 * their anchor, which is why the log is a list of ids: compaction rewrites ids only and
 * never re-encodes event JSON, and the archived flag lives in the {@code :archived} SET
 * rather than inside the JSON.
 *
 * <p>
 * <b>{@code :active} is a projection of {@code :log}, kept for the hot path.</b>
 * {@code SessionMemoryAdvisor} reads with {@link EventFilter#active()} on every turn, and
 * a long-lived session's archived history can be orders of magnitude larger than its
 * active window, so {@link #findEvents} with {@link EventFilter#excludeArchived()} reads
 * {@code :active} and never touches archived events. Both mutating event scripts keep the
 * projection in step with {@code :log} and {@code :archived}.
 *
 * <p>
 * <b>Native key TTL does not drive expiry.</b> Session lifetime is an application-driven
 * sweep ({@link #deleteExpiredSessions}). If the session keys carried a native TTL
 * matched to {@link Session#expiresAt()} they would evaporate the moment they expired,
 * leaving the expiry ZSET naming sessions that no longer exist. The ZSET is the source of
 * truth for expiry.
 *
 * <p>
 * <b>The crash backstop is an absolute deadline, re-applied by every write.</b>
 * {@link #save} stores {@code expiresAt} plus {@link Builder#keyTtlGrace(Duration)} as an
 * epoch-second in the session hash, and every mutating script {@code EXPIREAT}s the five
 * per-session keys to exactly that instant — absolute rather than relative, so a key
 * created by a later append still expires together with the hash. A deadline already in
 * the past is never applied: {@code EXPIREAT} with a past timestamp deletes the key at
 * once, behind the sweep's back. The grace must dwarf the sweep interval so the backstop
 * never pre-empts the sweep. The two shared index keys never get a deadline. If the
 * backstop does fire, {@link #findByUserId} skips by-user members whose hash is gone and
 * the next sweep removes the dangling expiry entry; the by-user member itself stays, as
 * its key is named by the {@code userId} in the expired hash.
 *
 * <p>
 * <b>Key namespace.</b> A session id must not end in one of the reserved suffixes
 * ({@code :log}, {@code :active}, {@code :event-data}, {@code :archived}), or one
 * session's metadata hash would collide with another session's event key. Colons are
 * otherwise allowed in ids. Every key is built under the configured prefix.
 *
 * <p>
 * Every mutation runs as a single Lua script, so its existence check, compare-and-swap
 * and writes are atomic. Scripts receive the five per-session keys as
 * {@code KEYS[1..5]}, session hash first; shared index keys come after them.
 *
 * <p>
 * The class is stateless beyond its configuration and is thread-safe as long as the
 * supplied {@link RedisClient} is.
 *
 * @author David J. M. Karlsen
 * @since 0.11.0
 */
public final class RedisSessionRepository implements SessionRepository {

	/** Default prefix every key is built under. */
	public static final String DEFAULT_KEY_PREFIX = "spring-ai:session:";

	/**
	 * Default grace added to {@link Session#expiresAt()} to form the crash-backstop
	 * deadline.
	 */
	public static final Duration DEFAULT_KEY_TTL_GRACE = Duration.ofDays(30);

	private static final Logger logger = LoggerFactory.getLogger(RedisSessionRepository.class);

	/** The session hash does not exist. */
	private static final int SESSION_NOT_FOUND = -1;

	/** Duplicate event id (append), or event-log version mismatch (compaction). */
	private static final int NOT_APPLIED = 0;

	/** The mutation was applied and the event-log version incremented. */
	private static final int APPLIED = 1;

	/** A compaction plan names an unknown archive/anchor id or an existing insert id. */
	private static final int UNKNOWN_EVENT = -2;

	private static final String FIELD_ID = "id";

	private static final String FIELD_USER_ID = "userId";

	private static final String FIELD_CREATED_AT = "createdAt";

	private static final String FIELD_EXPIRES_AT = "expiresAt";

	private static final String FIELD_METADATA = "metadata";

	/**
	 * The event-log version lives in the session hash, so the existence check, the
	 * version read and the CAS increment are a single-key operation inside Lua. The
	 * scripts hard-code {@code 'eventVersion'}; rename both together.
	 */
	private static final String FIELD_EVENT_VERSION = "eventVersion";

	// @formatter:off

	/**
	 * Prepended to every mutating script. Re-stamps the crash-backstop deadline (the
	 * {@code ttlDeadline} hash field, written by the save script) onto the five
	 * per-session keys. Declared {@code local}: the Redis Lua sandbox rejects globals.
	 */
	private static final String BACKSTOP_DEADLINE_LUA = """
			local function applyBackstopDeadline()
			  -- No field means the session has no expiry, hence no backstop. A deadline in the
			  -- past is NOT applied: EXPIREAT with a past timestamp deletes the key at once.
			  local deadline = tonumber(redis.call('HGET', KEYS[1], 'ttlDeadline'))
			  if deadline == nil or deadline <= tonumber(redis.call('TIME')[1]) then
			    return
			  end
			  for i = 1, 5 do
			    redis.call('EXPIREAT', KEYS[i], deadline)
			  end
			end
			""";

	private static final String SAVE_SESSION_SCRIPT = BACKSTOP_DEADLINE_LUA + """
			-- KEYS: 1..5=per-session keys, 6=expiry ZSET, 7=by-user SET for the new userId,
			-- 8=by-user SET for the userId stored before this call (equal to 7 if unchanged).
			-- ARGV: 1=insertOnly ('1'/'0'), 2=sessionId, 3=userId, 4=createdAt, 5=metadata JSON,
			-- 6=expiresAt ('' for none), 7=expiresAt epoch-milli, 8=ttlDeadline epoch-second.
			-- Returns the stored createdAt, or nil when insertOnly and the session exists.
			local exists = redis.call('EXISTS', KEYS[1]) == 1
			if exists and ARGV[1] == '1' then
			  return false
			end
			-- An existing session keeps its original createdAt.
			local createdAt = ARGV[4]
			if exists then
			  createdAt = redis.call('HGET', KEYS[1], 'createdAt') or ARGV[4]
			end
			if KEYS[8] ~= KEYS[7] then
			  redis.call('SREM', KEYS[8], ARGV[2])
			end
			-- HSET of named fields only, never DEL + HSET: that would reset eventVersion and
			-- turn every in-flight compaction compare-and-swap into a silent no-op.
			redis.call('HSET', KEYS[1], 'id', ARGV[2], 'userId', ARGV[3], 'createdAt', createdAt,
			    'metadata', ARGV[5])
			redis.call('SADD', KEYS[7], ARGV[2])
			if ARGV[6] ~= '' then
			  redis.call('HSET', KEYS[1], 'expiresAt', ARGV[6], 'ttlDeadline', ARGV[8])
			  redis.call('ZADD', KEYS[6], ARGV[7], ARGV[2])
			  applyBackstopDeadline()
			else
			  -- PERSIST, not merely skip: a session re-saved without expiry would otherwise keep
			  -- an earlier deadline and be deleted after leaving the expiry ZSET.
			  redis.call('HDEL', KEYS[1], 'expiresAt', 'ttlDeadline')
			  redis.call('ZREM', KEYS[6], ARGV[2])
			  for i = 1, 5 do
			    redis.call('PERSIST', KEYS[i])
			  end
			end
			return createdAt
			""";

	private static final String APPEND_EVENT_SCRIPT = BACKSTOP_DEADLINE_LUA + """
			-- Idempotent append. KEYS: 1..5=per-session keys (hash, log, active, event-data,
			-- archived). ARGV: 1=event id, 2=event JSON, 3=archived ('1'/'0').
			-- Returns -1 (no session), 0 (duplicate id), 1 (appended).
			if redis.call('EXISTS', KEYS[1]) == 0 then
			  return -1
			end
			if redis.call('HEXISTS', KEYS[4], ARGV[1]) == 1 then
			  return 0
			end
			redis.call('HSET', KEYS[4], ARGV[1], ARGV[2])
			redis.call('RPUSH', KEYS[2], ARGV[1])
			if ARGV[3] == '1' then
			  redis.call('SADD', KEYS[5], ARGV[1])
			else
			  redis.call('RPUSH', KEYS[3], ARGV[1])
			end
			redis.call('HINCRBY', KEYS[1], 'eventVersion', 1)
			applyBackstopDeadline()
			return 1
			""";

	private static final String APPLY_COMPACTION_SCRIPT = BACKSTOP_DEADLINE_LUA + """
			-- Atomic compare-and-swap compaction. KEYS: 1..5=per-session keys (hash, log, active,
			-- event-data, archived). ARGV: 1=expectedVersion, 2=archiveCount, 3=groupCount, then
			-- the archive ids, then per insert group: anchor id ('' to append), event count,
			-- (id, json) pairs. Returns -1 (no session), 0 (version mismatch), -2 (unknown
			-- archive/anchor id or existing insert id), 1 (applied). Nothing is mutated unless
			-- 1 is returned.
			if redis.call('EXISTS', KEYS[1]) == 0 then
			  return -1
			end
			local current = tonumber(redis.call('HGET', KEYS[1], 'eventVersion')) or 0
			if current ~= tonumber(ARGV[1]) then
			  return 0
			end
			local cursor = 4
			local archiveIds = {}
			for _ = 1, tonumber(ARGV[2]) do
			  if redis.call('HEXISTS', KEYS[4], ARGV[cursor]) == 0 then
			    return -2
			  end
			  archiveIds[#archiveIds + 1] = ARGV[cursor]
			  cursor = cursor + 1
			end
			local groupsByAnchor = {}
			local trailing = {}
			for _ = 1, tonumber(ARGV[3]) do
			  local anchor = ARGV[cursor]
			  local count = tonumber(ARGV[cursor + 1])
			  cursor = cursor + 2
			  local group = trailing
			  if anchor ~= '' then
			    -- An archived anchor still counts as existing: it is in the log, just flagged.
			    if redis.call('HEXISTS', KEYS[4], anchor) == 0 then
			      return -2
			    end
			    group = groupsByAnchor[anchor] or {}
			    groupsByAnchor[anchor] = group
			  end
			  for _ = 1, count do
			    -- An inserted event must be new: an existing id would overwrite the stored
			    -- event and appear twice in :log.
			    if redis.call('HEXISTS', KEYS[4], ARGV[cursor]) == 1 then
			      return -2
			    end
			    group[#group + 1] = { ARGV[cursor], ARGV[cursor + 1] }
			    cursor = cursor + 2
			  end
			end
			-- Validation is complete; from here on the plan is applied in full.
			for _, id in ipairs(archiveIds) do
			  redis.call('SADD', KEYS[5], id)
			end
			-- Rebuild :log with the inserts in place, and :active as its non-archived
			-- projection. One RPUSH per id, never a variadic RPUSH with unpack(), which
			-- overflows the Lua C stack on a long log.
			local log = redis.call('LRANGE', KEYS[2], 0, -1)
			redis.call('DEL', KEYS[2], KEYS[3])
			local function push(id)
			  redis.call('RPUSH', KEYS[2], id)
			  if redis.call('SISMEMBER', KEYS[5], id) == 0 then
			    redis.call('RPUSH', KEYS[3], id)
			  end
			end
			local function insert(group)
			  for _, event in ipairs(group) do
			    redis.call('HSET', KEYS[4], event[1], event[2])
			    push(event[1])
			  end
			end
			for _, id in ipairs(log) do
			  local group = groupsByAnchor[id]
			  if group then
			    insert(group)
			  end
			  push(id)
			end
			insert(trailing)
			redis.call('HINCRBY', KEYS[1], 'eventVersion', 1)
			applyBackstopDeadline()
			return 1
			""";

	private static final String READ_EVENTS_SCRIPT = """
			-- Atomic snapshot. KEYS: 1=id list (:log or :active), 2=event-data, 3=archived.
			-- Returns a flat array of (event JSON, archived 0/1) pairs in list order. One EVAL,
			-- so no compaction can commit between reading the ids and the events they name.
			local result = {}
			for _, id in ipairs(redis.call('LRANGE', KEYS[1], 0, -1)) do
			  local json = redis.call('HGET', KEYS[2], id)
			  if json then
			    result[#result + 1] = json
			    result[#result + 1] = redis.call('SISMEMBER', KEYS[3], id)
			  end
			end
			return result
			""";

	private static final String DELETE_IF_EXPIRED_SCRIPT = """
			-- KEYS: 1..5=per-session keys, 6=expiry ZSET, 7=by-user SET. ARGV: 1=sessionId,
			-- 2=cut-off epoch-milli. The expiry is re-checked atomically with the delete, so a
			-- session whose TTL a concurrent save() just extended is left alone.
			-- Returns 1 (a session was deleted) or 0 (not expired, or only a dangling entry).
			local score = redis.call('ZSCORE', KEYS[6], ARGV[1])
			if not score or tonumber(score) >= tonumber(ARGV[2]) then
			  return 0
			end
			local existed = redis.call('EXISTS', KEYS[1])
			redis.call('DEL', KEYS[1], KEYS[2], KEYS[3], KEYS[4], KEYS[5])
			redis.call('ZREM', KEYS[6], ARGV[1])
			redis.call('SREM', KEYS[7], ARGV[1])
			return existed
			""";

	// @formatter:on

	private final RedisClient jedisClient;

	private final String keyPrefix;

	private final Duration keyTtlGrace;

	private final JsonMapper jsonMapper;

	private final SessionEventCodec codec;

	private RedisSessionRepository(RedisClient jedisClient, String keyPrefix, Duration keyTtlGrace,
			JsonMapper jsonMapper) {
		this.jedisClient = jedisClient;
		this.keyPrefix = keyPrefix;
		this.keyTtlGrace = keyTtlGrace;
		this.jsonMapper = jsonMapper;
		this.codec = new SessionEventCodec(jsonMapper);
	}

	// -------------------------------------------------------------------------
	// Sessions
	// -------------------------------------------------------------------------

	/**
	 * Returns the session as stored: an existing session keeps its original
	 * {@code createdAt}, its event log and its event-log version.
	 * <p>
	 * The previous {@code userId} is read before the script so the script can name the
	 * by-user SET to leave. That read is not atomic with the script, so a concurrent
	 * re-save under a different {@code userId} may leave a stale member. Benign:
	 * {@link #findByUserId} drops members whose hash is missing.
	 */
	@Override
	public Session save(Session session) {
		Assert.notNull(session, "session must not be null");
		String createdAt = saveSession(session, false);
		return Session.builder()
			.id(session.id())
			.userId(session.userId())
			.createdAt(Instant.parse(Objects.requireNonNull(createdAt)))
			.expiresAt(session.expiresAt())
			.metadata(session.metadata())
			.build();
	}

	@Override
	public boolean saveIfAbsent(Session session) {
		Assert.notNull(session, "session must not be null");
		return saveSession(session, true) != null;
	}

	@Override
	public @Nullable Session findById(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		Map<String, String> fields = this.jedisClient.hgetAll(sessionKey(sessionId));
		return fields.isEmpty() ? null : toSession(fields);
	}

	/**
	 * Members whose session hash is gone are skipped — see the class javadoc. Order is
	 * unspecified by the contract.
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

	@Override
	public void delete(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		// The userId has to be read before the hash goes away: it names the by-user SET.
		String userId = this.jedisClient.hget(sessionKey(sessionId), FIELD_USER_ID);
		if (userId != null) {
			this.jedisClient.srem(byUserKey(userId), sessionId);
		}
		this.jedisClient.zrem(expiryKey(), sessionId);
		this.jedisClient.del(perSessionKeys(sessionId).toArray(String[]::new));
	}

	/**
	 * The {@code (} prefix makes the candidate range exclusive, matching the contract's
	 * strictly-before. Millisecond scores with an exclusive bound can only under-report,
	 * never over-report: an under-reported session is swept on the next pass, whereas an
	 * over-reported one would be deleted while still live. Each candidate is re-checked
	 * and deleted atomically by {@link #DELETE_IF_EXPIRED_SCRIPT}.
	 */
	@Override
	public int deleteExpiredSessions(Instant before) {
		Assert.notNull(before, "before must not be null");
		String cutOff = Long.toString(before.toEpochMilli());
		int deleted = 0;
		for (String sessionId : this.jedisClient.zrangeByScore(expiryKey(), "-inf", "(" + cutOff)) {
			String userId = this.jedisClient.hget(sessionKey(sessionId), FIELD_USER_ID);
			List<String> keys = new ArrayList<>(perSessionKeys(sessionId));
			keys.add(expiryKey());
			keys.add(byUserKey(Objects.requireNonNullElse(userId, "")));
			deleted += evalStatusCode(DELETE_IF_EXPIRED_SCRIPT, keys, List.of(sessionId, cutOff));
		}
		return deleted;
	}

	// -------------------------------------------------------------------------
	// Events
	// -------------------------------------------------------------------------

	@Override
	public void appendEvent(SessionEvent event) {
		Assert.notNull(event, "event must not be null");
		String sessionId = event.getSessionId();
		int code = evalStatusCode(APPEND_EVENT_SCRIPT, perSessionKeys(sessionId),
				List.of(event.getId(), toJson(event), event.isArchived() ? "1" : "0"));
		switch (code) {
			case SESSION_NOT_FOUND -> throw new IllegalArgumentException("Session not found: " + sessionId);
			case NOT_APPLIED -> logger.debug("Idempotent replay of event {} on session {}: not appended",
					event.getId(), sessionId);
			case APPLIED -> {
				// Appended; nothing further to do.
			}
			default -> throw unexpectedStatusCode(code);
		}
	}

	@Override
	public boolean applyCompaction(String sessionId, CompactionPlan plan, long expectedVersion) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		Assert.notNull(plan, "plan must not be null");
		// The archived flag is SET membership, assigned by archiveIds; an event inserted
		// already archived would land in :active and read back as active.
		Assert.isTrue(plan.insertedEvents().stream().noneMatch(SessionEvent::isArchived),
				"compaction plan must not insert archived events");
		Assert.isTrue(plan.insertedEvents().stream().map(SessionEvent::getId).distinct().count() == plan
			.insertedEvents()
			.size(), "compaction plan must not insert the same event id twice");

		List<String> args = new ArrayList<>();
		args.add(Long.toString(expectedVersion));
		args.add(Integer.toString(plan.archiveIds().size()));
		args.add(Integer.toString(plan.inserts().size()));
		args.addAll(plan.archiveIds());
		for (CompactionPlan.Insert insert : plan.inserts()) {
			args.add(Objects.requireNonNullElse(insert.beforeEventId(), ""));
			args.add(Integer.toString(insert.events().size()));
			for (SessionEvent event : insert.events()) {
				args.add(event.getId());
				args.add(toJson(event));
			}
		}

		int code = evalStatusCode(APPLY_COMPACTION_SCRIPT, perSessionKeys(sessionId), args);
		return switch (code) {
			case SESSION_NOT_FOUND -> throw new IllegalArgumentException("Session not found: " + sessionId);
			case UNKNOWN_EVENT -> throw new IllegalArgumentException(
					"Compaction plan names an unknown archive or anchor event, or inserts an existing one, in session "
							+ sessionId);
			case NOT_APPLIED -> false;
			case APPLIED -> true;
			default -> throw unexpectedStatusCode(code);
		};
	}

	/** {@code 0} covers both "no such session" and "session has no events yet". */
	@Override
	public long getEventVersion(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		String version = this.jedisClient.hget(sessionKey(sessionId), FIELD_EVENT_VERSION);
		return (version != null) ? Long.parseLong(version) : 0L;
	}

	/**
	 * Pushes {@link EventFilter#excludeArchived()} down by reading {@code :active}
	 * instead of {@code :log}; everything else goes through {@link EventFilter#apply},
	 * the reference implementation of the read contract. An unknown session has no list
	 * keys, so the result is empty without an existence check.
	 * <p>
	 * With archived events included the read covers the whole log, which only grows. The
	 * contract requires every matching event, so the read is not truncated; callers that
	 * search archived history should pass {@code lastN} or {@code pageSize}. Reading in
	 * chunks is not an option either: several round trips would break the snapshot.
	 */
	@Override
	public List<SessionEvent> findEvents(String sessionId, EventFilter filter) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		Assert.notNull(filter, "filter must not be null");
		return filter.apply(readEventsSnapshot(sessionId, filter.excludeArchived()));
	}

	// -------------------------------------------------------------------------
	// Keys
	// -------------------------------------------------------------------------

	private String sessionKey(String sessionId) {
		return this.keyPrefix + sessionId;
	}

	/** Shared across every session of one user, so it never gets a TTL. */
	private String byUserKey(String userId) {
		return this.keyPrefix + "by-user:" + userId;
	}

	/** Shared by every session, so it never gets a TTL. */
	private String expiryKey() {
		return this.keyPrefix + "expiry";
	}

	/**
	 * The five keys owned by one session, in the {@code KEYS[1..5]} order every script
	 * expects: hash, log, active, event-data, archived.
	 */
	private List<String> perSessionKeys(String sessionId) {
		String sessionKey = sessionKey(sessionId);
		return List.of(sessionKey, sessionKey + ":log", sessionKey + ":active", sessionKey + ":event-data",
				sessionKey + ":archived");
	}

	private long backstopDeadline(Instant expiresAt) {
		return expiresAt.plus(this.keyTtlGrace).getEpochSecond();
	}

	// -------------------------------------------------------------------------
	// Lua
	// -------------------------------------------------------------------------

	private @Nullable String saveSession(Session session, boolean insertOnly) {
		String previousUserId = this.jedisClient.hget(sessionKey(session.id()), FIELD_USER_ID);
		List<String> keys = new ArrayList<>(perSessionKeys(session.id()));
		keys.add(expiryKey());
		keys.add(byUserKey(session.userId()));
		keys.add(byUserKey(Objects.requireNonNullElse(previousUserId, session.userId())));
		Instant expiresAt = session.expiresAt();
		List<String> args = List.of(insertOnly ? "1" : "0", session.id(), session.userId(),
				session.createdAt().toString(), Objects.requireNonNull(this.codec.toJson(session.metadata())),
				(expiresAt != null) ? expiresAt.toString() : "",
				(expiresAt != null) ? Long.toString(expiresAt.toEpochMilli()) : "",
				(expiresAt != null) ? Long.toString(backstopDeadline(expiresAt)) : "");
		Object result = this.jedisClient.eval(SAVE_SESSION_SCRIPT, keys, args);
		if (result != null && !(result instanceof String)) {
			throw new IllegalStateException("Redis save-session script returned " + result.getClass().getName()
					+ "; expected the stored createdAt or nil");
		}
		return (String) result;
	}

	/**
	 * Reads the events named by {@code :active} (archived events excluded) or
	 * {@code :log} as a single point-in-time snapshot, in log order.
	 */
	private List<SessionEvent> readEventsSnapshot(String sessionId, boolean excludeArchived) {
		List<String> keys = perSessionKeys(sessionId);
		String idList = excludeArchived ? keys.get(2) : keys.get(1);
		Object result = this.jedisClient.eval(READ_EVENTS_SCRIPT, List.of(idList, keys.get(3), keys.get(4)),
				List.of());
		if (!(result instanceof List<?> entries) || entries.size() % 2 != 0) {
			throw new IllegalStateException("Redis event-snapshot script returned "
					+ ((result != null) ? result.getClass().getName() : "null") + "; expected (event JSON, archived) pairs");
		}
		List<SessionEvent> events = new ArrayList<>();
		for (int i = 0; i < entries.size(); i += 2) {
			if (!(entries.get(i) instanceof String json) || !(entries.get(i + 1) instanceof Long flag)) {
				throw new IllegalStateException(
						"Redis event-snapshot script returned an entry that is not (event JSON, archived)");
			}
			SessionEvent event = toEventOrNull(json, flag == 1L);
			if (event != null) {
				events.add(event);
			}
		}
		return events;
	}

	private int evalStatusCode(String script, List<String> keys, List<String> args) {
		Object result = this.jedisClient.eval(script, keys, args);
		if (!(result instanceof Long code)) {
			throw new IllegalStateException("Redis session script returned " + result + " of type "
					+ ((result != null) ? result.getClass().getName() : "null") + "; expected an integer status code");
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
	 * The persisted event. The message is stored in {@link SessionEventCodec}'s shape, so
	 * the encoding is shared with the other backends. The archived flag is not stored
	 * here: it is membership of the {@code :archived} SET, so archiving never rewrites
	 * event JSON.
	 */
	private record EventJson(@JsonProperty("id") String id, @JsonProperty("session_id") String sessionId,
			@JsonProperty("timestamp") String timestamp, @JsonProperty("message_type") String messageType,
			@JsonProperty("message_content") @Nullable String messageContent,
			@JsonProperty("message_data") @Nullable String messageData,
			@JsonProperty("metadata") Map<String, Object> metadata) {
	}

	private String toJson(SessionEvent event) {
		SessionEventCodec.EncodedMessage encoded = this.codec.encode(event.getMessage());
		return this.jsonMapper.writeValueAsString(new EventJson(event.getId(), event.getSessionId(),
				event.getTimestamp().toString(), encoded.type().name(), encoded.text(), encoded.data(),
				event.getMetadata()));
	}

	/**
	 * Best-effort per-event conversion: an unreadable record is logged at WARN and
	 * skipped, never propagated, so one bad record (a partial write, a manual edit, a
	 * {@link MessageType} renamed by a future Spring AI) cannot make the whole session
	 * unreadable. The catch is {@link RuntimeException} because the conversion has many
	 * throw sites: malformed JSON, {@link Instant#parse}, {@link MessageType#valueOf}, a
	 * {@code null} metadata object, and {@link SessionEvent.Builder#build()}'s own
	 * assertions.
	 * <p>
	 * {@link SessionEvent#isSynthetic()} is evaluated here on purpose: it casts the
	 * metadata value unchecked, and {@link EventFilter#apply} calls it for every event,
	 * so a {@code synthetic} value of the wrong JSON type would otherwise fail the whole
	 * read from inside the filter.
	 * <p>
	 * Only the event id and the exception message are logged, never message content.
	 */
	private @Nullable SessionEvent toEventOrNull(String json, boolean archived) {
		EventJson parsed;
		try {
			parsed = this.jsonMapper.readValue(json, EventJson.class);
		}
		catch (RuntimeException ex) {
			logger.warn("Skipping unparseable stored session event: {}", ex.getMessage());
			return null;
		}
		try {
			SessionEvent event = toEvent(parsed, archived);
			event.isSynthetic();
			return event;
		}
		catch (RuntimeException ex) {
			logger.warn("Skipping unconvertible stored session event {}: {}", parsed.id(), ex.getMessage());
			return null;
		}
	}

	private SessionEvent toEvent(EventJson parsed, boolean archived) {
		Assert.hasText(parsed.timestamp(), "timestamp must not be null or empty");
		Assert.hasText(parsed.messageType(), "message_type must not be null or empty");
		Assert.notNull(parsed.metadata(), "metadata must not be null");
		Message message = this.codec.decode(new SessionEventCodec.EncodedMessage(
				MessageType.valueOf(parsed.messageType()), parsed.messageContent(), parsed.messageData()));
		return SessionEvent.builder()
			.id(parsed.id())
			.sessionId(parsed.sessionId())
			.timestamp(Instant.parse(parsed.timestamp()))
			.message(message)
			.archived(archived)
			.metadata(parsed.metadata())
			.build();
	}

	private Session toSession(Map<String, String> fields) {
		Session.Builder builder = Session.builder()
			.id(fields.get(FIELD_ID))
			.userId(fields.get(FIELD_USER_ID))
			.createdAt(Instant.parse(fields.get(FIELD_CREATED_AT)))
			.metadata(this.codec.fromJsonMap(fields.get(FIELD_METADATA)));
		String expiresAt = fields.get(FIELD_EXPIRES_AT);
		// expiresAt(null) would override the builder's default rather than mean "none".
		if (expiresAt != null) {
			builder.expiresAt(Instant.parse(expiresAt));
		}
		return builder.build();
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for {@link RedisSessionRepository}.
	 * <p>
	 * Minimum required: {@link #jedisClient(RedisClient)}. All other fields default to
	 * sensible values.
	 */
	public static final class Builder {

		private @Nullable RedisClient jedisClient;

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
		 * Must dwarf the interval at which the application calls
		 * {@link SessionRepository#deleteExpiredSessions(Instant)}, so the backstop never
		 * pre-empts the sweep. Defaults to 30 days.
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
			Assert.notNull(this.jsonMapper, "jsonMapper must not be null");
			return new RedisSessionRepository(this.jedisClient, this.keyPrefix, this.keyTtlGrace, this.jsonMapper);
		}

	}

}
