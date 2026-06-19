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
import java.util.List;

import org.jspecify.annotations.Nullable;

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
	 * event log is preserved.
	 * @return the saved session
	 */
	Session save(Session session);

	@Nullable Session findById(String sessionId);

	List<Session> findByUserId(String userId);

	/**
	 * Returns the IDs of all sessions whose TTL has expired before the given instant.
	 */
	List<String> findExpiredSessionIds(Instant before);

	/**
	 * Deletes the session with the given ID.
	 */
	void delete(String sessionId);

	// Events

	/**
	 * Appends a single event to the session's event log. The target session is identified
	 * by {@link SessionEvent#getSessionId()}. Also updates {@code lastActiveAt} on the
	 * session.
	 * @throws IllegalArgumentException if the session does not exist
	 */
	void appendEvent(SessionEvent event);

	/**
	 * Atomically applies a compaction result to the session's event log using an
	 * optimistic compare-and-swap. The swap is performed only if the current event-log
	 * version equals {@code expectedVersion}; otherwise the call is a no-op and returns
	 * {@code false} (another writer mutated the log between the caller's read and this
	 * write).
	 *
	 * <p>
	 * Archived events are <em>retained</em> in the log (soft-deleted via
	 * {@link SessionEvent#isArchived()}) so they remain searchable by the Recall Storage
	 * tools. On success the resulting active log is, in order:
	 * <ol>
	 * <li>all events that were already archived (preserved as-is),</li>
	 * <li>the events in {@code archivedEvents}, now marked archived,</li>
	 * <li>the events in {@code retainedEvents} (the new active window, typically a
	 * synthetic summary turn followed by the most recent events), marked active.</li>
	 * </ol>
	 * Any previously-active event that appears in neither list (e.g. a superseded
	 * synthetic summary) is removed.
	 *
	 * <p>
	 * Callers should read {@link #getEventVersion} <em>before</em> reading events via
	 * {@link #findEvents}, then pass that version here. If this method returns
	 * {@code false} the caller should treat the compaction as a no-op — the concurrent
	 * writer already handled the session.
	 * @param sessionId the session whose log is being compacted
	 * @param archivedEvents events to mark archived (must already exist in the log)
	 * @param retainedEvents the new active event set, in chronological order
	 * @param expectedVersion the event-log version the caller observed
	 * @return {@code true} when the swap succeeded, {@code false} on a version mismatch
	 * @throws IllegalArgumentException if the session does not exist
	 */
	boolean compactEvents(String sessionId, List<SessionEvent> archivedEvents, List<SessionEvent> retainedEvents,
			long expectedVersion);

	/**
	 * Returns the current event-log version for the given session. The version is
	 * incremented atomically on every {@link #appendEvent} and {@link #compactEvents}
	 * call. Returns {@code 0} when the session does not exist or has no events yet.
	 * <p>
	 * Read this <em>before</em> calling {@link #findEvents} to obtain a version that is
	 * guaranteed to be ≤ the version of the events you subsequently read, which is the
	 * safe ordering for passing to {@link #compactEvents(String, List, List, long)}.
	 */
	long getEventVersion(String sessionId);

	/**
	 * Returns events for the given session that match the provided filter. If
	 * {@link EventFilter#lastN()} is set, only the most recent N matching events are
	 * returned. Events are always returned in chronological order (oldest first).
	 * <p>
	 * <strong>Existence contract:</strong> returns an empty list when the session does
	 * not exist, rather than throwing. This differs from {@link #appendEvent} and
	 * {@link #compactEvents}, which throw {@link IllegalArgumentException} for unknown
	 * sessions. The silent-empty behaviour allows callers to query event history without
	 * first checking whether the session exists (the "read before write" pattern used by
	 * {@code SessionMemoryAdvisor}).
	 */
	List<SessionEvent> findEvents(String sessionId, EventFilter filter);

}
