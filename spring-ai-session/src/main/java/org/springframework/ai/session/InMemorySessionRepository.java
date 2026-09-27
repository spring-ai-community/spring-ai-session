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

package org.springframework.ai.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

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
		SessionData saved = this.store.compute(session.id(), (id, existing) -> {
			if (existing == null) {
				return new SessionData(session, List.of(), 0L);
			}
			// Update: keep the original creation time, like the JDBC upsert does.
			Session updated = Session.builder()
				.id(session.id())
				.userId(session.userId())
				.createdAt(existing.session().createdAt())
				.expiresAt(session.expiresAt())
				.metadata(session.metadata())
				.build();
			return new SessionData(updated, existing.events(), existing.version());
		});
		return saved.session();
	}

	@Override
	public boolean saveIfAbsent(Session session) {
		Assert.notNull(session, "session must not be null");
		return this.store.putIfAbsent(session.id(), new SessionData(session, List.of(), 0L)) == null;
	}

	@Override
	public @Nullable Session findById(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		SessionData data = this.store.get(sessionId);
		return (data != null) ? data.session() : null;
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
			boolean alreadyAppended = existing.events().stream().anyMatch(e -> e.getId().equals(event.getId()));
			if (alreadyAppended) {
				// Idempotent replay: an event with this id was already committed, e.g. a
				// retried append after a crash. No-op -- do not duplicate the event or
				// increment the version.
				return existing;
			}
			List<SessionEvent> newEvents = new ArrayList<>(existing.events());
			newEvents.add(event);
			return existing.withEvents(newEvents);
		});
	}

	@Override
	public boolean compactEvents(String sessionId, List<SessionEvent> archivedEvents,
			List<SessionEvent> retainedEvents, long expectedVersion) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		Assert.notNull(archivedEvents, "archivedEvents must not be null");
		Assert.notNull(retainedEvents, "retainedEvents must not be null");
		boolean[] success = { false };
		this.store.compute(sessionId, (id, existing) -> {
			if (existing == null) {
				throw new IllegalArgumentException("Session not found: " + sessionId);
			}
			if (existing.version() != expectedVersion) {
				return existing;
			}
			success[0] = true;
			return existing.withEvents(compactedLog(sessionId, existing.events(), archivedEvents, retainedEvents));
		});
		return success[0];
	}

	/**
	 * Applies a compaction without reordering existing events: {@code archivedEvents}
	 * are flagged archived in place, previously-active events in neither list are dropped,
	 * and each new event in {@code retainedEvents} is inserted immediately before the next
	 * existing event that follows it there (or appended when none follows).
	 */
	private static List<SessionEvent> compactedLog(String sessionId, List<SessionEvent> log,
			List<SessionEvent> archivedEvents, List<SessionEvent> retainedEvents) {
		Set<String> logIds = log.stream().map(SessionEvent::getId).collect(Collectors.toSet());
		Set<String> archivedIds = new HashSet<>();
		for (SessionEvent event : archivedEvents) {
			if (!logIds.contains(event.getId())) {
				throw new IllegalArgumentException(
						"archivedEvents contains an event that is not in the log of session " + sessionId);
			}
			archivedIds.add(event.getId());
		}
		Set<String> retainedIds = retainedEvents.stream().map(SessionEvent::getId).collect(Collectors.toSet());

		// Group the new events by the existing event they must precede
		Map<String, List<SessionEvent>> insertBefore = new HashMap<>();
		List<SessionEvent> pending = new ArrayList<>();
		for (SessionEvent event : retainedEvents) {
			if (logIds.contains(event.getId())) {
				if (!pending.isEmpty()) {
					insertBefore.computeIfAbsent(event.getId(), id -> new ArrayList<>()).addAll(pending);
					pending.clear();
				}
			}
			else {
				if (!sessionId.equals(event.getSessionId())) {
					throw new IllegalArgumentException("retainedEvents contains a new event of session '"
							+ event.getSessionId() + "', not of session " + sessionId);
				}
				pending.add(event);
			}
		}

		List<SessionEvent> result = new ArrayList<>();
		for (SessionEvent event : log) {
			result.addAll(insertBefore.getOrDefault(event.getId(), List.of()));
			if (event.isArchived()) {
				result.add(event);
			}
			else if (archivedIds.contains(event.getId())) {
				result.add(event.asArchived());
			}
			else if (retainedIds.contains(event.getId())) {
				result.add(event);
			}
			// else: a previously-active event in neither list (e.g. a superseded summary)
		}
		result.addAll(pending);
		return List.copyOf(result);
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
			// long arithmetic: a large page number must not overflow into a negative index
			long fromIdx = (long) pageNum * size;
			if (fromIdx >= matched.size()) {
				matched = new ArrayList<>();
			}
			else {
				matched = matched.subList((int) fromIdx, (int) Math.min(fromIdx + size, matched.size()));
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
