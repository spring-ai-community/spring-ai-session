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

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.compaction.CompactionRequest;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.CompactionStrategy;
import org.springframework.ai.session.compaction.CompactionTrigger;
import org.springframework.util.Assert;

/**
 * Default implementation of {@link SessionService} backed by a {@link SessionRepository}.
 *
 * @author Christian Tzolov
 * @since 2.0.0
 */
public class DefaultSessionService implements SessionService {

	private final SessionRepository sessionRepository;

	public DefaultSessionService(SessionRepository sessionRepository) {
		Assert.notNull(sessionRepository, "sessionRepository must not be null");
		this.sessionRepository = sessionRepository;
	}

	@Override
	public Session create(CreateSessionRequest request) {
		Assert.notNull(request, "request must not be null");
		Instant now = Instant.now();
		Instant expiresAt = (request.timeToLive() != null) ? now.plus(request.timeToLive())
				: now.plus(Duration.ofDays(60));
		String sessionId = (request.id() != null && !request.id().isBlank()) ? request.id()
				: UUID.randomUUID().toString();
		Session session = Session.builder()
			.id(sessionId)
			.userId(request.userId())
			.createdAt(now)
			.expiresAt(expiresAt)
			.metadata(new HashMap<>(request.metadata()))
			.build();
		return this.sessionRepository.save(session);
	}

	@Override
	@Nullable public Session findById(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");

		return this.sessionRepository.findById(sessionId).orElse(null);
	}

	@Override
	public List<Session> findByUserId(String userId) {
		Assert.hasText(userId, "userId must not be null or empty");
		return this.sessionRepository.findByUserId(userId);
	}

	@Override
	public void delete(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		this.sessionRepository.delete(sessionId);
	}

	@Override
	public void appendEvent(SessionEvent event) {
		Assert.notNull(event, "event must not be null");
		this.sessionRepository.appendEvent(event);
	}

	@Override
	public List<SessionEvent> getEvents(String sessionId, EventFilter filter) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		Assert.notNull(filter, "filter must not be null");
		return this.sessionRepository.findEvents(sessionId, filter);
	}

	@Override
	public CompactionResult compact(String sessionId, CompactionTrigger trigger, CompactionStrategy strategy) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		Assert.notNull(trigger, "trigger must not be null");
		Assert.notNull(strategy, "strategy must not be null");

		Session session = this.sessionRepository.findById(sessionId)
			.orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

		return compactWith(session, trigger, strategy);
	}

	/**
	 * Core compaction logic shared by both {@code compact} overloads. Skips the
	 * {@code findById} round-trip — the caller must supply a valid {@link Session}.
	 */
	private CompactionResult compactWith(Session session, CompactionTrigger trigger, CompactionStrategy strategy) {
		// Read version BEFORE events so the version we pass to the CAS write is
		// guaranteed to be ≤ the version of the events we subsequently read. If another
		// writer (append or compaction) mutates the log between our read and our write,
		// the CAS will detect the version mismatch and return false — we skip silently,
		// as the concurrent writer already handled the session.
		long version = this.sessionRepository.getEventVersion(session.id());
		List<SessionEvent> events = this.sessionRepository.findEvents(session.id(), EventFilter.all());
		CompactionRequest request = CompactionRequest.of(session, events);

		if (!trigger.shouldCompact(request)) {
			return new CompactionResult(events, List.of(), 0);
		}

		CompactionResult result = strategy.compact(request);

		if (!result.archivedEvents().isEmpty()) {
			this.sessionRepository.replaceEvents(session.id(), result.compactedEvents(), version);
		}

		return result;
	}

}
