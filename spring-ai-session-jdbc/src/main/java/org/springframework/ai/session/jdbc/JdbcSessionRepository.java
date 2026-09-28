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
import java.util.Collections;
import java.util.HashMap;
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
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.compaction.CompactionPlan;
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
 * Two tables are required:
 * <ul>
 * <li>{@code AI_SESSION} — session metadata (id, user_id, TTL, metadata JSON,
 * event_version)</li>
 * <li>{@code AI_SESSION_EVENT} — append-only event log (FK → AI_SESSION)</li>
 * </ul>
 * SQL DDL scripts for supported databases are bundled under
 * {@code classpath:org/springframework/ai/session/jdbc/}.
 *
 * <h2>Message serialization</h2>
 * <p>
 * Each {@link SessionEvent}'s wrapped {@link Message} is stored across three columns:
 * <ul>
 * <li>{@code message_type} — the {@link MessageType} name</li>
 * <li>{@code message_content} — plain text ({@code message.getText()})</li>
 * <li>{@code message_data} — JSON blob for type-specific structured data
 * ({@link AssistantMessage.ToolCall} list or {@link ToolResponseMessage.ToolResponse}
 * list)</li>
 * </ul>
 *
 * <h2>Optimistic concurrency (CAS)</h2>
 * <p>
 * The {@code event_version} column in {@code AI_SESSION} is incremented atomically on
 * every {@link #appendEvent} call that appends a new event (a replay leaves it unchanged)
 * and on every {@link #compactEvents} call. {@code compactEvents} guards
 * compaction by issuing a conditional {@code UPDATE … WHERE event_version = ?} first; if
 * zero rows are updated the swap is abandoned and {@code false} is returned.
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
 * {@code timestamp}. This keeps a synthetic compaction summary — whose timestamp is the
 * compaction time — correctly positioned ahead of the older active-window events it
 * precedes. Compaction never reorders existing events: archived events are flagged in
 * place, and only the part of the log after an inserted summary is re-inserted.
 *
 * <h2>Thread safety</h2>
 * <p>
 * All mutating operations are wrapped in a {@link TransactionTemplate}. The class is
 * thread-safe as long as the underlying {@link DataSource} is.
 *
 * @author Christian Tzolov
 * @since 2.0.0
 */
public final class JdbcSessionRepository implements SessionRepository {

	private static final Logger logger = LoggerFactory.getLogger(JdbcSessionRepository.class);

	// @formatter:off

	private static final String SELECT_SESSION_BY_ID =
		"SELECT id, user_id, created_at, expires_at, metadata, event_version"
		+ " FROM AI_SESSION WHERE id = ?";

	private static final String SELECT_SESSIONS_BY_USER =
		"SELECT id, user_id, created_at, expires_at, metadata, event_version"
		+ " FROM AI_SESSION WHERE user_id = ? ORDER BY created_at, id";

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
		+ " (id, session_id, timestamp, message_type, message_content, message_data,"
		+ "  synthetic, archived, metadata)"
		+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

	private static final String INCREMENT_EVENT_VERSION =
		"UPDATE AI_SESSION SET event_version = event_version + 1 WHERE id = ?";

	private static final String CAS_INCREMENT_EVENT_VERSION =
		"UPDATE AI_SESSION SET event_version = event_version + 1"
		+ " WHERE id = ? AND event_version = ?";

	private static final String GET_EVENT_VERSION =
		"SELECT event_version FROM AI_SESSION WHERE id = ?";

	private static final String COUNT_SESSION =
		"SELECT COUNT(*) FROM AI_SESSION WHERE id = ?";

	// Marks events as archived without rewriting existing rows.
	private static final String ARCHIVE_EVENT_BY_ID =
		"UPDATE AI_SESSION_EVENT SET archived = true WHERE id = ? AND session_id = ?";

	private static final String SELECT_EVENT_SESSION_ID =
		"SELECT session_id FROM AI_SESSION_EVENT WHERE id = ?";

	private static final String SELECT_EVENT_SEQ =
		"SELECT seq FROM AI_SESSION_EVENT WHERE id = ? AND session_id = ?";

	// Removes the tail of the log that compaction re-inserts with new events in between.
	private static final String DELETE_EVENTS_FROM_SEQ =
		"DELETE FROM AI_SESSION_EVENT WHERE session_id = ? AND seq >= ?";

	private static final String SELECT_EVENT_COLUMNS =
		"SELECT e.id, e.session_id, e.timestamp, e.message_type, e.message_content,"
		+ "       e.message_data, e.synthetic, e.archived, e.metadata";

	private static final String SELECT_EVENTS_BASE =
		SELECT_EVENT_COLUMNS
		+ " FROM AI_SESSION_EVENT e"
		+ " WHERE e.session_id = ? ";

	// Cross-session reads: one query over every session of a user, joined on the session
	// row so the store sorts and pages the union.
	private static final String SELECT_EVENTS_BY_USER_BASE =
		SELECT_EVENT_COLUMNS
		+ " FROM AI_SESSION_EVENT e"
		+ " JOIN AI_SESSION s ON s.id = e.session_id"
		+ " WHERE s.user_id = ? ";

	// @formatter:on

	private final JdbcTemplate jdbcTemplate;

	private final TransactionTemplate transactionTemplate;

	private final JdbcSessionRepositoryDialect dialect;

	private final SessionEventCodec codec;

	private JdbcSessionRepository(JdbcTemplate jdbcTemplate, JdbcSessionRepositoryDialect dialect,
			PlatformTransactionManager txManager, SessionEventCodec codec) {
		this.jdbcTemplate = jdbcTemplate;
		this.dialect = dialect;
		this.transactionTemplate = new TransactionTemplate(txManager);
		this.codec = codec;
	}

	// -------------------------------------------------------------------------
	// SessionRepository — session lifecycle
	// -------------------------------------------------------------------------

	@Override
	public Session save(Session session) {
		Assert.notNull(session, "session must not be null");
		this.jdbcTemplate.update(this.dialect.getUpsertSessionSql(), session.id(), session.userId(),
				toUtc(session.createdAt()), toUtc(session.expiresAt()), this.codec.toJson(session.metadata()));
		// Re-read: an update keeps the stored created_at, so the input is not what was
		// saved.
		return Objects.requireNonNull(findById(session.id()), () -> "Session vanished after save: " + session.id());
	}

	@Override
	public boolean saveIfAbsent(Session session) {
		Assert.notNull(session, "session must not be null");
		try {
			return this.jdbcTemplate.update(this.dialect.getInsertSessionIfAbsentSql(), session.id(),
					session.userId(), toUtc(session.createdAt()), toUtc(session.expiresAt()),
					this.codec.toJson(session.metadata())) == 1;
		}
		catch (DuplicateKeyException ex) {
			return false;
		}
	}

	@Override
	public @Nullable Session findById(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		List<Session> results = this.jdbcTemplate.query(SELECT_SESSION_BY_ID, new SessionRowMapper(), sessionId);
		return results.isEmpty() ? null : results.get(0);
	}

	@Override
	public List<Session> findByUserId(String userId) {
		Assert.hasText(userId, "userId must not be null or empty");
		return this.jdbcTemplate.query(SELECT_SESSIONS_BY_USER, new SessionRowMapper(), userId);
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
				// event's seq is only assigned once any in-flight compactEvents on this
				// session has committed. Inserting first would let a concurrent compaction
				// miss the uncommitted row and re-insert the tail of the log with higher
				// seq values, ordering this (newer) event before them without the CAS
				// noticing. Zero updated rows also means the session does not exist.
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
	public boolean applyCompaction(String sessionId, CompactionPlan plan, long expectedVersion) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		Assert.notNull(plan, "plan must not be null");
		requireSessionExists(sessionId);
		Boolean success = this.transactionTemplate.execute(status -> {
			// Atomically claim the version slot. If another writer already changed it,
			// 0 rows are updated and we bail out without touching the event log.
			int updated = this.jdbcTemplate.update(CAS_INCREMENT_EVENT_VERSION, sessionId, expectedVersion);
			if (updated == 0) {
				return false;
			}
			archiveInPlace(sessionId, plan.archiveIds());
			insertEvents(sessionId, plan.inserts());
			return true;
		});
		return Boolean.TRUE.equals(success);
	}

	/**
	 * Flags the events archived in place: compaction never moves an event, so the logical
	 * order defined by the {@code seq} column is preserved. Every id must be in this
	 * session's log, otherwise the whole transaction is rolled back.
	 */
	private void archiveInPlace(String sessionId, Set<String> archiveIds) {
		if (archiveIds.isEmpty()) {
			return;
		}
		List<String> ids = List.copyOf(archiveIds);
		int[][] counts = this.jdbcTemplate.batchUpdate(ARCHIVE_EVENT_BY_ID, ids, ids.size(), (ps, id) -> {
			ps.setString(1, id);
			ps.setString(2, sessionId);
		});
		// Negative counts (Statement.SUCCESS_NO_INFO) are driver-specific and treated as
		// success.
		for (int[] batch : counts) {
			for (int count : batch) {
				if (count == 0) {
					throw new IllegalArgumentException(
							"archiveIds contains an event that is not in the log of session " + sessionId);
				}
			}
		}
	}

	/**
	 * Inserts each group immediately before its anchor event. Because {@code seq} is
	 * assigned on insert, the log is re-inserted from the smallest anchor onward, so only
	 * that tail (typically the kept window) is rewritten; groups without an anchor are
	 * appended. When nothing is inserted, as with every strategy except summarization, no
	 * row is re-inserted at all.
	 */
	private void insertEvents(String sessionId, List<CompactionPlan.Insert> inserts) {
		if (inserts.isEmpty()) {
			return;
		}
		Map<String, List<SessionEvent>> insertBefore = new HashMap<>();
		List<SessionEvent> append = new ArrayList<>();
		Map<String, Long> anchorSeqs = new HashMap<>();
		for (CompactionPlan.Insert insert : inserts) {
			String anchor = insert.beforeEventId();
			if (anchor == null) {
				append.addAll(insert.events());
				continue;
			}
			List<Long> seq = this.jdbcTemplate.queryForList(SELECT_EVENT_SEQ, Long.class, anchor, sessionId);
			if (seq.isEmpty()) {
				throw new IllegalArgumentException(
						"inserts refers to an anchor event that is not in the log of session " + sessionId);
			}
			anchorSeqs.put(anchor, seq.get(0));
			insertBefore.computeIfAbsent(anchor, id -> new ArrayList<>()).addAll(insert.events());
		}

		List<SessionEvent> toInsert = new ArrayList<>();
		if (!anchorSeqs.isEmpty()) {
			long fromSeq = anchorSeqs.values().stream().mapToLong(Long::longValue).min().orElseThrow();
			List<SessionEvent> tail = this.jdbcTemplate.query(SELECT_EVENTS_BASE + "AND e.seq >= ? ORDER BY e.seq ASC",
					new SessionEventRowMapper(), sessionId, fromSeq);
			this.jdbcTemplate.update(DELETE_EVENTS_FROM_SEQ, sessionId, fromSeq);
			for (SessionEvent event : tail) {
				toInsert.addAll(insertBefore.getOrDefault(event.getId(), List.of()));
				toInsert.add(event);
			}
		}
		toInsert.addAll(append);
		batchInsertEvents(toInsert);
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
		return query(SELECT_EVENTS_BASE, sessionId, filter, "e.seq");
	}

	@Override
	public List<SessionEvent> findEventsByUserId(String userId, EventFilter filter) {
		Assert.hasText(userId, "userId must not be null or empty");
		Assert.notNull(filter, "filter must not be null");
		// Across sessions the log order is the timestamp; seq only breaks ties.
		return query(SELECT_EVENTS_BY_USER_BASE, userId, filter, "e.timestamp, e.seq");
	}

	/**
	 * Runs an event query: every criterion the database can evaluate is pushed down as a
	 * {@code WHERE} clause, and the window ({@code lastN} or a page) as {@code LIMIT} /
	 * {@code OFFSET}. A {@link EventFilter#pattern()} is the exception: a
	 * {@link java.util.regex.Pattern} cannot be safely translated to portable SQL (H2,
	 * MySQL and PostgreSQL each have their own regex dialect, none a strict superset of
	 * Java's), so with a pattern the window is deferred and the SQL-filtered result is
	 * finished with {@link EventFilter#apply(List)} in memory.
	 * @param base the {@code SELECT … WHERE <scope> = ? } prefix
	 * @param scope the value of the scope column (session id or user id)
	 * @param order the {@code ORDER BY} columns, ascending; reversed for {@code lastN}
	 */
	private List<SessionEvent> query(String base, String scope, EventFilter filter, String order) {
		boolean inMemoryWindow = filter.pattern() != null;
		StringBuilder sql = new StringBuilder(base);
		List<Object> params = new ArrayList<>();
		params.add(scope);
		appendCriteria(sql, params, filter);

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
			return filter.apply(result);
		}
		if (filter.lastN() != null) {
			result = new ArrayList<>(result);
			Collections.reverse(result);
		}
		return Collections.unmodifiableList(result);
	}

	private void appendCriteria(StringBuilder sql, List<Object> params, EventFilter filter) {
		if (filter.from() != null) {
			sql.append("AND e.timestamp >= ? ");
			params.add(toUtc(filter.from()));
		}
		if (filter.to() != null) {
			sql.append("AND e.timestamp <= ? ");
			params.add(toUtc(filter.to()));
		}
		if (filter.messageTypes() != null && !filter.messageTypes().isEmpty()) {
			sql.append("AND e.message_type IN (");
			filter.messageTypes().forEach(mt -> sql.append("?,"));
			sql.setLength(sql.length() - 1);
			sql.append(") ");
			filter.messageTypes().forEach(mt -> params.add(mt.name()));
		}
		if (filter.excludeSynthetic()) {
			sql.append("AND e.synthetic = ? ");
			params.add(false);
		}
		if (filter.excludeArchived()) {
			sql.append("AND e.archived = ? ");
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
		SessionEventCodec.EncodedMessage msg = this.codec.encode(event.getMessage());
		this.jdbcTemplate.update(INSERT_EVENT, event.getId(), event.getSessionId(), toUtc(event.getTimestamp()),
				msg.type().name(), msg.text(), msg.data(), event.isSynthetic(), event.isArchived(),
				this.codec.toJson(event.getMetadata()));
	}

	/**
	 * Inserts the supplied events using a JDBC batch operation.
	 */
	private void batchInsertEvents(List<SessionEvent> events) {
		if (events.isEmpty()) {
			return;
		}
		this.jdbcTemplate.batchUpdate(INSERT_EVENT, events, events.size(), (ps, event) -> {
			SessionEventCodec.EncodedMessage msg = this.codec.encode(event.getMessage());
			ps.setString(1, event.getId());
			ps.setString(2, event.getSessionId());
			ps.setObject(3, toUtc(event.getTimestamp()));
			ps.setString(4, msg.type().name());
			ps.setString(5, msg.text());
			ps.setString(6, msg.data());
			ps.setBoolean(7, event.isSynthetic());
			ps.setBoolean(8, event.isArchived());
			ps.setString(9, this.codec.toJson(event.getMetadata()));
		});
	}

	private void requireSessionExists(String sessionId) {
		Integer count = this.jdbcTemplate.queryForObject(COUNT_SESSION, Integer.class, sessionId);
		if (count == null || count == 0) {
			throw new IllegalArgumentException("Session not found: " + sessionId);
		}
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
			Session.Builder builder = Session.builder()
				.id(rs.getString("id"))
				.userId(rs.getString("user_id"))
				.createdAt(Objects.requireNonNull(fromUtc(rs, "created_at")))
				.metadata(JdbcSessionRepository.this.codec.fromJsonMap(rs.getString("metadata")));
			if (expiresAt != null) {
				builder.expiresAt(expiresAt);
			}
			return builder.build();
		}

	}

	private class SessionEventRowMapper implements RowMapper<SessionEvent> {

		@Override
		public SessionEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
			MessageType messageType = MessageType.valueOf(rs.getString("message_type"));
			Message message = JdbcSessionRepository.this.codec.decode(new SessionEventCodec.EncodedMessage(messageType,
					rs.getString("message_content"), rs.getString("message_data")));

			// Merge the dedicated synthetic column back into the metadata map so that
			// SessionEvent.isSynthetic() returns the correct value.
			Map<String, Object> metadata = new HashMap<>(
					JdbcSessionRepository.this.codec.fromJsonMap(rs.getString("metadata")));
			if (rs.getBoolean("synthetic")) {
				metadata.put(SessionEvent.METADATA_SYNTHETIC, true);
			}

			return SessionEvent.builder()
				.id(rs.getString("id"))
				.sessionId(rs.getString("session_id"))
				.timestamp(Objects.requireNonNull(fromUtc(rs, "timestamp")))
				.message(message)
				.archived(rs.getBoolean("archived"))
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

		private Builder() {
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
		 * Overrides the {@link JsonMapper} used for metadata and message-data
		 * serialization. Defaults to {@code JsonMapper.builder().build()}.
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
			return new JdbcSessionRepository(jt, d, txm, new SessionEventCodec(this.jsonMapper));
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
