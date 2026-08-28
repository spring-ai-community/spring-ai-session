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

package org.springframework.ai.session.compaction;

import java.util.List;

import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.util.Assert;

/**
 * Contextual information provided to {@link CompactionTrigger} and
 * {@link CompactionStrategy} when evaluating whether compaction is needed.
 *
 * @param session the session being evaluated for compaction
 * @param events the session's current list of events, ordered from oldest to newest
 * @param currentEventCount total number of events in the session
 * @param currentTurnCount total number of turns in the session (a turn is a user message
 * plus all subsequent events up to the next user message)
 * @param scope what this compaction pass may archive/rewrite and what counts as a turn
 * boundary; {@link CompactionScope#session()} reproduces pre-scope behaviour exactly
 * @author Christian Tzolov
 * @since 2.0.0
 */
public record CompactionRequest(Session session, List<SessionEvent> events, int currentEventCount,
		int currentTurnCount, CompactionScope scope) {

	/**
	 * Creates a whole-session-scoped {@code CompactionRequest} from the given session and
	 * its event list. Equivalent to {@code of(session, events, CompactionScope.session())}.
	 */
	public static CompactionRequest of(Session session, List<SessionEvent> events) {
		return of(session, events, CompactionScope.session());
	}

	/**
	 * Creates a {@code CompactionRequest} scoped to the given {@link CompactionScope}.
	 * <p>
	 * {@code currentTurnCount} counts only non-synthetic USER messages that are turn
	 * boundaries under {@code scope} (see {@link CompactionScope#isTurnBoundary(SessionEvent)}).
	 * For session scope this is exactly the original root-only count: sub-agents write
	 * USER messages attributed to their own branch, and counting those would inflate the
	 * root turn count and cause premature compaction of the root conversation. For branch
	 * scope, the equivalent rule applies one level down — only USER messages on exactly
	 * that branch count; USER messages on sub-branches are turn-internal.
	 */
	public static CompactionRequest of(Session session, List<SessionEvent> events, CompactionScope scope) {
		Assert.notNull(session, "session must not be null");
		Assert.notNull(events, "events must not be null");
		Assert.notNull(scope, "scope must not be null");
		int eventCount = events.size();
		int turnCount = (int) events.stream()
			.filter(e -> !e.isSynthetic())
			.filter(e -> e.getMessageType() == MessageType.USER)
			.filter(scope::isTurnBoundary)
			.count();
		return new CompactionRequest(session, events, eventCount, turnCount, scope);
	}

}
