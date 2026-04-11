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

package org.springframework.ai.session.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;
import org.springframework.util.Assert;

/**
 * Thread-safe, in-memory implementation of {@link SessionRepository}. Suitable for
 * development and testing. Not suitable for production use as state is lost on
 * application restart and not shared across instances.
 *
 * <p>
 * Session metadata and event log are stored together in a private {@code SessionData}
 * record. All mutations are performed atomically via {@link ConcurrentHashMap#compute}.
 *
 * @author Christian Tzolov
 * @since 2.0.0
 */
public final class InMemorySessionRepository implements SessionRepository {

	private final Map<String, SessionData> store = new ConcurrentHashMap<>();

	private InMemorySessionRepository() {
	}

	/**
	 * Returns a new {@link Builder} for {@code InMemorySessionRepository}.
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	@Override
	public Session save(Session session) {
		Assert.notNull(session, "session must not be null");
		this.store.compute(session.id(), (id, existing) -> {
			List<SessionEvent> events = (existing != null) ? existing.events() : List.of();
			long version = (existing != null) ? existing.version() : 0L;
			return new SessionData(session, events, version);
		});
		return session;
	}

	@Override
	public Optional<Session> findById(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		SessionData data = this.store.get(sessionId);
		return Optional.ofNullable(data).map(SessionData::session);
	}

	@Override
	public List<Session> findByUserId(String userId) {
		Assert.hasText(userId, "userId must not be null or empty");
		return this.store.values()
			.stream()
			.filter(d -> userId.equals(d.session().userId()))
			.map(SessionData::session)
			.toList();
	}

	@Override
	public List<String> findExpiredSessionIds(Instant before) {
		Assert.notNull(before, "before must not be null");
		return this.store.values()
			.stream()
			.filter(d -> d.session().expiresAt() != null && d.session().expiresAt().isBefore(before))
			.map(d -> d.session().id())
			.toList();
	}

	@Override
	public void delete(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		this.store.remove(sessionId);
	}

	@Override
	public void appendEvent(SessionEvent event) {
		Assert.notNull(event, "event must not be null");
		String sessionId = event.getSessionId();
		this.store.compute(sessionId, (id, existing) -> {
			if (existing == null) {
				throw new IllegalArgumentException("Session not found: " + sessionId);
			}
			List<SessionEvent> newEvents = new ArrayList<>(existing.events());
			newEvents.add(event);
			return existing.withEvents(newEvents);
		});
	}

	@Override
	public void replaceEvents(String sessionId, List<SessionEvent> events) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		Assert.notNull(events, "events must not be null");
		this.store.compute(sessionId, (id, existing) -> {
			if (existing == null) {
				throw new IllegalArgumentException("Session not found: " + sessionId);
			}
			return existing.withEvents(List.copyOf(events));
		});
	}

	@Override
	public boolean replaceEvents(String sessionId, List<SessionEvent> events, long expectedVersion) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		Assert.notNull(events, "events must not be null");
		boolean[] success = { false };
		this.store.compute(sessionId, (id, existing) -> {
			if (existing == null) {
				throw new IllegalArgumentException("Session not found: " + sessionId);
			}
			if (existing.version() != expectedVersion) {
				return existing;
			}
			success[0] = true;
			return existing.withEvents(List.copyOf(events));
		});
		return success[0];
	}

	@Override
	public long getEventVersion(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		SessionData data = this.store.get(sessionId);
		return (data != null) ? data.version() : 0L;
	}

	@Override
	public List<SessionEvent> findEvents(String sessionId, EventFilter filter) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		Assert.notNull(filter, "filter must not be null");

		SessionData data = this.store.get(sessionId);
		if (data == null) {
			return List.of();
		}

		List<SessionEvent> matched = data.events()
			.stream()
			.filter(filter::matches)
			.collect(Collectors.toCollection(ArrayList::new));

		if (filter.lastN() != null && matched.size() > filter.lastN()) {
			matched = matched.subList(matched.size() - filter.lastN(), matched.size());
		}

		if (filter.pageSize() != null) {
			int pageNum = (filter.page() != null) ? filter.page() : 0;
			int size = filter.pageSize();
			int fromIdx = pageNum * size;
			if (fromIdx >= matched.size()) {
				matched = new ArrayList<>();
			}
			else {
				matched = matched.subList(fromIdx, Math.min(fromIdx + size, matched.size()));
			}
		}

		return List.copyOf(matched);
	}

	private record SessionData(Session session, List<SessionEvent> events, long version) {

		SessionData withEvents(List<SessionEvent> newEvents) {
			return new SessionData(this.session, newEvents, this.version + 1);
		}

	}

	/**
	 * Builder for {@link InMemorySessionRepository}.
	 */
	public static final class Builder {

		private Builder() {
		}

		/**
		 * Builds the {@link InMemorySessionRepository} instance.
		 * @return a new {@code InMemorySessionRepository}
		 */
		public InMemorySessionRepository build() {
			return new InMemorySessionRepository();
		}

	}
}
