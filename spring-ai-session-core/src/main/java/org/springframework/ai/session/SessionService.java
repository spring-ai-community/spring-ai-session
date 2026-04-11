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
 * @since 2.0.0
 */
public interface SessionService {

	// Sessions

	Session create(CreateSessionRequest request);

	@Nullable Session findById(String sessionId);

	List<Session> findByUserId(String userId);

	void delete(String sessionId);

	// Events

	/**
	 * Appends a {@link SessionEvent} to the session identified by
	 * {@link SessionEvent#getSessionId()}.
	 */
	void appendEvent(SessionEvent event);

	/**
	 * Convenience shorthand: wraps the message in a {@link SessionEvent} and appends it.
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
	 * Convenience: returns all events as a flat {@link Message} list, suitable for
	 * passing directly to an LLM.
	 */
	default List<Message> getMessages(String sessionId) {
		return getEvents(sessionId).stream().map(SessionEvent::getMessage).toList();
	}

	// Compaction

	/**
	 * Evaluates the trigger and, if it fires, compacts the session's event history using
	 * the given strategy. Fetches the event list once, builds a
	 * {@link org.springframework.ai.session.compaction.CompactionRequest}, checks the
	 * trigger, and — only if the trigger fires — runs the strategy and writes the
	 * compacted list back to the repository. No-ops (trigger does not fire, or strategy
	 * archives nothing) skip the repository write entirely.
	 * <p>
	 * Pass {@code (req) -> true} as the trigger to compact unconditionally.
	 * @return the compaction result;
	 * {@link org.springframework.ai.session.compaction.CompactionResult#archivedEvents()}
	 * is empty when the trigger did not fire or when the strategy archived nothing
	 */
	CompactionResult compact(String sessionId, CompactionTrigger trigger, CompactionStrategy strategy);

}
