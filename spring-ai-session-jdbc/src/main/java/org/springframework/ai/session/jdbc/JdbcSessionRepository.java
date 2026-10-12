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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.EventType;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionStatus;
import org.springframework.ai.session.StopReason;
import org.springframework.ai.session.compaction.CompactionBoundary;
import org.springframework.ai.session.support.SessionEventCodec;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

/**
 * JDBC-backed implementation of {@link SessionRepository}.
 *
 * <h2>Schema</h2>
 * <p>
 * Three tables are required:
 * <ul>
 * <li>{@code AI_SESSION} — session metadata (id, the parent and root links of a session
 * tree, status, stop reason, archive time, TTL, metadata JSON, event_version)</li>
 * <li>{@code AI_SESSION_INDEX} — the indexed metadata attributes of each session, one row
 * per attribute name (FK → AI_SESSION)</li>
 * <li>{@code AI_SESSION_EVENT} — append-only event log (FK → AI_SESSION)</li>
 * </ul>
 * SQL DDL scripts for supported databases are bundled under
 * {@code classpath:org/springframework/ai/session/jdbc/}.
 *
 * <h2>Indexed attributes</h2>
 * <p>
 * The repository has no notion of ownership. On every save it copies the metadata entries
 * whose keys are {@linkplain Builder#indexedAttributes(Set) configured as indexed} (by
 * default the principal, {@link #DEFAULT_INDEXED_ATTRIBUTES}) into
 * {@code AI_SESSION_INDEX}, and {@link #findByIndex} is one query over that table. A value
 * is indexed on its first {@value #MAX_INDEXED_VALUE_LENGTH} characters. The links of a
 * session tree are columns of {@code AI_SESSION}, each with its own index, so
 * {@link #findByParentSessionId} and {@link #findByRootSessionId} are plain lookups; a row
 * written before 0.11.0 has {@code NULL} links and reads as a root. The {@code user_id}
 * column of 0.10 is
 * no longer part of the model: it still receives the principal name, so a database that
 * declares it {@code NOT NULL} keeps working, and a row without a principal attribute in
 * its metadata is read with {@code user_id} as the principal, so sessions written before
 * 0.11.0 keep their owner. The schema script copies {@code user_id} into the index once.
 *
 * <h2>Event serialization</h2>
 * <p>
 * Each {@link SessionEvent} is stored across four columns:
 * <ul>
 * <li>{@code event_type} — the {@link org.springframework.ai.session.EventType} name</li>
 * <li>{@code message_type} — the {@link MessageType} name of an event that carries a
 * message (a message event or a tree marker), else {@code NULL}</li>
 * <li>{@code message_content} — the plain text of that message
 * ({@code message.getText()}), kept as its own column so keyword searches run in SQL</li>
 * <li>{@code payload} — JSON: the ordered message parts and metadata of a message event
 * (plus the marker's own payload for a tree marker), or the
 * {@link SessionEvent#getPayload() payload} of any other event</li>
 * </ul>
 * The columns are the persisted shape defined by {@link SessionEventCodec}. Rows written
 * before 0.11.0 hold their tool calls or tool responses in {@code message_data} and no
 * {@code payload}; they are still read, through the codec's legacy decoder.
 *
 * <h2>Optimistic concurrency (CAS)</h2>
 * <p>
 * The {@code event_version} column in {@code AI_SESSION} is incremented atomically on
 * every {@link #appendEvent} call that appends a new event (a replay leaves it unchanged)
 * and on every successful {@link #appendEvents} call. {@code appendEvents} issues a
 * conditional {@code UPDATE … WHERE event_version = ?} first; if zero rows are updated
 * nothing is inserted and {@code false} is returned.
 *
 * <h2>Active view</h2>
 * <p>
 * The log is append-only. A compaction appends a {@code COMPACTION} boundary row whose
 * payload names the view by event id, so a read with
 * {@link EventFilter#activeView()} fetches the newest boundary, then one query for the
 * rows it names plus every row from the boundary on, and lets
 * {@link EventFilter#apply(List)} order and window them. Rows written before 0.11.0 by a
 * compaction that flagged events {@code archived} are honoured until the session's first
 * boundary: without a boundary, the view excludes rows with {@code archived = true}.
 *
 * <h2>0.10 compatibility</h2>
 * <p>
 * Five paths keep a database written by 0.10 readable and are meant to be removed together
 * in 0.12: {@code user_id} is still written with the principal name and read as a legacy
 * principal ({@code legacyUserId}, {@code SessionRowMapper}); {@code message_data} is read
 * for message rows without a {@code payload} ({@code SessionEventRowMapper}); the
 * {@code archived} flag bounds the view of a session without a boundary
 * ({@code findActiveView}); {@code NULL} tree and status columns read as an idle root
 * ({@code SessionRowMapper}, {@code findByRootSessionId}). The bundled H2 and PostgreSQL
 * scripts add the 0.11 columns to an existing table; MySQL needs the statements in the
 * migration note.
 *
 * <h2>Idempotent append</h2>
 * <p>
 * {@link #appendEvent} looks the event id up under the session row lock before inserting.
 * An id already stored for the same session is a retried append and a no-op (the version
 * is not incremented); an id used by another session is rejected. Checking first, rather
 * than relying on a unique-constraint violation of the {@code AI_SESSION_EVENT.id}
 * primary key, keeps a replay from marking a caller's surrounding transaction
 * rollback-only (or, on PostgreSQL, aborting it).
 *
 * <h2>Event ordering</h2>
 * <p>
 * Events are ordered by a database-assigned monotonic {@code seq} column, which reflects
 * insertion order (the logical conversation order) rather than wall-clock
 * {@code timestamp}. The active view has its own order (pinned, summary, preserved,
 * then the rest), which the boundary defines and the filter applies in memory.
 *
 * <h2>Thread safety</h2>
 * <p>
 * All mutating operations are wrapped in a {@link TransactionTemplate}. The class is
 * thread-safe as long as the underlying {@link DataSource} is.
 *
 * @author Christian Tzolov
 * @since 0.1.0
 */
public final class JdbcSessionRepository implements SessionRepository {

	private static final Logger logger = LoggerFactory.getLogger(JdbcSessionRepository.class);

	// @formatter:off

	private static final String SELECT_SESSION_COLUMNS =
		"SELECT s.id, s.user_id, s.parent_session_id, s.root_session_id, s.status, s.stop_reason, s.archived_at,"
		+ " s.created_at, s.expires_at, s.metadata, s.event_version FROM AI_SESSION s";

	// The status compare-and-set: one turn at a time, no archive of a running session
	private static final String UPDATE_SESSION_STATUS =
		"UPDATE AI_SESSION SET status = ?, stop_reason = ?, archived_at = ? WHERE id = ? AND status = ?";

	private static final String SELECT_SESSION_BY_ID =
		SELECT_SESSION_COLUMNS + " WHERE s.id = ?";

	private static final String SELECT_SESSIONS_BY_INDEX =
		SELECT_SESSION_COLUMNS
		+ " JOIN AI_SESSION_INDEX i ON i.session_id = s.id"
		+ " WHERE i.attr_name = ? AND i.attr_value = ? ORDER BY s.created_at, s.id";

	private static final String SELECT_SESSIONS_BY_PARENT =
		SELECT_SESSION_COLUMNS + " WHERE s.parent_session_id = ? ORDER BY s.created_at, s.id";

	// A row written before 0.11.0 has a NULL root and is its own root; the second branch
	// keeps such a root in its tree on a schema that did not run the backfill
	private static final String SELECT_SESSIONS_BY_ROOT =
		SELECT_SESSION_COLUMNS + " WHERE s.root_session_id = ? OR (s.root_session_id IS NULL AND s.id = ?)"
		+ " ORDER BY s.created_at, s.id";

	private static final String DELETE_SESSION_INDEX =
		"DELETE FROM AI_SESSION_INDEX WHERE session_id = ?";

	private static final String INSERT_SESSION_INDEX =
		"INSERT INTO AI_SESSION_INDEX (session_id, attr_name, attr_value) VALUES (?, ?, ?)";

	// Checks the expiry in the DELETE itself, so a concurrently extended TTL is respected.
	// Events are removed by the ON DELETE CASCADE foreign key.
	private static final String DELETE_EXPIRED_SESSIONS =
		"DELETE FROM AI_SESSION WHERE expires_at IS NOT NULL AND expires_at < ?";

	private static final String DECREMENT_EVENT_VERSION =
		"UPDATE AI_SESSION SET event_version = event_version - 1 WHERE id = ?";

	private static final String DELETE_SESSION =
		"DELETE FROM AI_SESSION WHERE id = ?";

	private static final String INSERT_EVENT =
		"INSERT INTO AI_SESSION_EVENT"
		+ " (id, session_id, timestamp, event_type, message_type, message_content, payload,"
		+ "  synthetic, metadata)"
		+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

	private static final String INCREMENT_EVENT_VERSION =
		"UPDATE AI_SESSION SET event_version = event_version + 1 WHERE id = ?";

	private static final String CAS_INCREMENT_EVENT_VERSION =
		"UPDATE AI_SESSION SET event_version = event_version + 1"
		+ " WHERE id = ? AND event_version = ?";

	private static final String GET_EVENT_VERSION =
		"SELECT event_version FROM AI_SESSION WHERE id = ?";

	private static final String SELECT_EVENT_SESSION_ID =
		"SELECT session_id FROM AI_SESSION_EVENT WHERE id = ?";

	// The newest compaction boundary of a session, if any: it defines the active view.
	private static final String SELECT_NEWEST_BOUNDARY =
		"SELECT e.seq, e.payload FROM AI_SESSION_EVENT e"
		+ " WHERE e.session_id = ? AND e.event_type = ? ORDER BY e.seq DESC LIMIT 1";

	private static final String SELECT_EVENT_COLUMNS =
		"SELECT e.id, e.session_id, e.timestamp, e.event_type, e.message_type, e.message_content,"
		+ "       e.payload, e.message_data, e.synthetic, e.metadata";

	private static final String SELECT_EVENTS_BASE =
		SELECT_EVENT_COLUMNS
		+ " FROM AI_SESSION_EVENT e"
		+ " WHERE e.session_id = ? ";

	// Cross-session reads: one query over the given sessions so the store sorts and pages
	// the union. The IN list is built per call.
	private static final String SELECT_EVENTS_IN_SESSIONS_BASE =
		SELECT_EVENT_COLUMNS
		+ " FROM AI_SESSION_EVENT e"
		+ " WHERE e.session_id IN (";

	// @formatter:on

	private final JdbcTemplate jdbcTemplate;

	private final TransactionTemplate transactionTemplate;

	private final JdbcSessionRepositoryDialect dialect;

	private final SessionEventCodec codec;

	private final Set<String> indexedAttributes;

	/** The number of leading characters of a metadata value that are indexed. */
	public static final int MAX_INDEXED_VALUE_LENGTH = 512;

	/** The metadata keys every repository indexes: the principal. */
	public static final Set<String> DEFAULT_INDEXED_ATTRIBUTES = Set.of(SessionAttributes.PRINCIPAL_NAME);

	private JdbcSessionRepository(JdbcTemplate jdbcTemplate, JdbcSessionRepositoryDialect dialect,
			PlatformTransactionManager txManager, SessionEventCodec codec, Set<String> indexedAttributes) {
		this.jdbcTemplate = jdbcTemplate;
		this.dialect = dialect;
		this.transactionTemplate = new TransactionTemplate(txManager);
		this.codec = codec;
		this.indexedAttributes = Set.copyOf(indexedAttributes);
	}

	/** The metadata keys this repository indexes; see {@link #findByIndex}. */
	public Set<String> getIndexedAttributes() {
		return this.indexedAttributes;
	}

	// -------------------------------------------------------------------------
	// SessionRepository — session lifecycle
	// -------------------------------------------------------------------------

	@Override
	public Session save(Session session) {
		Assert.notNull(session, "session must not be null");
		this.transactionTemplate.executeWithoutResult(status -> {
			this.jdbcTemplate.update(this.dialect.getUpsertSessionSql(), session.id(), legacyUserId(session),
					session.parentSessionId(), session.rootSessionId(), session.status().name(),
					name(session.stopReason()), toUtc(session.archivedAt()), toUtc(session.createdAt()),
					toUtc(session.expiresAt()), this.codec.toJson(session.metadata()));
			this.jdbcTemplate.update(DELETE_SESSION_INDEX, session.id());
			insertIndexRows(session);
		});
		// Re-read: an update keeps the stored created_at, so the input is not what was
		// saved.
		return Objects.requireNonNull(findById(session.id()), () -> "Session vanished after save: " + session.id());
	}

	@Override
	public boolean saveIfAbsent(Session session) {
		Assert.notNull(session, "session must not be null");
		Boolean inserted = this.transactionTemplate.execute(status -> {
			try {
				if (this.jdbcTemplate.update(this.dialect.getInsertSessionIfAbsentSql(), session.id(),
						legacyUserId(session), session.parentSessionId(), session.rootSessionId(),
						session.status().name(), name(session.stopReason()), toUtc(session.archivedAt()),
						toUtc(session.createdAt()), toUtc(session.expiresAt()),
						this.codec.toJson(session.metadata())) != 1) {
					return false;
				}
			}
			catch (DuplicateKeyException ex) {
				return false;
			}
			insertIndexRows(session);
			return true;
		});
		return Boolean.TRUE.equals(inserted);
	}

	/**
	 * The value written to the 0.10 {@code user_id} column: the principal name, or an
	 * empty string. Keeps a database whose column is {@code NOT NULL} working.
	 */
	private static String legacyUserId(Session session) {
		String principal = SessionAttributes.principalName(session);
		return (principal != null) ? principal : "";
	}

	private void insertIndexRows(Session session) {
		for (String name : this.indexedAttributes) {
			Object value = session.metadata().get(name);
			if (value != null) {
				this.jdbcTemplate.update(INSERT_SESSION_INDEX, session.id(), name, indexValue(String.valueOf(value)));
			}
		}
	}

	private static String indexValue(String value) {
		return (value.length() > MAX_INDEXED_VALUE_LENGTH) ? value.substring(0, MAX_INDEXED_VALUE_LENGTH) : value;
	}

	@Override
	public @Nullable Session findById(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		List<Session> results = this.jdbcTemplate.query(SELECT_SESSION_BY_ID, new SessionRowMapper(), sessionId);
		return results.isEmpty() ? null : results.get(0);
	}

	@Override
	public List<Session> findByIndex(String name, String value) {
		Assert.hasText(name, "name must not be null or empty");
		Assert.notNull(value, "value must not be null");
		return this.jdbcTemplate.query(SELECT_SESSIONS_BY_INDEX, new SessionRowMapper(), name, indexValue(value));
	}

	@Override
	public List<Session> findByParentSessionId(String parentSessionId) {
		Assert.hasText(parentSessionId, "parentSessionId must not be null or empty");
		return this.jdbcTemplate.query(SELECT_SESSIONS_BY_PARENT, new SessionRowMapper(), parentSessionId);
	}

	@Override
	public boolean updateStatus(String sessionId, SessionStatus expected, SessionStatus status,
			@Nullable StopReason stopReason, @Nullable Instant archivedAt) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		Assert.notNull(expected, "expected must not be null");
		Assert.notNull(status, "status must not be null");
		return this.jdbcTemplate.update(UPDATE_SESSION_STATUS, status.name(), name(stopReason), toUtc(archivedAt),
				sessionId, expected.name()) == 1;
	}

	@Nullable private static String name(@Nullable Enum<?> value) {
		return value != null ? value.name() : null;
	}

	@Override
	public List<Session> findByRootSessionId(String rootSessionId) {
		Assert.hasText(rootSessionId, "rootSessionId must not be null or empty");
		return this.jdbcTemplate.query(SELECT_SESSIONS_BY_ROOT, new SessionRowMapper(), rootSessionId, rootSessionId);
	}

	@Override
	public int deleteExpiredSessions(Instant before) {
		Assert.notNull(before, "before must not be null");
		return this.jdbcTemplate.update(DELETE_EXPIRED_SESSIONS, toUtc(before));
	}

	@Override
	public void delete(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		this.jdbcTemplate.update(DELETE_SESSION, sessionId);
	}

	// -------------------------------------------------------------------------
	// SessionRepository — event log
	// -------------------------------------------------------------------------

	@Override
	public void appendEvent(SessionEvent event) {
		Assert.notNull(event, "event must not be null");
		String sessionId = event.getSessionId();
		try {
			this.transactionTemplate.execute(status -> {
				// Bump the version BEFORE inserting: the UPDATE locks the session row, so the
				// event's seq is only assigned once any in-flight appendEvents on this
				// session has committed. Inserting first would let a concurrent compaction
				// miss the uncommitted row: its boundary would land after this event in
				// seq order without naming it, so the event would wrongly enter the view
				// as "appended since". Zero updated rows also means the session does not
				// exist.
				int updated = this.jdbcTemplate.update(INCREMENT_EVENT_VERSION, sessionId);
				if (updated == 0) {
					throw new IllegalArgumentException("Session not found: " + sessionId);
				}
				// Detect a replay before inserting, under the session row lock, instead of
				// relying on a failed INSERT: a duplicate-key error would mark a caller's
				// surrounding transaction rollback-only, and on PostgreSQL abort it.
				List<String> owner = this.jdbcTemplate.queryForList(SELECT_EVENT_SESSION_ID, String.class,
						event.getId());
				if (!owner.isEmpty()) {
					if (!sessionId.equals(owner.get(0))) {
						throw new IllegalStateException("Event id '" + event.getId()
								+ "' is already used by another session; event ids must be unique across sessions");
					}
					// Idempotent replay: the event was already committed, e.g. a retried
					// append after a crash. Undo the version bump so the version counts
					// only appended events.
					this.jdbcTemplate.update(DECREMENT_EVENT_VERSION, sessionId);
					logger.debug("appendEvent: event {} already exists for session {}; idempotent replay",
							event.getId(), sessionId);
					return null;
				}
				insertEvent(event);
				return null;
			});
		}
		catch (DuplicateKeyException ex) {
			// Only reached when another session inserts the same id concurrently: a
			// same-session replay is caught by the lookup above, under this session's
			// lock. Event ids are a table-wide primary key, so the event was not stored
			// and must not be dropped silently. If the competing insert is still there,
			// report the collision; if it was rolled back, propagate the failure so the
			// caller can retry.
			List<String> owner = this.jdbcTemplate.queryForList(SELECT_EVENT_SESSION_ID, String.class,
					event.getId());
			if (!owner.isEmpty() && !sessionId.equals(owner.get(0))) {
				throw new IllegalStateException("Event id '" + event.getId() + "' is already used by another session; "
						+ "event ids must be unique across sessions", ex);
			}
			throw ex;
		}
	}

	@Override
	public boolean appendEvents(String sessionId, List<SessionEvent> events, long expectedVersion) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		Assert.notEmpty(events, "events must not be empty");
		Set<String> batchIds = new HashSet<>();
		for (SessionEvent event : events) {
			Assert.isTrue(sessionId.equals(event.getSessionId()),
					() -> "Event '" + event.getId() + "' belongs to session '" + event.getSessionId() + "'");
			Assert.isTrue(batchIds.add(event.getId()),
					() -> "Event id '" + event.getId() + "' appears twice in the batch");
		}
		Boolean success = this.transactionTemplate.execute(status -> {
			// Atomically claim the version slot. If another writer already changed it,
			// 0 rows are updated and we bail out without touching the event log; a session
			// that does not exist at all is reported as such.
			int updated = this.jdbcTemplate.update(CAS_INCREMENT_EVENT_VERSION, sessionId, expectedVersion);
			if (updated == 0) {
				if (this.jdbcTemplate.queryForList(GET_EVENT_VERSION, Long.class, sessionId).isEmpty()) {
					throw new IllegalArgumentException("Session not found: " + sessionId);
				}
				return false;
			}
			String placeholders = placeholders(events.size());
			List<String> taken = this.jdbcTemplate.queryForList(
					"SELECT id FROM AI_SESSION_EVENT WHERE id IN (" + placeholders + ")", String.class,
					events.stream().map(SessionEvent::getId).toArray());
			if (!taken.isEmpty()) {
				throw new IllegalArgumentException("Event id '" + taken.get(0) + "' is already in the log");
			}
			batchInsertEvents(events);
			return true;
		});
		return Boolean.TRUE.equals(success);
	}

	@Override
	public long getEventVersion(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		List<Long> result = this.jdbcTemplate.queryForList(GET_EVENT_VERSION, Long.class, sessionId);
		return result.isEmpty() ? 0L : (result.get(0) != null ? result.get(0) : 0L);
	}

	@Override
	public List<SessionEvent> findEvents(String sessionId, EventFilter filter) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		Assert.notNull(filter, "filter must not be null");
		if (filter.activeView()) {
			return findActiveView(sessionId, filter);
		}
		return findFiltered(SELECT_EVENTS_BASE, List.of(sessionId), filter, "e.seq", sessionId);
	}

	@Override
	public List<SessionEvent> findEvents(Collection<String> sessionIds, EventFilter filter) {
		Assert.notNull(sessionIds, "sessionIds must not be null");
		Assert.notNull(filter, "filter must not be null");
		List<String> ids = sessionIds.stream().distinct().toList();
		if (ids.isEmpty()) {
			return List.of();
		}
		if (filter.activeView()) {
			// An active view is per session; read each session's view and window the union.
			return SessionRepository.super.findEvents(ids, filter);
		}
		// Across sessions the log order is the timestamp; seq only breaks ties. Turns do
		// not exist across sessions, so the window is plain.
		String base = SELECT_EVENTS_IN_SESSIONS_BASE + placeholders(ids.size()) + ") ";
		return findFiltered(base, ids, filter, "e.timestamp, e.seq", null);
	}

	/**
	 * Reads the active view of a session: the rows the newest boundary names (pinned,
	 * summary, preserved) plus every row from the boundary on, in log order, which
	 * {@link EventFilter#apply(List)} then projects into view order, filters and windows.
	 * The view is bounded by compaction, so it is read whole. A session without a
	 * boundary is read whole too, minus rows flagged {@code archived} by a compaction
	 * written before 0.11.0.
	 */
	private List<SessionEvent> findActiveView(String sessionId, EventFilter filter) {
		List<Map<String, Object>> boundaries = this.jdbcTemplate.queryForList(SELECT_NEWEST_BOUNDARY, sessionId,
				EventType.COMPACTION.name());
		StringBuilder sql = new StringBuilder(SELECT_EVENTS_BASE);
		List<Object> params = new ArrayList<>();
		params.add(sessionId);
		if (boundaries.isEmpty()) {
			sql.append("AND e.archived = ? ");
			params.add(false);
		}
		else {
			Map<String, Object> boundary = boundaries.get(0);
			long boundarySeq = ((Number) Objects.requireNonNull(boundary.get("seq"))).longValue();
			Map<String, Object> payload = this.codec.fromJsonMap((String) boundary.get("payload"));
			List<String> named = new ArrayList<>();
			for (String key : List.of(CompactionBoundary.PAYLOAD_PINNED, CompactionBoundary.PAYLOAD_SUMMARY,
					CompactionBoundary.PAYLOAD_PRESERVED)) {
				if (payload.get(key) instanceof List<?> ids) {
					ids.forEach(id -> named.add(String.valueOf(id)));
				}
			}
			sql.append("AND (e.seq >= ? ");
			params.add(boundarySeq);
			if (!named.isEmpty()) {
				sql.append("OR e.id IN (").append(placeholders(named.size())).append(") ");
				params.addAll(named);
			}
			sql.append(") ");
		}
		sql.append("ORDER BY e.seq");
		List<SessionEvent> rows = this.jdbcTemplate.query(sql.toString(), new SessionEventRowMapper(),
				params.toArray());
		return filter.apply(rows);
	}

	/**
	 * Runs the filter as one query: every criterion the database can evaluate is pushed
	 * down as a {@code WHERE} clause, and the window ({@code lastN} or a page) as
	 * {@code LIMIT} / {@code OFFSET}. A {@link EventFilter#pattern()} is the exception: a
	 * {@link java.util.regex.Pattern} cannot be safely translated to portable SQL (H2,
	 * MySQL and PostgreSQL each have their own regex dialect, none a strict superset of
	 * Java's), so with a pattern the window is deferred and the SQL-filtered result is
	 * finished in memory with {@link EventFilter#apply(List)} (per session) or
	 * {@link EventFilter#applyWindow(List)} (across sessions).
	 * @param base the {@code SELECT … WHERE <scope> = ? } prefix
	 * @param scope the values of the scope column: one session id, or the ids of a read
	 * across sessions
	 * @param order the {@code ORDER BY} columns, ascending; reversed for {@code lastN}
	 * @param turnAwareSessionId the session when the query reads one session's log, so
	 * that a {@code lastN} window keeps turns whole ({@link #extendToTurnStart} prepends
	 * the rest of the turn with one more query); {@code null} for a read across sessions,
	 * whose window is plain
	 */
	private List<SessionEvent> findFiltered(String base, List<String> scope, EventFilter filter, String order,
			@Nullable String turnAwareSessionId) {
		boolean perSession = turnAwareSessionId != null;
		boolean inMemoryWindow = filter.pattern() != null;
		StringBuilder sql = new StringBuilder(base);
		List<Object> params = new ArrayList<>(scope);
		appendCriteria(sql, params, filter, "e");

		if (inMemoryWindow) {
			sql.append("ORDER BY ").append(order).append(" ");
		}
		else if (filter.lastN() != null) {
			sql.append("ORDER BY ").append(descending(order)).append(" LIMIT ? ");
			params.add(filter.lastN());
		}
		else if (filter.pageSize() != null) {
			int page = filter.page() != null ? filter.page() : 0;
			sql.append("ORDER BY ").append(order).append(" LIMIT ? OFFSET ? ");
			params.add(filter.pageSize());
			params.add((long) page * filter.pageSize());
		}
		else {
			sql.append("ORDER BY ").append(order).append(" ");
		}

		List<SessionEvent> result = this.jdbcTemplate.query(sql.toString(), new SessionEventRowMapper(),
				params.toArray());
		if (inMemoryWindow) {
			return perSession ? filter.apply(result)
					: filter.applyWindow(result.stream().filter(filter::matches).toList());
		}
		if (filter.lastN() != null) {
			result = new ArrayList<>(result);
			Collections.reverse(result);
			if (perSession) {
				result = extendToTurnStart(turnAwareSessionId, filter, result);
			}
		}
		return Collections.unmodifiableList(result);
	}

	/**
	 * Prepends the rest of the turn a {@code lastN} window landed in, so that the window
	 * starts at a {@linkplain SessionEvent#isTurnStart() turn start}. Runs one extra query
	 * only when it can matter: the window is full (fewer than N rows means the whole
	 * matched log is already in hand), its oldest event is not a turn start, the filter
	 * does not search text (a search result is not a prompt) and it does not exclude
	 * {@code USER} events (no turn start could match). The query is bounded by one turn
	 * of the filtered log and uses the {@code (session_id, seq)} index; a subquery that
	 * finds no preceding turn start yields no rows, which is the plain-window fallback of
	 * {@link EventFilter#applyTurnAwareWindow(List)}.
	 * <p>
	 * The two reads are not one snapshot: a compaction committing in between can only
	 * shorten the extension of that one read, which the next turn corrects.
	 */
	private List<SessionEvent> extendToTurnStart(String sessionId, EventFilter filter, List<SessionEvent> window) {
		Integer lastN = filter.lastN();
		if (lastN == null || window.size() < lastN || window.get(0).isTurnStart() || filter.hasTextCriteria()
				|| (filter.messageTypes() != null && !filter.messageTypes().contains(MessageType.USER))) {
			return window;
		}
		StringBuilder sql = new StringBuilder(SELECT_EVENTS_BASE);
		List<Object> params = new ArrayList<>();
		params.add(sessionId);
		appendCriteria(sql, params, filter, "e");
		sql.append("AND e.seq < (SELECT w.seq FROM AI_SESSION_EVENT w WHERE w.id = ? AND w.session_id = ?) ");
		params.add(window.get(0).getId());
		params.add(sessionId);
		sql.append("AND e.seq >= (SELECT MAX(u.seq) FROM AI_SESSION_EVENT u WHERE u.session_id = ? ");
		params.add(sessionId);
		sql.append("AND u.event_type = ? AND u.message_type = ? ");
		params.add(EventType.MESSAGE.name());
		params.add(MessageType.USER.name());
		sql.append("AND u.seq < (SELECT w.seq FROM AI_SESSION_EVENT w WHERE w.id = ? AND w.session_id = ?) ");
		params.add(window.get(0).getId());
		params.add(sessionId);
		appendCriteria(sql, params, filter, "u");
		sql.append(") ORDER BY e.seq");
		List<SessionEvent> head = this.jdbcTemplate.query(sql.toString(), new SessionEventRowMapper(),
				params.toArray());
		if (head.isEmpty()) {
			return window;
		}
		List<SessionEvent> extended = new ArrayList<>(head.size() + window.size());
		extended.addAll(head);
		extended.addAll(window);
		return extended;
	}

	private void appendCriteria(StringBuilder sql, List<Object> params, EventFilter filter, String alias) {
		// The dialect's keyword fragments are written against alias "e"
		Assert.state("e".equals(alias) || !filter.hasTextCriteria(),
				"text criteria can only be appended for alias e");
		String col = alias + ".";
		if (filter.from() != null) {
			sql.append("AND ").append(col).append("timestamp >= ? ");
			params.add(toUtc(filter.from()));
		}
		if (filter.to() != null) {
			sql.append("AND ").append(col).append("timestamp <= ? ");
			params.add(toUtc(filter.to()));
		}
		if (filter.eventTypes() != null && !filter.eventTypes().isEmpty()) {
			sql.append("AND ").append(col).append("event_type IN (").append(placeholders(filter.eventTypes().size()))
				.append(") ");
			filter.eventTypes().forEach(et -> params.add(et.name()));
		}
		if (filter.messageTypes() != null && !filter.messageTypes().isEmpty()) {
			// A NULL message_type (an event that is not a message) never matches.
			sql.append("AND ").append(col).append("message_type IN (").append(placeholders(filter.messageTypes().size()))
				.append(") ");
			filter.messageTypes().forEach(mt -> params.add(mt.name()));
		}
		if (filter.excludeSynthetic()) {
			sql.append("AND ").append(col).append("synthetic = ? ");
			params.add(false);
		}
		if (filter.keyword() != null) {
			sql.append(this.dialect.getKeywordFilterFragment()).append(" ");
			params.add(containsPattern(filter.keyword()));
		}
		if (filter.keywords() != null) {
			sql.append("AND (");
			String joiner = filter.matchMode() == EventFilter.MatchMode.ALL ? " AND " : " OR ";
			for (int i = 0; i < filter.keywords().size(); i++) {
				if (i > 0) {
					sql.append(joiner);
				}
				sql.append(this.dialect.getKeywordPredicateFragment());
				params.add(containsPattern(filter.keywords().get(i)));
			}
			sql.append(") ");
		}
	}

	/** Turns {@code "a, b"} into {@code "a DESC, b DESC"}. */
	private static String descending(String order) {
		return Arrays.stream(order.split(",")).map(column -> column.trim() + " DESC").collect(Collectors.joining(", "));
	}

	// -------------------------------------------------------------------------
	// Internal helpers
	// -------------------------------------------------------------------------

	/**
	 * Builds a {@code LIKE} pattern matching {@code term} as a literal substring: the
	 * dialect fragments declare {@code ESCAPE '!'}, so {@code !}, {@code %} and {@code _}
	 * are escaped to keep them from acting as wildcards.
	 */
	static String containsPattern(String term) {
		String escaped = term.replace("!", "!!").replace("%", "!%").replace("_", "!_");
		return "%" + escaped + "%";
	}

	private void insertEvent(SessionEvent event) {
		SessionEventCodec.EncodedEvent encoded = this.codec.encode(event);
		this.jdbcTemplate.update(INSERT_EVENT, event.getId(), event.getSessionId(), toUtc(event.getTimestamp()),
				encoded.type().name(), messageTypeName(encoded.messageType()), encoded.text(), encoded.payload(),
				event.isSynthetic(), this.codec.toJson(event.getMetadata()));
	}

	@Nullable private static String messageTypeName(@Nullable MessageType messageType) {
		return name(messageType);
	}

	/**
	 * Inserts the supplied events using a JDBC batch operation.
	 */
	private void batchInsertEvents(List<SessionEvent> events) {
		if (events.isEmpty()) {
			return;
		}
		this.jdbcTemplate.batchUpdate(INSERT_EVENT, events, events.size(), (ps, event) -> {
			SessionEventCodec.EncodedEvent encoded = this.codec.encode(event);
			ps.setString(1, event.getId());
			ps.setString(2, event.getSessionId());
			ps.setObject(3, toUtc(event.getTimestamp()));
			ps.setString(4, encoded.type().name());
			ps.setString(5, messageTypeName(encoded.messageType()));
			ps.setString(6, encoded.text());
			ps.setString(7, encoded.payload());
			ps.setBoolean(8, event.isSynthetic());
			ps.setString(9, this.codec.toJson(event.getMetadata()));
		});
	}

	/** {@code ?,?,…} for an {@code IN} list of {@code n} values. */
	private static String placeholders(int n) {
		return String.join(",", Collections.nCopies(n, "?"));
	}

	/**
	 * Timestamp columns carry no time zone ({@code TIMESTAMP} / {@code DATETIME}), so
	 * instants are always stored and read as UTC wall-clock time. Binding through
	 * {@link java.sql.Timestamp} would instead use the JVM default time zone, shifting
	 * values between application instances in different zones and across DST changes.
	 */
	@Nullable private static LocalDateTime toUtc(@Nullable Instant instant) {
		return instant != null ? LocalDateTime.ofInstant(instant, ZoneOffset.UTC) : null;
	}

	@Nullable private static Instant fromUtc(ResultSet rs, String column) throws SQLException {
		LocalDateTime value = rs.getObject(column, LocalDateTime.class);
		return value != null ? value.toInstant(ZoneOffset.UTC) : null;
	}

	/** Returns a new {@link Builder}. */
	public static Builder builder() {
		return new Builder();
	}

	// -------------------------------------------------------------------------
	// Row mappers
	// -------------------------------------------------------------------------

	private class SessionRowMapper implements RowMapper<Session> {

		@Override
		public Session mapRow(ResultSet rs, int rowNum) throws SQLException {
			Instant expiresAt = fromUtc(rs, "expires_at");
			Map<String, Object> metadata = new HashMap<>(
					JdbcSessionRepository.this.codec.fromJsonMap(rs.getString("metadata")));
			// A row written before 0.11.0 has its owner in user_id only
			String legacyUserId = rs.getString("user_id");
			if (legacyUserId != null && !legacyUserId.isEmpty()) {
				metadata.putIfAbsent(SessionAttributes.PRINCIPAL_NAME, legacyUserId);
			}
			// A row written before 0.11.0 has no tree or status columns: NULLs make it an
			// idle root
			String status = rs.getString("status");
			String stopReason = rs.getString("stop_reason");
			Session.Builder builder = Session.builder()
				.id(rs.getString("id"))
				.createdAt(Objects.requireNonNull(fromUtc(rs, "created_at")))
				.parentSessionId(rs.getString("parent_session_id"))
				.rootSessionId(rs.getString("root_session_id"))
				.status(status != null ? SessionStatus.valueOf(status) : SessionStatus.IDLE)
				.stopReason(stopReason != null ? StopReason.valueOf(stopReason) : null)
				.archivedAt(fromUtc(rs, "archived_at"))
				.metadata(metadata);
			if (expiresAt != null) {
				builder.expiresAt(expiresAt);
			}
			return builder.build();
		}

	}

	private class SessionEventRowMapper implements RowMapper<SessionEvent> {

		@Override
		public SessionEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
			SessionEventCodec codec = JdbcSessionRepository.this.codec;
			String eventTypeName = rs.getString("event_type");
			EventType eventType = (eventTypeName != null) ? EventType.valueOf(eventTypeName) : EventType.MESSAGE;
			String messageTypeName = rs.getString("message_type");
			MessageType messageType = (messageTypeName != null) ? MessageType.valueOf(messageTypeName) : null;
			String payload = rs.getString("payload");
			String legacyData = rs.getString("message_data");

			SessionEvent.Builder builder;
			if (eventType == EventType.MESSAGE && messageType != null && payload == null) {
				// Written before 0.11.0: the message's structured data is in message_data.
				@SuppressWarnings("deprecation")
				Message message = codec.decode(new SessionEventCodec.EncodedMessage(messageType,
						rs.getString("message_content"), legacyData));
				builder = SessionEvent.builder().message(message);
			}
			else {
				builder = codec.decode(new SessionEventCodec.EncodedEvent(eventType, messageType,
						rs.getString("message_content"), payload));
			}

			// Merge the dedicated synthetic column back into the metadata map so that
			// SessionEvent.isSynthetic() returns the correct value.
			Map<String, Object> metadata = new HashMap<>(codec.fromJsonMap(rs.getString("metadata")));
			if (rs.getBoolean("synthetic")) {
				metadata.put(SessionEvent.METADATA_SYNTHETIC, true);
			}

			return builder.id(rs.getString("id"))
				.sessionId(rs.getString("session_id"))
				.timestamp(Objects.requireNonNull(fromUtc(rs, "timestamp")))
				.metadata(metadata)
				.build();
		}

	}

	// -------------------------------------------------------------------------
	// Builder
	// -------------------------------------------------------------------------

	/**
	 * Builder for {@link JdbcSessionRepository}.
	 *
	 * <p>
	 * Minimum required: either {@link #dataSource(DataSource)} or
	 * {@link #jdbcTemplate(JdbcTemplate)}. All other fields default to sensible values.
	 */
	public static final class Builder {

		@Nullable private DataSource dataSource;

		@Nullable private JdbcTemplate jdbcTemplate;

		@Nullable private JdbcSessionRepositoryDialect dialect;

		@Nullable private PlatformTransactionManager transactionManager;

		private JsonMapper jsonMapper = JsonMapper.builder().build();

		private Set<String> indexedAttributes = DEFAULT_INDEXED_ATTRIBUTES;

		private Builder() {
		}

		/**
		 * The metadata keys to index in {@code AI_SESSION_INDEX}, so that
		 * {@link #findByIndex} can answer for them. The
		 * {@link #DEFAULT_INDEXED_ATTRIBUTES} (the principal) are always indexed; add a
		 * tenant key or the like.
		 */
		public Builder indexedAttributes(Set<String> indexedAttributes) {
			Assert.notNull(indexedAttributes, "indexedAttributes must not be null");
			Set<String> keys = new HashSet<>(indexedAttributes);
			keys.addAll(DEFAULT_INDEXED_ATTRIBUTES);
			this.indexedAttributes = keys;
			return this;
		}

		/** Sets the {@link DataSource}. */
		public Builder dataSource(DataSource dataSource) {
			this.dataSource = dataSource;
			return this;
		}

		/** Sets a pre-configured {@link JdbcTemplate}. */
		public Builder jdbcTemplate(JdbcTemplate jdbcTemplate) {
			this.jdbcTemplate = jdbcTemplate;
			return this;
		}

		/**
		 * Overrides the auto-detected SQL dialect. When omitted,
		 * {@link JdbcSessionRepositoryDialect#from(DataSource)} is used.
		 */
		public Builder dialect(JdbcSessionRepositoryDialect dialect) {
			this.dialect = dialect;
			return this;
		}

		/** Overrides the transaction manager. */
		public Builder transactionManager(PlatformTransactionManager transactionManager) {
			this.transactionManager = transactionManager;
			return this;
		}

		/**
		 * Overrides the {@link JsonMapper} used for metadata and payload serialization.
		 * Defaults to {@code JsonMapper.builder().build()}.
		 */
		public Builder jsonMapper(JsonMapper jsonMapper) {
			this.jsonMapper = jsonMapper;
			return this;
		}

		/** Builds the repository. */
		public JdbcSessionRepository build() {
			DataSource ds = resolveDataSource();
			JdbcTemplate jt = this.jdbcTemplate != null ? this.jdbcTemplate : new JdbcTemplate(ds);
			JdbcSessionRepositoryDialect d = this.dialect != null ? this.dialect
					: JdbcSessionRepositoryDialect.from(ds);
			PlatformTransactionManager txm = this.transactionManager != null ? this.transactionManager
					: new DataSourceTransactionManager(ds);
			return new JdbcSessionRepository(jt, d, txm, new SessionEventCodec(this.jsonMapper),
					this.indexedAttributes);
		}

		private DataSource resolveDataSource() {
			if (this.dataSource != null) {
				return this.dataSource;
			}
			if (this.jdbcTemplate != null && this.jdbcTemplate.getDataSource() != null) {
				return this.jdbcTemplate.getDataSource();
			}
			throw new IllegalArgumentException("A DataSource is required — set via dataSource() or jdbcTemplate()");
		}

	}

}
