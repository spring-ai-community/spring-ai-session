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

package org.springframework.ai.session.mongodb;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bson.Document;
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
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.util.Assert;

/**
 * MongoDB-backed implementation of {@link SessionRepository} using Spring Data MongoDB.
 *
 * <h2>Collections</h2>
 * <p>
 * Two collections are required:
 * <ul>
 * <li>{@code ai_sessions} — session metadata (id, userId, createdAt, expiresAt, metadata,
 * eventVersion, eventSeq)</li>
 * <li>{@code ai_session_events} — append-only event log</li>
 * </ul>
 *
 * <h2>Message serialization</h2>
 * <p>
 * Each {@link SessionEvent}'s wrapped {@link Message} is stored across three fields:
 * <ul>
 * <li>{@code messageType} — the {@link MessageType} name</li>
 * <li>{@code messageContent} — plain text ({@code message.getText()})</li>
 * <li>{@code messageData} — JSON string for type-specific structured data
 * ({@link AssistantMessage.ToolCall} list or {@link ToolResponseMessage.ToolResponse}
 * list)</li>
 * </ul>
 *
 * <h2>Optimistic concurrency (CAS)</h2>
 * <p>
 * The {@code eventVersion} field in {@code ai_sessions} is incremented atomically on
 * every {@link #appendEvent} and {@link #compactEvents} call. {@code compactEvents} guards
 * compaction by issuing a conditional {@code findAndModify} that matches
 * {@code eventVersion = expectedVersion} first; if no document matches, the swap is
 * abandoned and {@code false} is returned.
 *
 * <h2>Event ordering</h2>
 * <p>
 * Events are ordered by a monotonically increasing {@code seq} field, which reflects
 * insertion order (the logical conversation order) rather than wall-clock
 * {@code timestamp}. This keeps a synthetic compaction summary — whose timestamp is the
 * compaction time — correctly positioned ahead of the older active-window events it
 * precedes. The {@code seq} value is sourced from an {@code eventSeq} counter on the
 * session document that is atomically incremented via {@code $inc}.
 *
 * <h2>Thread safety</h2>
 * <p>
 * All mutating operations use atomic MongoDB operations ({@code $inc},
 * {@code findAndModify}). The class is thread-safe as long as the underlying
 * {@link MongoTemplate} is.
 *
 * @author Christian Tzolov
 * @since 2.0.0
 */
public final class MongoSessionRepository implements SessionRepository {

	private static final Logger logger = LoggerFactory.getLogger(MongoSessionRepository.class);

	private static final String COLLECTION_SESSIONS = "ai_sessions";

	private static final String COLLECTION_EVENTS = "ai_session_events";

	private static final String FIELD_ID = "_id";

	private static final String FIELD_USER_ID = "userId";

	private static final String FIELD_CREATED_AT = "createdAt";

	private static final String FIELD_EXPIRES_AT = "expiresAt";

	private static final String FIELD_METADATA = "metadata";

	private static final String FIELD_EVENT_VERSION = "eventVersion";

	private static final String FIELD_EVENT_SEQ = "eventSeq";

	private static final String FIELD_SESSION_ID = "sessionId";

	private static final String FIELD_TIMESTAMP = "timestamp";

	private static final String FIELD_MESSAGE_TYPE = "messageType";

	private static final String FIELD_MESSAGE_CONTENT = "messageContent";

	private static final String FIELD_MESSAGE_DATA = "messageData";

	private static final String FIELD_SYNTHETIC = "synthetic";

	private static final String FIELD_ARCHIVED = "archived";

	private static final String FIELD_BRANCH = "branch";

	private static final String FIELD_SEQ = "seq";

	private final MongoTemplate mongoTemplate;

	private final JsonMapper jsonMapper;

	private MongoSessionRepository(MongoTemplate mongoTemplate, JsonMapper jsonMapper) {
		this.mongoTemplate = mongoTemplate;
		this.jsonMapper = jsonMapper;
	}

	// -------------------------------------------------------------------------
	// SessionRepository — session lifecycle
	// -------------------------------------------------------------------------

	@Override
	public Session save(Session session) {
		Assert.notNull(session, "session must not be null");
		Update update = new Update().set(FIELD_USER_ID, session.userId())
			.set(FIELD_CREATED_AT, toDate(session.createdAt()))
			.set(FIELD_EXPIRES_AT, toDate(session.expiresAt()))
			.set(FIELD_METADATA, session.metadata());
		this.mongoTemplate.upsert(Query.query(Criteria.where(FIELD_ID).is(session.id())), update, COLLECTION_SESSIONS);
		return session;
	}

	@Override
	public @Nullable Session findById(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		Document doc = this.mongoTemplate.findOne(Query.query(Criteria.where(FIELD_ID).is(sessionId)), Document.class,
				COLLECTION_SESSIONS);
		return (doc != null) ? toSession(doc) : null;
	}

	@Override
	public List<Session> findByUserId(String userId) {
		Assert.hasText(userId, "userId must not be null or empty");
		List<Document> docs = this.mongoTemplate.find(Query.query(Criteria.where(FIELD_USER_ID).is(userId)),
				Document.class, COLLECTION_SESSIONS);
		return docs.stream().map(this::toSession).toList();
	}

	@Override
	public List<String> findExpiredSessionIds(Instant before) {
		Assert.notNull(before, "before must not be null");
		Query query = Query.query(Criteria.where(FIELD_EXPIRES_AT).ne(null).lt(toDate(before)));
		query.fields().include(FIELD_ID);
		return this.mongoTemplate.find(query, Document.class, COLLECTION_SESSIONS)
			.stream()
			.map(d -> d.get(FIELD_ID).toString())
			.toList();
	}

	@Override
	public void delete(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		this.mongoTemplate.remove(Query.query(Criteria.where(FIELD_ID).is(sessionId)), COLLECTION_SESSIONS);
		this.mongoTemplate.remove(Query.query(Criteria.where(FIELD_SESSION_ID).is(sessionId)), COLLECTION_EVENTS);
	}

	// -------------------------------------------------------------------------
	// SessionRepository — event log
	// -------------------------------------------------------------------------

	@Override
	public void appendEvent(SessionEvent event) {
		Assert.notNull(event, "event must not be null");
		String sessionId = event.getSessionId();
		requireSessionExists(sessionId);
		// Atomically claim the next seq value and increment eventVersion in one shot.
		Document updated = this.mongoTemplate.findAndModify(
				Query.query(Criteria.where(FIELD_ID).is(sessionId)),
				new Update().inc(FIELD_EVENT_SEQ, 1).inc(FIELD_EVENT_VERSION, 1),
				FindAndModifyOptions.options().returnNew(true), Document.class, COLLECTION_SESSIONS);
		if (updated == null) {
			throw new IllegalArgumentException("Session not found: " + sessionId);
		}
		long seq = ((Number) updated.get(FIELD_EVENT_SEQ)).longValue();
		insertEvent(event, seq);
	}

	@Override
	public boolean compactEvents(String sessionId, List<SessionEvent> archivedEvents,
			List<SessionEvent> retainedEvents, long expectedVersion) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		Assert.notNull(archivedEvents, "archivedEvents must not be null");
		Assert.notNull(retainedEvents, "retainedEvents must not be null");
		requireSessionExists(sessionId);
		// Atomically claim the version slot. If another writer already changed it, no
		// document matches and we bail out without touching the event log.
		Document updated = this.mongoTemplate.findAndModify(
				Query.query(Criteria.where(FIELD_ID).is(sessionId).and(FIELD_EVENT_VERSION).is(expectedVersion)),
				new Update().inc(FIELD_EVENT_VERSION, 1), FindAndModifyOptions.options().returnNew(false), Document.class,
				COLLECTION_SESSIONS);
		if (updated == null) {
			return false;
		}
		// Read the previously-archived events (oldest prefix) so they survive the
		// delete-and-reinsert. The whole log is rebuilt in order so the seq reflects the
		// logical conversation order: previously-archived events, then newly-archived
		// events, then the new active window (summary + recent).
		List<SessionEvent> previouslyArchived = this.mongoTemplate
			.find(Query.query(Criteria.where(FIELD_SESSION_ID).is(sessionId).and(FIELD_ARCHIVED).is(true))
				.with(Sort.by(FIELD_SEQ).ascending()), Document.class, COLLECTION_EVENTS)
			.stream()
			.map(this::toSessionEvent)
			.toList();
		this.mongoTemplate.remove(Query.query(Criteria.where(FIELD_SESSION_ID).is(sessionId)), COLLECTION_EVENTS);
		// Re-insert all events in order with new seq values: previously-archived, then
		// newly-archived, then the new active window.
		long seq = 0;
		for (SessionEvent e : previouslyArchived) {
			insertEvent(e, ++seq);
		}
		for (SessionEvent e : archivedEvents) {
			insertEvent(e.asArchived(), ++seq);
		}
		for (SessionEvent e : retainedEvents) {
			insertEvent(e, ++seq);
		}
		// Set the seq counter to the last used value so the next appendEvent gets seq+1.
		this.mongoTemplate.updateFirst(Query.query(Criteria.where(FIELD_ID).is(sessionId)),
				new Update().set(FIELD_EVENT_SEQ, seq), COLLECTION_SESSIONS);
		return true;
	}

	@Override
	public long getEventVersion(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		Query query = Query.query(Criteria.where(FIELD_ID).is(sessionId));
		query.fields().include(FIELD_EVENT_VERSION);
		Document doc = this.mongoTemplate.findOne(query, Document.class, COLLECTION_SESSIONS);
		if (doc == null) {
			return 0L;
		}
		Object version = doc.get(FIELD_EVENT_VERSION);
		return (version instanceof Number n) ? n.longValue() : 0L;
	}

	@Override
	public List<SessionEvent> findEvents(String sessionId, EventFilter filter) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		Assert.notNull(filter, "filter must not be null");

		List<Criteria> criteria = new ArrayList<>();
		criteria.add(Criteria.where(FIELD_SESSION_ID).is(sessionId));

		if (filter.from() != null) {
			criteria.add(Criteria.where(FIELD_TIMESTAMP).gte(toDate(filter.from())));
		}
		if (filter.to() != null) {
			criteria.add(Criteria.where(FIELD_TIMESTAMP).lte(toDate(filter.to())));
		}
		if (filter.messageTypes() != null && !filter.messageTypes().isEmpty()) {
			criteria.add(Criteria.where(FIELD_MESSAGE_TYPE).in(filter.messageTypes().stream().map(MessageType::name).toList()));
		}
		if (filter.excludeSynthetic()) {
			criteria.add(Criteria.where(FIELD_SYNTHETIC).is(false));
		}
		if (filter.excludeArchived()) {
			criteria.add(Criteria.where(FIELD_ARCHIVED).is(false));
		}
		if (filter.branch() != null) {
			// Visibility: null branch (root events) OR exact match OR caller is a
			// descendant (filterBranch starts with eventBranch + '.')
			criteria.add(new Criteria().orOperator(Criteria.where(FIELD_BRANCH).is(null),
					Criteria.where(FIELD_BRANCH).is(filter.branch()),
					Criteria.where(FIELD_BRANCH).regex("^" + java.util.regex.Pattern.quote(filter.branch()) + "\\.")));
		}
		if (filter.keyword() != null) {
			criteria.add(Criteria.where(FIELD_MESSAGE_CONTENT).regex(filter.keyword(), "i"));
		}

		Query query = Query.query(new Criteria().andOperator(criteria.toArray(new Criteria[0])));

		if (filter.lastN() != null) {
			query.with(Sort.by(FIELD_SEQ).descending()).limit(filter.lastN());
		}
		else if (filter.pageSize() != null) {
			int page = filter.page() != null ? filter.page() : 0;
			query.with(Sort.by(FIELD_SEQ).ascending()).skip((long) page * filter.pageSize()).limit(filter.pageSize());
		}
		else {
			query.with(Sort.by(FIELD_SEQ).ascending());
		}

		List<SessionEvent> result = this.mongoTemplate.find(query, Document.class, COLLECTION_EVENTS)
			.stream()
			.map(this::toSessionEvent)
			.toList();

		if (filter.lastN() != null) {
			List<SessionEvent> reversed = new ArrayList<>(result);
			Collections.reverse(reversed);
			return Collections.unmodifiableList(reversed);
		}

		return Collections.unmodifiableList(result);
	}

	// -------------------------------------------------------------------------
	// Internal helpers
	// -------------------------------------------------------------------------

	private void insertEvent(SessionEvent event, long seq) {
		Message msg = event.getMessage();
		Document doc = new Document(FIELD_ID, event.getId())
			.append(FIELD_SESSION_ID, event.getSessionId())
			.append(FIELD_TIMESTAMP, toDate(event.getTimestamp()))
			.append(FIELD_MESSAGE_TYPE, msg.getMessageType().name())
			.append(FIELD_MESSAGE_CONTENT, msg.getText())
			.append(FIELD_MESSAGE_DATA, messageDataToJson(msg))
			.append(FIELD_SYNTHETIC, event.isSynthetic())
			.append(FIELD_ARCHIVED, event.isArchived())
			.append(FIELD_BRANCH, event.getBranch())
			.append(FIELD_METADATA, event.getMetadata())
			.append(FIELD_SEQ, seq);
		this.mongoTemplate.insert(doc, COLLECTION_EVENTS);
	}

	private void requireSessionExists(String sessionId) {
		if (!this.mongoTemplate.exists(Query.query(Criteria.where(FIELD_ID).is(sessionId)), COLLECTION_SESSIONS)) {
			throw new IllegalArgumentException("Session not found: " + sessionId);
		}
	}

	@Nullable private Date toDate(@Nullable Instant instant) {
		return instant != null ? Date.from(instant) : null;
	}

	@Nullable private Instant toInstant(@Nullable Date date) {
		return date != null ? date.toInstant() : null;
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

	@SuppressWarnings("unchecked")
	private Map<String, Object> fromJsonMap(@Nullable Object value) {
		if (value == null) {
			return Map.of();
		}
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> result = new HashMap<>();
			map.forEach((k, v) -> result.put(k.toString(), v));
			return result;
		}
		if (value instanceof String json && !json.isBlank()) {
			try {
				return this.jsonMapper.readValue(json, new TypeReference<Map<String, Object>>() {
				});
			}
			catch (JacksonException ex) {
				logger.warn("Failed to deserialize metadata JSON; returning empty map", ex);
				return new HashMap<>();
			}
		}
		return new HashMap<>();
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

	private Session toSession(Document doc) {
		Session.Builder builder = Session.builder()
			.id(doc.get(FIELD_ID).toString())
			.userId(doc.get(FIELD_USER_ID).toString())
			.createdAt(toInstant(doc.getDate(FIELD_CREATED_AT)))
			.metadata(fromJsonMap(doc.get(FIELD_METADATA)));
		Date expiresAt = doc.getDate(FIELD_EXPIRES_AT);
		if (expiresAt != null) {
			builder.expiresAt(expiresAt.toInstant());
		}
		return builder.build();
	}

	private SessionEvent toSessionEvent(Document doc) {
		MessageType messageType = MessageType.valueOf(doc.getString(FIELD_MESSAGE_TYPE));
		Message message = toMessage(messageType, doc.getString(FIELD_MESSAGE_CONTENT), doc.getString(FIELD_MESSAGE_DATA));

		// Merge the dedicated synthetic field back into the metadata map so that
		// SessionEvent.isSynthetic() returns the correct value.
		Map<String, Object> metadata = new HashMap<>(fromJsonMap(doc.get(FIELD_METADATA)));
		if (doc.getBoolean(FIELD_SYNTHETIC, false)) {
			metadata.put(SessionEvent.METADATA_SYNTHETIC, true);
		}

		return SessionEvent.builder()
			.id(doc.get(FIELD_ID).toString())
			.sessionId(doc.getString(FIELD_SESSION_ID))
			.timestamp(toInstant(doc.getDate(FIELD_TIMESTAMP)))
			.message(message)
			.branch(doc.getString(FIELD_BRANCH))
			.archived(doc.getBoolean(FIELD_ARCHIVED, false))
			.metadata(metadata)
			.build();
	}

	/** Returns a new {@link Builder}. */
	public static Builder builder() {
		return new Builder();
	}

	// -------------------------------------------------------------------------
	// Builder
	// -------------------------------------------------------------------------

	/**
	 * Builder for {@link MongoSessionRepository}.
	 *
	 * <p>
	 * Minimum required: a {@link MongoTemplate}. All other fields default to sensible
	 * values.
	 */
	public static final class Builder {

		@Nullable private MongoTemplate mongoTemplate;

		private JsonMapper jsonMapper = JsonMapper.builder().build();

		private Builder() {
		}

		/** Sets the {@link MongoTemplate}. */
		public Builder mongoTemplate(MongoTemplate mongoTemplate) {
			this.mongoTemplate = mongoTemplate;
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
		public MongoSessionRepository build() {
			Assert.notNull(this.mongoTemplate, "mongoTemplate must not be null");
			return new MongoSessionRepository(this.mongoTemplate, this.jsonMapper);
		}

	}

}
