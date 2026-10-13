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

package org.springframework.ai.session.security;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.SessionStatus;
import org.springframework.ai.session.StopReason;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.CompactionStrategy;
import org.springframework.ai.session.compaction.CompactionTrigger;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.util.Assert;

/**
 * A {@link SessionService} that asks an {@link AuthorizationManager} before every call
 * that touches a session, with the caller taken from the security context.
 *
 * <p>
 * Reads, appends, compaction, turns, archive, delete and the creation of a child are
 * allowed only when the manager grants the caller that session (the parent, for a child;
 * the root, for an aggregate status); otherwise an
 * {@link AccessDeniedException} is thrown.
 * Listings ({@link #findByIndex}, {@link #findChildren}, {@link #findTree}) and
 * cross-session reads ({@link #findEvents(Collection, EventFilter)}) are narrowed to the
 * sessions the caller is granted. A
 * {@link #create(CreateSessionRequest) create} whose request names a principal is checked
 * as if that session existed, so a caller cannot create a session for someone else unless
 * the manager allows it; a request without one gets the caller's own scope from the
 * delegate's {@code SessionScopeResolver}. An unknown session id is handed to the delegate
 * unchecked, so its own not-found behaviour is kept; a known session the caller is not
 * granted is reported as denied rather than as not found, which follows Spring Security's
 * convention and reveals that the id exists.
 *
 * <p>
 * {@link #deleteExpiredSessions} is maintenance and is not checked per session; protect
 * the caller with method security if needed.
 *
 * @author Christian Tzolov
 * @since 0.11.0
 */
public class AuthorizingSessionService implements SessionService {

	private final SessionService delegate;

	private final AuthorizationManager<Session> authorizationManager;

	private final SecurityContextHolderStrategy securityContextHolderStrategy;

	public AuthorizingSessionService(SessionService delegate, AuthorizationManager<Session> authorizationManager) {
		this(delegate, authorizationManager, SecurityContextHolder.getContextHolderStrategy());
	}

	public AuthorizingSessionService(SessionService delegate, AuthorizationManager<Session> authorizationManager,
			SecurityContextHolderStrategy securityContextHolderStrategy) {
		Assert.notNull(delegate, "delegate must not be null");
		Assert.notNull(authorizationManager, "authorizationManager must not be null");
		Assert.notNull(securityContextHolderStrategy, "securityContextHolderStrategy must not be null");
		this.delegate = delegate;
		this.authorizationManager = authorizationManager;
		this.securityContextHolderStrategy = securityContextHolderStrategy;
	}

	/** The service this one guards. */
	public SessionService getDelegate() {
		return this.delegate;
	}

	@Override
	public Session create(CreateSessionRequest request) {
		Assert.notNull(request, "request must not be null");
		if (request.metadata().containsKey(SessionAttributes.PRINCIPAL_NAME)) {
			String id = (request.id() != null && !request.id().isBlank()) ? request.id() : "new";
			authorize(Session.builder().id(id).metadata(request.metadata()).build());
		}
		return this.delegate.create(request);
	}

	@Override
	public @Nullable Session findById(String sessionId) {
		return authorized(sessionId);
	}

	@Override
	public List<Session> findByIndex(String name, String value) {
		return this.delegate.findByIndex(name, value).stream().filter(this::isGranted).toList();
	}

	@Override
	public List<Session> findChildren(String parentSessionId) {
		return this.delegate.findChildren(parentSessionId).stream().filter(this::isGranted).toList();
	}

	@Override
	public List<Session> findTree(String rootSessionId) {
		return this.delegate.findTree(rootSessionId).stream().filter(this::isGranted).toList();
	}

	@Override
	public Session createChild(String parentSessionId, CreateSessionRequest request) {
		Assert.notNull(request, "request must not be null");
		// The child inherits the parent's principal, so being granted the parent is the
		// check; the delegate rejects a request that tries to diverge from it.
		authorized(parentSessionId);
		return this.delegate.createChild(parentSessionId, request);
	}

	@Override
	public void delete(String sessionId) {
		authorized(sessionId);
		this.delegate.delete(sessionId);
	}

	@Override
	public int deleteExpiredSessions(Instant before) {
		return this.delegate.deleteExpiredSessions(before);
	}

	@Override
	public void appendEvent(SessionEvent event) {
		Assert.notNull(event, "event must not be null");
		authorized(event.getSessionId());
		this.delegate.appendEvent(event);
	}

	@Override
	public List<SessionEvent> getEvents(String sessionId, EventFilter filter) {
		authorized(sessionId);
		return this.delegate.getEvents(sessionId, filter);
	}

	@Override
	public List<SessionEvent> findEvents(Collection<String> sessionIds, EventFilter filter) {
		Assert.notNull(sessionIds, "sessionIds must not be null");
		// One lookup per id: the ids come from a selector, not from a listing this service
		// already filtered. Fine for a recall page; a very large selection pays for it.
		List<String> granted = sessionIds.stream().distinct().filter(id -> {
			Session session = this.delegate.findById(id);
			return session != null && isGranted(session);
		}).toList();
		return granted.isEmpty() ? List.of() : this.delegate.findEvents(granted, filter);
	}

	@Override
	public CompactionResult compact(String sessionId, CompactionTrigger trigger, CompactionStrategy strategy) {
		authorized(sessionId);
		return this.delegate.compact(sessionId, trigger, strategy);
	}

	@Override
	public Session startTurn(String sessionId) {
		authorized(sessionId);
		return this.delegate.startTurn(sessionId);
	}

	@Override
	public Session endTurn(String sessionId, StopReason stopReason, List<String> eventIds) {
		authorized(sessionId);
		return this.delegate.endTurn(sessionId, stopReason, eventIds);
	}

	@Override
	public SessionStatus aggregateStatus(String rootSessionId) {
		authorized(rootSessionId);
		return this.delegate.aggregateStatus(rootSessionId);
	}

	@Override
	public Session archive(String sessionId) {
		authorized(sessionId);
		return this.delegate.archive(sessionId);
	}

	private @Nullable Session authorized(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		Session session = this.delegate.findById(sessionId);
		if (session != null) {
			authorize(session);
		}
		return session;
	}

	private void authorize(Session session) {
		if (!isGranted(session)) {
			throw new AccessDeniedException("Access denied to session '" + session.id() + "'");
		}
	}

	private boolean isGranted(Session session) {
		Supplier<@Nullable Authentication> authentication = () -> this.securityContextHolderStrategy.getContext()
			.getAuthentication();
		AuthorizationResult result = this.authorizationManager.authorize(authentication, session);
		return result != null && result.isGranted();
	}

}
