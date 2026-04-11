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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * every {@link #appendEvent} and {@link #replaceEvents} call. The CAS variant of
 * {@code replaceEvents} guards compaction by issuing a conditional
 * {@code UPDATE … WHERE event_version = ?} first; if zero rows are updated the swap is
 * abandoned and {@code false} is returned.
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
		+ " FROM AI_SESSION WHERE user_id = ?";

	private static final String SELECT_EXPIRED_SESSION_IDS =
		"SELECT id FROM AI_SESSION WHERE expires_at IS NOT NULL AND expires_at < ?";

	private static final String DELETE_SESSION =
		"DELETE FROM AI_SESSION WHERE id = ?";

	private static final String INSERT_EVENT =
		"INSERT INTO AI_SESSION_EVENT"
		+ " (id, session_id, timestamp, message_type, message_content, message_data,"
		+ "  synthetic, branch, metadata)"
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

	private static final String DELETE_EVENTS =
		"DELETE FROM AI_SESSION_EVENT WHERE session_id = ?";

	private static final String SELECT_EVENTS_BASE =
		"SELECT e.id, e.session_id, e.timestamp, e.message_type, e.message_content,"
		+ "       e.message_data, e.synthetic, e.branch, e.metadata"
		+ " FROM AI_SESSION_EVENT e"
		+ " WHERE e.session_id = ? ";

	// @formatter:on

	private final JdbcTemplate jdbcTemplate;

	private final TransactionTemplate transactionTemplate;

	private final JdbcSessionRepositoryDialect dialect;

	private final JsonMapper jsonMapper;

	private JdbcSessionRepository(JdbcTemplate jdbcTemplate, JdbcSessionRepositoryDialect dialect,
			PlatformTransactionManager txManager, JsonMapper jsonMapper) {
		this.jdbcTemplate = jdbcTemplate;
		this.dialect = dialect;
		this.transactionTemplate = new TransactionTemplate(txManager);
		this.jsonMapper = jsonMapper;
	}

	// -------------------------------------------------------------------------
	// SessionRepository — session lifecycle
	// -------------------------------------------------------------------------

	@Override
	public Session save(Session session) {
		Assert.notNull(session, "session must not be null");
		this.jdbcTemplate.update(this.dialect.getUpsertSessionSql(), session.id(), session.userId(),
				toTimestamp(session.createdAt()), toTimestamp(session.expiresAt()), toJson(session.metadata()));
		return session;
	}

	@Override
	public Optional<Session> findById(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		List<Session> results = this.jdbcTemplate.query(SELECT_SESSION_BY_ID, new SessionRowMapper(), sessionId);
		return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
	}

	@Override
	public List<Session> findByUserId(String userId) {
		Assert.hasText(userId, "userId must not be null or empty");
		return this.jdbcTemplate.query(SELECT_SESSIONS_BY_USER, new SessionRowMapper(), userId);
	}

	@Override
	public List<String> findExpiredSessionIds(Instant before) {
		Assert.notNull(before, "before must not be null");
		return this.jdbcTemplate.queryForList(SELECT_EXPIRED_SESSION_IDS, String.class, toTimestamp(before));
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
		requireSessionExists(sessionId);
		this.transactionTemplate.execute(status -> {
			insertEvent(event);
			this.jdbcTemplate.update(INCREMENT_EVENT_VERSION, sessionId);
			return null;
		});
	}

	@Override
	public void replaceEvents(String sessionId, List<SessionEvent> events) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		Assert.notNull(events, "events must not be null");
		requireSessionExists(sessionId);
		this.transactionTemplate.execute(status -> {
			this.jdbcTemplate.update(DELETE_EVENTS, sessionId);
			events.forEach(this::insertEvent);
			this.jdbcTemplate.update(INCREMENT_EVENT_VERSION, sessionId);
			return null;
		});
	}

	@Override
	public boolean replaceEvents(String sessionId, List<SessionEvent> events, long expectedVersion) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		Assert.notNull(events, "events must not be null");
		requireSessionExists(sessionId);
		Boolean success = this.transactionTemplate.execute(status -> {
			// Atomically claim the version slot. If another writer already changed it,
			// 0 rows are updated and we bail out without touching the event log.
			int updated = this.jdbcTemplate.update(CAS_INCREMENT_EVENT_VERSION, sessionId, expectedVersion);
			if (updated == 0) {
				return false;
			}
			this.jdbcTemplate.update(DELETE_EVENTS, sessionId);
			events.forEach(this::insertEvent);
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

		StringBuilder sql = new StringBuilder(SELECT_EVENTS_BASE);
		List<Object> params = new ArrayList<>();
		params.add(sessionId);

		if (filter.from() != null) {
			sql.append("AND e.timestamp >= ? ");
			params.add(toTimestamp(filter.from()));
		}
		if (filter.to() != null) {
			sql.append("AND e.timestamp <= ? ");
			params.add(toTimestamp(filter.to()));
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
		if (filter.branch() != null) {
			// Visibility: null branch (root events) OR exact match OR caller is a
			// descendant (filterBranch starts with eventBranch + '.')
			sql.append("AND (e.branch IS NULL OR e.branch = ? OR ? LIKE e.branch || '.%') ");
			params.add(filter.branch());
			params.add(filter.branch());
		}
		if (filter.keyword() != null) {
			sql.append(this.dialect.getKeywordFilterFragment()).append(" ");
			params.add("%" + filter.keyword() + "%");
		}

		if (filter.lastN() != null) {
			sql.append("ORDER BY e.timestamp DESC LIMIT ? ");
			params.add(filter.lastN());
		}
		else if (filter.pageSize() != null) {
			int page = filter.page() != null ? filter.page() : 0;
			sql.append("ORDER BY e.timestamp ASC LIMIT ? OFFSET ? ");
			params.add(filter.pageSize());
			params.add((long) page * filter.pageSize());
		}
		else {
			sql.append("ORDER BY e.timestamp ASC ");
		}

		List<SessionEvent> result = this.jdbcTemplate.query(sql.toString(), new SessionEventRowMapper(),
				params.toArray());

		if (filter.lastN() != null) {
			result = new ArrayList<>(result);
			Collections.reverse(result);
		}

		return Collections.unmodifiableList(result);
	}

	// -------------------------------------------------------------------------
	// Internal helpers
	// -------------------------------------------------------------------------

	private void insertEvent(SessionEvent event) {
		Message msg = event.getMessage();
		this.jdbcTemplate.update(INSERT_EVENT, event.getId(), event.getSessionId(), toTimestamp(event.getTimestamp()),
				msg.getMessageType().name(), msg.getText(), messageDataToJson(msg), event.isSynthetic(),
				event.getBranch(), toJson(event.getMetadata()));
	}

	private void requireSessionExists(String sessionId) {
		Integer count = this.jdbcTemplate.queryForObject(COUNT_SESSION, Integer.class, sessionId);
		if (count == null || count == 0) {
			throw new IllegalArgumentException("Session not found: " + sessionId);
		}
	}

	@Nullable private Timestamp toTimestamp(@Nullable Instant instant) {
		return instant != null ? Timestamp.from(instant) : null;
	}

	@Nullable private String toJson(@Nullable Object value) {
		if (value == null) {
			return null;
		}
		try {
			return this.jsonMapper.writeValueAsString(value);
		}
		catch (JacksonException ex) {
			throw new IllegalStateException("Failed to serialize value to JSON", ex);
		}
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

	/**
	 * Serializes type-specific {@link Message} payload to JSON:
	 * <ul>
	 * <li>{@link AssistantMessage} with tool calls → JSON array of tool calls</li>
	 * <li>{@link ToolResponseMessage} → JSON array of tool responses</li>
	 * <li>All other types → {@code null}</li>
	 * </ul>
	 */
	@Nullable private String messageDataToJson(Message message) {
		if (message instanceof AssistantMessage am && am.hasToolCalls()) {
			return toJson(am.getToolCalls());
		}
		if (message instanceof ToolResponseMessage trm) {
			return toJson(trm.getResponses());
		}
		return null;
	}

	private Message toMessage(MessageType type, @Nullable String content, @Nullable String messageData) {
		return switch (type) {
			case USER -> new UserMessage(content != null ? content : "");
			case SYSTEM -> new SystemMessage(content != null ? content : "");
			case ASSISTANT -> {
				if (messageData != null && !messageData.isBlank()) {
					List<AssistantMessage.ToolCall> toolCalls = parseToolCalls(messageData);
					yield AssistantMessage.builder().content(content).toolCalls(toolCalls).build();
				}
				yield new AssistantMessage(content != null ? content : "");
			}
			case TOOL -> {
				if (messageData != null && !messageData.isBlank()) {
					List<ToolResponseMessage.ToolResponse> responses = parseToolResponses(messageData);
					yield ToolResponseMessage.builder().responses(responses).build();
				}
				yield ToolResponseMessage.builder().responses(List.of()).build();
			}
		};
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
			Timestamp expiresAt = rs.getTimestamp("expires_at");
			Session.Builder builder = Session.builder()
				.id(rs.getString("id"))
				.userId(rs.getString("user_id"))
				.createdAt(rs.getTimestamp("created_at").toInstant())
				.metadata(fromJsonMap(rs.getString("metadata")));
			if (expiresAt != null) {
				builder.expiresAt(expiresAt.toInstant());
			}
			return builder.build();
		}

	}

	private class SessionEventRowMapper implements RowMapper<SessionEvent> {

		@Override
		public SessionEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
			MessageType messageType = MessageType.valueOf(rs.getString("message_type"));
			Message message = toMessage(messageType, rs.getString("message_content"), rs.getString("message_data"));

			// Merge the dedicated synthetic column back into the metadata map so that
			// SessionEvent.isSynthetic() returns the correct value.
			Map<String, Object> metadata = new HashMap<>(fromJsonMap(rs.getString("metadata")));
			if (rs.getBoolean("synthetic")) {
				metadata.put(SessionEvent.METADATA_SYNTHETIC, true);
			}

			return SessionEvent.builder()
				.id(rs.getString("id"))
				.sessionId(rs.getString("session_id"))
				.timestamp(rs.getTimestamp("timestamp").toInstant())
				.message(message)
				.branch(rs.getString("branch"))
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
			return new JdbcSessionRepository(jt, d, txm, this.jsonMapper);
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
