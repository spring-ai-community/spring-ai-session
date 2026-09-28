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
import java.util.Comparator;
import java.util.List;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.session.compaction.CompactionPlan;

/**
 * Persistence contract for {@link Session} objects and their event logs.
 *
 * <p>
 * Implementations must be thread-safe. Events are stored separately from session metadata
 * and are mutated via dedicated methods to keep session metadata immutable.
 *
 * @author Christian Tzolov
 * @since 2.0.0
 */
public interface SessionRepository {

	// Sessions

	/**
	 * Persists session metadata (create or update). If the session already exists its
	 * event log and its original {@link Session#createdAt()} are preserved; the other
	 * metadata fields are replaced.
	 * @return the saved session
	 */
	Session save(Session session);

	/**
	 * Inserts the session only if no session with the same id exists — never updates an
	 * existing one. Used by {@link SessionService#create(CreateSessionRequest)} so that
	 * two concurrent creates of the same id cannot both succeed (the second would
	 * otherwise silently take over the first caller's session).
	 * <p>
	 * Implementations must perform the check and the insert atomically (e.g. a
	 * primary-key-guarded {@code INSERT}, or a compare-and-set put).
	 * @return {@code true} if the session was inserted, {@code false} if a session with
	 * the same id already exists (the existing session is left untouched)
	 */
	boolean saveIfAbsent(Session session);

	@Nullable Session findById(String sessionId);

	List<Session> findByUserId(String userId);

	/**
	 * Deletes the session with the given ID.
	 */
	void delete(String sessionId);

	/**
	 * Deletes every session whose TTL has expired before the given instant, together with
	 * its events, and returns the number of sessions deleted.
	 * <p>
	 * Implementations must check the expiry and delete in one atomic step (e.g. a single
	 * {@code DELETE … WHERE expires_at < ?}), so that a session whose TTL was extended
	 * concurrently (e.g. by {@link #save(Session)}) is not deleted.
	 * @param before the expiry cut-off
	 * @return the number of sessions deleted
	 */
	int deleteExpiredSessions(Instant before);

	// Events

	/**
	 * Appends a single event to the session's event log. The target session is identified
	 * by {@link SessionEvent#getSessionId()}.
	 * <p>
	 * <strong>Idempotent by id:</strong> if an event with the same
	 * {@link SessionEvent#getId()} already exists for this session, this call is a no-op
	 * — it does not throw, and it does not append a duplicate row or increment the
	 * event-log version. This makes a retried append (e.g. after a crash between the
	 * write and the caller receiving confirmation) safe to repeat. Callers that want this
	 * safety should supply a deterministic id (e.g. derived from a durable run/turn id)
	 * rather than relying on {@link SessionEvent.Builder}'s random default.
	 * <p>
	 * Event ids should be unique across <em>all</em> sessions: persistent implementations
	 * (e.g. JDBC) key events by id alone and reject an id already used by a different
	 * session rather than silently dropping the event.
	 * @throws IllegalArgumentException if the session does not exist
	 * @throws IllegalStateException if the id is already used by an event of another
	 * session (implementations with a global event-id namespace only)
	 */
	void appendEvent(SessionEvent event);

	/**
	 * Atomically applies a compaction plan to the session's event log using an optimistic
	 * compare-and-swap. The plan is applied only if the current event-log version equals
	 * {@code expectedVersion}; otherwise the call is a no-op and returns {@code false}
	 * (another writer mutated the log between the caller's read and this write).
	 *
	 * <p>
	 * Archived events are <em>retained</em> in the log (soft-deleted via
	 * {@link SessionEvent#isArchived()}) so they remain searchable by the Recall Storage
	 * tools. Compaction never removes or reorders existing events. On success:
	 * <ul>
	 * <li>the events in {@link CompactionPlan#archiveIds()} are flagged archived <em>in
	 * place</em>;</li>
	 * <li>each {@link CompactionPlan.Insert} group is inserted immediately before its
	 * anchor event, or appended at the end of the log when the anchor is {@code null};</li>
	 * <li>every other event stays where it is.</li>
	 * </ul>
	 * {@link CompactionPlan#applyTo(List)} is the reference implementation of these rules
	 * for a log held as a list.
	 *
	 * <p>
	 * Callers should read {@link #getEventVersion} <em>before</em> reading events via
	 * {@link #findEvents}, then pass that version here. If this method returns
	 * {@code false} the caller should treat the compaction as a no-op — the concurrent
	 * writer already handled the session.
	 * @param sessionId the session whose log is being compacted
	 * @param plan the plan; its archive ids and anchor ids must be in the session's log,
	 * otherwise implementations reject the whole call with
	 * {@link IllegalArgumentException} and change nothing
	 * @param expectedVersion the event-log version the caller observed
	 * @return {@code true} when the plan was applied, {@code false} on a version mismatch
	 * @throws IllegalArgumentException if the session does not exist
	 */
	boolean applyCompaction(String sessionId, CompactionPlan plan, long expectedVersion);

	/**
	 * Returns the current event-log version for the given session. The version is
	 * incremented atomically on every {@link #appendEvent} call that actually appends a
	 * new event, and on every successful {@link #applyCompaction} call (an idempotent
	 * replay of an already-applied {@link #appendEvent} does not increment it). Returns
	 * {@code 0} when the session does not exist or has no events yet.
	 * <p>
	 * Read this <em>before</em> calling {@link #findEvents} to obtain a version that is
	 * guaranteed to be ≤ the version of the events you subsequently read, which is the
	 * safe ordering for passing to {@link #applyCompaction(String, CompactionPlan, long)}.
	 */
	long getEventVersion(String sessionId);

	/**
	 * Returns events for the given session that match the provided filter. If
	 * {@link EventFilter#lastN()} is set, only the most recent N matching events are
	 * returned. Events are always returned in chronological order (oldest first).
	 * <p>
	 * <strong>Push the filter down.</strong> The cost of a read must not grow with the
	 * size of the whole log: an implementation is expected to evaluate
	 * {@link EventFilter#excludeArchived()}, {@link EventFilter#excludeSynthetic()},
	 * {@link EventFilter#messageTypes()}, the time range, {@link EventFilter#lastN()},
	 * {@link EventFilter#page()} / {@link EventFilter#pageSize()} and, where the store
	 * can, the keywords in its query, and to fall back to {@link EventFilter#apply(List)}
	 * only for what the store cannot express (a {@link EventFilter#pattern()}, typically),
	 * as {@code JdbcSessionRepository} does.
	 * <p>
	 * <strong>Existence contract:</strong> returns an empty list when the session does
	 * not exist, rather than throwing. This differs from {@link #appendEvent} and
	 * {@link #applyCompaction}, which throw {@link IllegalArgumentException} for unknown
	 * sessions. The silent-empty behaviour allows callers to query event history without
	 * first checking whether the session exists (the "read before write" pattern used by
	 * {@code SessionMemoryAdvisor}).
	 */
	List<SessionEvent> findEvents(String sessionId, EventFilter filter);

	/**
	 * Returns the events of <em>every</em> session of the given user that match the
	 * filter, ordered by timestamp (then event id) across sessions, with the filter's
	 * {@link EventFilter#lastN()} or page applied to that combined order.
	 * <p>
	 * The default runs the filter without its window over each session of the user,
	 * sorts the union and applies the window in memory, which costs the total number of
	 * matches on every call. Stores that can join sessions and events should override it
	 * with a single sorted, paged query.
	 * @param userId the user whose sessions are searched
	 * @param filter the filter; its window applies to the combined result
	 * @return the matching events, oldest first
	 */
	default List<SessionEvent> findEventsByUserId(String userId, EventFilter filter) {
		EventFilter perSession = filter.withoutWindow();
		List<SessionEvent> matches = new ArrayList<>();
		for (Session session : findByUserId(userId)) {
			matches.addAll(findEvents(session.id(), perSession));
		}
		matches.sort(Comparator.comparing(SessionEvent::getTimestamp).thenComparing(SessionEvent::getId));
		return filter.applyWindow(matches);
	}

}
