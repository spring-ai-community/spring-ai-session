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
import java.util.Collection;
import java.util.List;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.CompactionStrategy;
import org.springframework.ai.session.compaction.CompactionTrigger;

/**
 * Primary API for managing the full lifecycle of {@link Session} objects.
 *
 * @author Christian Tzolov
 * @since 0.1.0
 */
public interface SessionService {

	// Sessions

	/**
	 * Creates a new root session. Uses {@link CreateSessionRequest#id()} when set,
	 * otherwise a random UUID. A child of an existing session is created with
	 * {@link #createChild}.
	 * @throws IllegalStateException if a session with the requested id already exists
	 */
	Session create(CreateSessionRequest request);

	@Nullable Session findById(String sessionId);

	/**
	 * Returns the sessions whose metadata holds {@code value} under {@code name}; see
	 * {@link SessionRepository#findByIndex}. The sessions of a principal are
	 * {@code findByIndex(SessionAttributes.PRINCIPAL_NAME, name)}.
	 */
	List<Session> findByIndex(String name, String value);

	// Session tree

	/**
	 * Creates a child session: a session that belongs to the tree of
	 * {@code parentSessionId}, for a sub-agent delegated to from the parent. The child has
	 * its own log and compaction.
	 * It names the parent ({@link Session#parentSessionId()}) and the tree's root
	 * ({@link Session#rootSessionId()}), inherits the parent's scope attributes
	 * (the principal, and whatever else the service is configured to pass down) and the
	 * parent's expiry, and may not diverge from them: a request that sets an inherited
	 * attribute to another value, or its own time to live, is rejected. The request's id and
	 * other metadata are honoured as in {@link #create}; put
	 * {@link SessionAttributes#AGENT_NAME} there to name the agent. The depth of the tree
	 * is limited (one level by default). A {@link EventType#CHILD_CREATED} marker is
	 * appended to the parent's log.
	 * @throws IllegalArgumentException if the parent does not exist, the depth limit is
	 * reached, or the request diverges from the inherited attributes or expiry
	 * @throws IllegalStateException if a session with the requested id already exists
	 */
	Session createChild(String parentSessionId, CreateSessionRequest request);

	/** Creates a child session for the named agent; see {@link #createChild(String, CreateSessionRequest)}. */
	default Session createChild(String parentSessionId, String agentName) {
		return createChild(parentSessionId,
				CreateSessionRequest.builder().metadata(SessionAttributes.AGENT_NAME, agentName).build());
	}

	/** The children created directly from the given session, oldest first. */
	List<Session> findChildren(String parentSessionId);

	/**
	 * Every session of the tree rooted at the given id, the root itself included, oldest
	 * first. A root with no children is a tree of one.
	 */
	List<Session> findTree(String rootSessionId);

	// Status

	/**
	 * Starts a turn: the session goes {@link SessionStatus#RUNNING} and a
	 * {@link EventType#STATUS} event records it. The change is a compare-and-set from
	 * {@link SessionStatus#IDLE}, so two callers cannot run turns on one session at the
	 * same time; a turn that was abandoned (the caller crashed) is ended with
	 * {@link #endTurn} and {@link StopReason#INTERRUPTED}.
	 * @return the session, running
	 * @throws IllegalArgumentException if the session does not exist
	 * @throws IllegalStateException if the session is already running a turn, or is
	 * archived
	 */
	Session startTurn(String sessionId);

	/**
	 * Ends the running turn: the session goes {@link SessionStatus#IDLE} with the given
	 * stop reason and a {@link EventType#STATUS} event records it.
	 * @return the session, idle
	 * @throws IllegalArgumentException if the session does not exist
	 * @throws IllegalStateException if the session is not running a turn
	 */
	default Session endTurn(String sessionId, StopReason stopReason) {
		return endTurn(sessionId, stopReason, List.of());
	}

	/**
	 * Ends the running turn and names the events it waits on: for
	 * {@link StopReason#REQUIRES_ACTION}, the assistant message whose tool calls need a
	 * {@link ToolConfirmation}. The ids go into the {@link EventType#STATUS} event's
	 * payload ({@link SessionStatusEvents#PAYLOAD_EVENT_IDS}).
	 * @param eventIds the events the turn waits on; empty for any other stop reason
	 * @return the session, idle
	 * @throws IllegalArgumentException if the session does not exist
	 * @throws IllegalStateException if the session is not running a turn
	 */
	Session endTurn(String sessionId, StopReason stopReason, List<String> eventIds);

	/**
	 * The status of a session tree as a whole, never stored: {@link SessionStatus#RUNNING}
	 * while any member of the tree runs a turn, otherwise the root's own status.
	 * @throws IllegalArgumentException if the root does not exist
	 */
	SessionStatus aggregateStatus(String rootSessionId);

	/**
	 * Archives the session and, when it has children, its children: each goes
	 * {@link SessionStatus#TERMINATED}, is stamped with {@link Session#archivedAt()},
	 * records a {@link EventType#STATUS} event and accepts no further events, turns or
	 * children. Its history stays readable until {@link #delete}. An archived session is
	 * left as it is, so the call is idempotent. Like a delete, archiving never affects the
	 * parent.
	 * @return the session, archived
	 * @throws IllegalArgumentException if the session does not exist
	 * @throws IllegalStateException if the session or one of its children is running a
	 * turn: interrupt or wait for it first
	 */
	Session archive(String sessionId);

	/**
	 * Deletes the session, its events and, when it has children, the children and their
	 * events. Deleting a child never affects its parent.
	 */
	void delete(String sessionId);

	/**
	 * Deletes all sessions whose {@code expiresAt} is before {@code before}. Delegates
	 * to {@link SessionRepository#deleteExpiredSessions(Instant)}, which does not delete a
	 * session whose TTL was extended concurrently. Returns the number of sessions deleted.
	 * <p>
	 * This method does not schedule itself — call it from a {@code @Scheduled} method,
	 * a Quartz job, or any other scheduler:
	 * <pre>{@code
	 * @Scheduled(fixedRate = 3_600_000) // every hour
	 * void sweepExpiredSessions() {
	 *     sessionService.deleteExpiredSessions(Instant.now());
	 * }
	 * }</pre>
	 */
	int deleteExpiredSessions(Instant before);

	// Events

	/**
	 * Appends a {@link SessionEvent} to the session identified by
	 * {@link SessionEvent#getSessionId()}.
	 * @throws IllegalStateException if the session is archived
	 * @throws IllegalArgumentException if the event wraps a
	 * {@link org.springframework.ai.chat.messages.SystemMessage} and the service does not
	 * allow storing system messages (the default for {@link DefaultSessionService}; see
	 * {@link DefaultSessionService.Builder#allowSystemMessages(boolean)})
	 */
	void appendEvent(SessionEvent event);

	/**
	 * Convenience shorthand: wraps the message in a {@link SessionEvent} and appends it.
	 * Subject to the same system-message rule as {@link #appendEvent(SessionEvent)}.
	 */
	default void appendMessage(String sessionId, Message message) {
		appendEvent(SessionEvent.builder().sessionId(sessionId).message(message).build());
	}

	/** Returns events matching the given filter, in chronological order. */
	List<SessionEvent> getEvents(String sessionId, EventFilter filter);

	/** Returns all events for the session, in chronological order. */
	default List<SessionEvent> getEvents(String sessionId) {
		return getEvents(sessionId, EventFilter.all());
	}

	/**
	 * Returns the matching events of the given sessions, ordered by timestamp across
	 * sessions, with the filter's window (last-N or page) applied to that combined order.
	 * Backed by {@link SessionRepository#findEvents(Collection, EventFilter)}, which stores
	 * push down to one sorted, paged query. The sessions of a principal come from
	 * {@link #findByIndex} or a {@link SessionSelector}.
	 */
	List<SessionEvent> findEvents(Collection<String> sessionIds, EventFilter filter);

	/**
	 * Convenience: returns the messages of <em>all</em> events as a flat {@link Message}
	 * list, including the events a compaction covered and any synthetic summaries. This is
	 * the full recorded history, not a prompt: after compaction it contains both the
	 * verbatim covered turns and their summary. Events that are not messages (status,
	 * usage and the like) are skipped. Use {@link #getActiveMessages(String)} for the
	 * context window to send to an LLM.
	 */
	default List<Message> getMessages(String sessionId) {
		return getEvents(sessionId).stream().filter(SessionEvent::hasMessage).map(SessionEvent::getMessage).toList();
	}

	/**
	 * Convenience: returns the messages of the <em>active</em> context window — the events
	 * a compaction covered are excluded, synthetic summaries are included — as a flat
	 * {@link Message} list, suitable for passing directly to an LLM. Equivalent to
	 * {@code getEvents(sessionId, EventFilter.active())} mapped to messages.
	 */
	default List<Message> getActiveMessages(String sessionId) {
		return getEvents(sessionId, EventFilter.active()).stream().map(SessionEvent::getMessage).toList();
	}

	// Compaction

	/**
	 * Evaluates the trigger and, if it fires, compacts the session's active view using the
	 * given strategy. Reads the view once, builds a
	 * {@link org.springframework.ai.session.compaction.CompactionRequest}, checks the
	 * trigger, and — only if the trigger fires — runs the strategy and records its
	 * decision by appending a {@link EventType#COMPACTION} boundary event followed by the
	 * summary events. The log is never changed: the events the compaction covers stay
	 * where they are and leave the active view, which is projected from the newest
	 * boundary. No-ops (trigger does not fire, or the strategy covers nothing) skip the
	 * repository write entirely.
	 * <p>
	 * Pass {@code (req) -> true} as the trigger to compact unconditionally.
	 * @return the compaction result;
	 * {@link org.springframework.ai.session.compaction.CompactionResult#isUnchanged()}
	 * when the trigger did not fire, the strategy covered nothing, or a concurrent writer
	 * changed the log first
	 */
	CompactionResult compact(String sessionId, CompactionTrigger trigger, CompactionStrategy strategy);

}
