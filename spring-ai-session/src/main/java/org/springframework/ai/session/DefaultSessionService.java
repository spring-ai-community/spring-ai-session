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

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.session.compaction.CompactionRequest;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.CompactionStrategy;
import org.springframework.ai.session.compaction.CompactionTrigger;
import org.springframework.util.Assert;

/**
 * Default implementation of {@link SessionService} backed by a {@link SessionRepository}.
 *
 * @author Christian Tzolov
 * @since 0.1.0
 */
public class DefaultSessionService implements SessionService {

	private final Duration defaultTimeToLive;

	private final SessionRepository sessionRepository;

	private final boolean allowSystemMessages;

	private final SessionScopeResolver scopeResolver;

	private final int maxTreeDepth;

	private final Set<String> inheritedAttributes;

	private DefaultSessionService(SessionRepository sessionRepository, Duration defaultTimeToLive,
			boolean allowSystemMessages, SessionScopeResolver scopeResolver, int maxTreeDepth,
			Set<String> inheritedAttributes) {
		Assert.notNull(sessionRepository, "sessionRepository must not be null");
		Assert.notNull(defaultTimeToLive, "defaultTimeToLive must not be null");
		Assert.notNull(scopeResolver, "scopeResolver must not be null");
		Assert.isTrue(maxTreeDepth >= 0, "maxTreeDepth must not be negative");
		this.sessionRepository = sessionRepository;
		this.defaultTimeToLive = defaultTimeToLive;
		this.allowSystemMessages = allowSystemMessages;
		this.scopeResolver = scopeResolver;
		this.maxTreeDepth = maxTreeDepth;
		this.inheritedAttributes = Set.copyOf(inheritedAttributes);
	}

	@Override
	public Session create(CreateSessionRequest request) {
		Assert.notNull(request, "request must not be null");

		Instant now = Instant.now();
		Instant expiresAt = (request.timeToLive() != null) ? now.plus(request.timeToLive())
				: now.plus(this.defaultTimeToLive);

		String sessionId = (request.id() != null && !request.id().isBlank()) ? request.id()
				: UUID.randomUUID().toString();

		// The caller's scope (principal, tenant) fills the attributes the request left out;
		// an explicit request attribute wins, and an authorization layer in front of this
		// service decides whether the caller may set it.
		Map<String, Object> metadata = new HashMap<>(request.metadata());
		this.scopeResolver.resolve().forEach(metadata::putIfAbsent);
		Session session = Session.builder()
			.id(sessionId)
			.createdAt(now)
			.expiresAt(expiresAt)
			.metadata(metadata)
			.build();

		// Insert-only and atomic: never silently overwrite an existing session (an upsert
		// would reassign its owner and TTL while keeping its event log), even when two
		// callers create the same id concurrently.
		if (!this.sessionRepository.saveIfAbsent(session)) {
			throw new IllegalStateException("Session already exists: " + sessionId);
		}
		return session;
	}

	@Override
	@Nullable public Session findById(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");

		return this.sessionRepository.findById(sessionId);
	}

	@Override
	public List<Session> findByIndex(String name, String value) {
		Assert.hasText(name, "name must not be null or empty");
		Assert.notNull(value, "value must not be null");
		return this.sessionRepository.findByIndex(name, value);
	}

	@Override
	public List<Session> findChildren(String parentSessionId) {
		Assert.hasText(parentSessionId, "parentSessionId must not be null or empty");
		return this.sessionRepository.findByParentSessionId(parentSessionId);
	}

	@Override
	public List<Session> findTree(String rootSessionId) {
		Assert.hasText(rootSessionId, "rootSessionId must not be null or empty");
		return this.sessionRepository.findByRootSessionId(rootSessionId);
	}

	@Override
	public Session createChild(String parentSessionId, CreateSessionRequest request) {
		Assert.hasText(parentSessionId, "parentSessionId must not be null or empty");
		Assert.notNull(request, "request must not be null");
		Session parent = this.sessionRepository.findById(parentSessionId);
		if (parent == null) {
			throw new IllegalArgumentException("Session not found: " + parentSessionId);
		}
		requireNotArchived(parent);
		if (request.timeToLive() != null) {
			throw new IllegalArgumentException(
					"A child inherits the expiry of its parent; timeToLive must not be set");
		}
		int depth = depthOf(parent) + 1;
		if (depth > this.maxTreeDepth) {
			throw new IllegalArgumentException("Session '" + parentSessionId + "' is at depth " + (depth - 1)
					+ "; children may not go deeper than " + this.maxTreeDepth);
		}

		// Inherited attributes come from the parent and may not diverge: the request may
		// repeat the parent's value, not set another one, and not set one the parent lacks
		Map<String, Object> metadata = new HashMap<>(request.metadata());
		for (String key : this.inheritedAttributes) {
			Object inherited = parent.metadata().get(key);
			Object requested = metadata.get(key);
			if (inherited == null) {
				if (requested != null) {
					throw new IllegalArgumentException("A child inherits '" + key + "' from its parent, which has none;"
							+ " the request may not set it");
				}
				continue;
			}
			if (requested != null && !String.valueOf(requested).equals(String.valueOf(inherited))) {
				throw new IllegalArgumentException("A child inherits '" + key + "' from its parent; '" + requested
						+ "' differs from '" + inherited + "'");
			}
			metadata.put(key, inherited);
		}
		this.scopeResolver.resolve().forEach(metadata::putIfAbsent);

		String childId = (request.id() != null && !request.id().isBlank()) ? request.id()
				: UUID.randomUUID().toString();
		Session child = Session.builder()
			.id(childId)
			.createdAt(Instant.now())
			.expiresAt(parent.expiresAt())
			.parentSessionId(parent.id())
			.rootSessionId(parent.rootSessionId())
			.metadata(metadata)
			.build();
		if (!this.sessionRepository.saveIfAbsent(child)) {
			throw new IllegalStateException("Session already exists: " + childId);
		}
		this.sessionRepository.appendEvent(SessionTreeEvents.childCreated(child));
		return child;
	}

	/** The depth of a session in its tree: 0 for a root, 1 for its children, and so on. */
	private int depthOf(Session session) {
		int depth = 0;
		Session current = session;
		while (current != null && current.isChild()) {
			depth++;
			if (depth > this.maxTreeDepth) {
				break;
			}
			String parentId = current.parentSessionId();
			current = (parentId != null) ? this.sessionRepository.findById(parentId) : null;
		}
		return depth;
	}

	@Override
	public Session startTurn(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		if (!this.sessionRepository.updateStatus(sessionId, SessionStatus.IDLE, SessionStatus.RUNNING, null, null)) {
			Session session = requireSession(sessionId);
			requireNotArchived(session);
			throw new IllegalStateException("Session '" + sessionId + "' could not start a turn: it is "
					+ session.status() + "; a turn that was abandoned is ended with endTurn(id, StopReason.INTERRUPTED)");
		}
		this.sessionRepository.appendEvent(SessionStatusEvents.running(sessionId));
		return requireSession(sessionId);
	}

	@Override
	public Session endTurn(String sessionId, StopReason stopReason, List<String> eventIds) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		Assert.notNull(stopReason, "stopReason must not be null");
		Assert.notNull(eventIds, "eventIds must not be null");
		if (!this.sessionRepository.updateStatus(sessionId, SessionStatus.RUNNING, SessionStatus.IDLE, stopReason,
				null)) {
			Session session = requireSession(sessionId);
			throw new IllegalStateException("Session '" + sessionId + "' is not running a turn; it is " + session.status());
		}
		this.sessionRepository.appendEvent(SessionStatusEvents.idle(sessionId, stopReason, eventIds));
		return requireSession(sessionId);
	}

	@Override
	public TokenUsage usage(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		requireSession(sessionId);
		return sumUsage(List.of(sessionId));
	}

	@Override
	public TokenUsage aggregateUsage(String rootSessionId) {
		Assert.hasText(rootSessionId, "rootSessionId must not be null or empty");
		Session root = requireSession(rootSessionId);
		List<String> ids = new ArrayList<>();
		ids.add(root.id());
		this.sessionRepository.findByRootSessionId(rootSessionId)
			.stream()
			.map(Session::id)
			.filter(id -> !id.equals(root.id()))
			.forEach(ids::add);
		return sumUsage(ids);
	}

	private TokenUsage sumUsage(List<String> sessionIds) {
		EventFilter usageEvents = EventFilter.builder().eventTypes(Set.of(EventType.USAGE)).build();
		TokenUsage sum = TokenUsage.ZERO;
		for (SessionEvent event : this.sessionRepository.findEvents(sessionIds, usageEvents)) {
			TokenUsage usage = UsageEvents.usage(event);
			if (usage != null) {
				sum = sum.plus(usage);
			}
		}
		return sum;
	}

	@Override
	public SessionStatus aggregateStatus(String rootSessionId) {
		Assert.hasText(rootSessionId, "rootSessionId must not be null or empty");
		Session root = requireSession(rootSessionId);
		boolean running = this.sessionRepository.findByRootSessionId(rootSessionId)
			.stream()
			.anyMatch(s -> s.status() == SessionStatus.RUNNING);
		return running ? SessionStatus.RUNNING : root.status();
	}

	@Override
	public Session archive(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		Session session = requireSession(sessionId);
		if (session.isArchived()) {
			return session;
		}
		// The whole subtree must be idle before anything changes; then children go first,
		// so a failure part-way leaves the parent open rather than orphaning live children
		List<Session> children = findChildren(sessionId);
		if (session.status() == SessionStatus.RUNNING || children.stream().anyMatch(this::isRunningTree)) {
			throw new IllegalStateException(
					"Session '" + sessionId + "' or one of its children is running a turn; interrupt or wait first");
		}
		for (Session child : children) {
			archive(child.id());
		}
		if (this.sessionRepository.updateStatus(sessionId, SessionStatus.IDLE, SessionStatus.TERMINATED, null,
				Instant.now())) {
			this.sessionRepository.appendEvent(SessionStatusEvents.terminated(sessionId));
			return requireSession(sessionId);
		}
		// The compare-and-set lost against a turn that started in between, or another archive
		Session current = requireSession(sessionId);
		if (current.isArchived()) {
			return current;
		}
		throw new IllegalStateException("Session '" + sessionId + "' started a turn while being archived; retry");
	}

	private boolean isRunningTree(Session session) {
		return session.status() == SessionStatus.RUNNING || findChildren(session.id()).stream().anyMatch(this::isRunningTree);
	}

	private Session requireSession(String sessionId) {
		Session session = this.sessionRepository.findById(sessionId);
		if (session == null) {
			throw new IllegalArgumentException("Session not found: " + sessionId);
		}
		return session;
	}

	private static void requireNotArchived(Session session) {
		if (session.isArchived()) {
			throw new IllegalStateException("Session '" + session.id() + "' is archived and accepts no more events");
		}
	}

	@Override
	public void delete(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		// Children go with their parent, never the other way round
		for (Session child : findChildren(sessionId)) {
			delete(child.id());
		}
		this.sessionRepository.delete(sessionId);
	}

	@Override
	public int deleteExpiredSessions(Instant before) {
		Assert.notNull(before, "before must not be null");
		return this.sessionRepository.deleteExpiredSessions(before);
	}

	@Override
	public void appendEvent(SessionEvent event) {
		Assert.notNull(event, "event must not be null");
		Session session = this.sessionRepository.findById(event.getSessionId());
		if (session != null) {
			// An unknown session is left to the repository, which reports it
			requireNotArchived(session);
		}
		if (!this.allowSystemMessages && event.hasMessage() && event.getMessageType() == MessageType.SYSTEM) {
			throw new IllegalArgumentException("Storing a SystemMessage in session '" + event.getSessionId()
					+ "' is disabled. System prompts are configuration: supply them on every request "
					+ "(e.g. ChatClient defaultSystem/.system, or your own prompt builder) and keep per-session "
					+ "settings in Session.metadata. To store system messages anyway, use "
					+ "DefaultSessionService.builder().allowSystemMessages(true) or set "
					+ "spring.ai.session.allow-system-messages=true. See the \"System Messages\" reference page.");
		}
		this.sessionRepository.appendEvent(event);
	}

	@Override
	public List<SessionEvent> findEvents(Collection<String> sessionIds, EventFilter filter) {
		Assert.notNull(sessionIds, "sessionIds must not be null");
		Assert.notNull(filter, "filter must not be null");
		return this.sessionRepository.findEvents(sessionIds, filter);
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

		Session session = requireSession(sessionId);
		requireNotArchived(session);

		// Read version BEFORE events so the version we pass to the CAS write is
		// guaranteed to be ≤ the version of the events we subsequently read. If another
		// writer (append or compaction) changes the log between our read and our write,
		// the CAS will detect the version mismatch and return false — we skip silently,
		// as the concurrent writer already handled the session.
		long version = this.sessionRepository.getEventVersion(session.id());

		// Operate on the active view only — the events an earlier compaction covered are
		// retained for Recall Storage and must not be re-processed (or re-summarized).
		List<SessionEvent> view = this.sessionRepository.findEvents(session.id(), EventFilter.active());

		CompactionRequest request = CompactionRequest.of(session, view);

		if (!trigger.shouldCompact(request)) {
			return CompactionResult.unchanged();
		}

		CompactionResult result = strategy.compact(request);
		if (result.isUnchanged()) {
			return result;
		}

		// Record the decision as an appended boundary followed by its summary events. The
		// log is never changed: the boundary names, by id, what the view is from now on.
		if (!this.sessionRepository.appendEvents(session.id(), result.toEvents(session.id()), version)) {
			// CAS rejected — a concurrent writer already changed the log; skip silently.
			return CompactionResult.unchanged();
		}
		return result;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private SessionRepository sessionRepository;

		private Duration defaultTimeToLive = Duration.ofDays(60);

		private boolean allowSystemMessages = false;

		private SessionScopeResolver scopeResolver = SessionScopeResolver.none();

		private int maxTreeDepth = 1;

		private Set<String> inheritedAttributes = Set.of(SessionAttributes.PRINCIPAL_NAME);

		public Builder sessionRepository(SessionRepository sessionRepository) {
			Assert.notNull(sessionRepository, "sessionRepository must not be null");
			this.sessionRepository = sessionRepository;
			return this;
		}

		/**
		 * Whether {@link #appendEvent(SessionEvent)} may store a
		 * {@link org.springframework.ai.chat.messages.SystemMessage}. Defaults to
		 * {@code false}: system prompts are configuration, best supplied on every request
		 * rather than stored in the session, so storing one is rejected with an
		 * {@link IllegalArgumentException} that explains how to enable it. Set to
		 * {@code true} to store system messages anyway. The latest stored system message
		 * is then the system prompt; see
		 * {@link org.springframework.ai.session.compaction.CompactionStrategy}
		 * implementations.
		 */
		public Builder allowSystemMessages(boolean allowSystemMessages) {
			this.allowSystemMessages = allowSystemMessages;
			return this;
		}

		public Builder defaultTimeToLive(Duration defaultTimeToLive) {
			Assert.notNull(defaultTimeToLive, "defaultTimeToLive must not be null");
			this.defaultTimeToLive = defaultTimeToLive;
			return this;
		}

		/**
		 * The resolver of the caller's scope attributes, merged into the metadata of every
		 * created session for the keys the request did not set. Defaults to
		 * {@link SessionScopeResolver#none()}; the Spring Security auto-configuration sets
		 * one that stores the principal name.
		 */
		public Builder scopeResolver(SessionScopeResolver scopeResolver) {
			Assert.notNull(scopeResolver, "scopeResolver must not be null");
			this.scopeResolver = scopeResolver;
			return this;
		}

		/**
		 * How deep a session tree may go: {@code 1} (the default) allows children of a root
		 * session and no children of children, like Claude Managed Agents; {@code 0} disables
		 * {@link #createChild}.
		 */
		public Builder maxTreeDepth(int maxTreeDepth) {
			Assert.isTrue(maxTreeDepth >= 0, "maxTreeDepth must not be negative");
			this.maxTreeDepth = maxTreeDepth;
			return this;
		}

		/**
		 * The metadata keys a child takes from its parent and may not diverge from.
		 * Defaults to {@link SessionAttributes#PRINCIPAL_NAME}, which is always included;
		 * add a tenant key or the like.
		 */
		public Builder inheritedAttributes(Set<String> inheritedAttributes) {
			Assert.notNull(inheritedAttributes, "inheritedAttributes must not be null");
			Set<String> keys = new HashSet<>(inheritedAttributes);
			keys.add(SessionAttributes.PRINCIPAL_NAME);
			this.inheritedAttributes = keys;
			return this;
		}

		public DefaultSessionService build() {
			return new DefaultSessionService(this.sessionRepository, this.defaultTimeToLive, this.allowSystemMessages,
					this.scopeResolver, this.maxTreeDepth, this.inheritedAttributes);
		}

	}

}
