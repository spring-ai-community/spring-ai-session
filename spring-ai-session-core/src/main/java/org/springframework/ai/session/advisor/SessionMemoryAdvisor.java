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

package org.springframework.ai.session.advisor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.compaction.CompactionStrategy;
import org.springframework.ai.session.compaction.CompactionTrigger;
import org.springframework.util.Assert;

/**
 * A {@link BaseAdvisor} that manages conversation history using the
 * {@link SessionService}, with optional context compaction.
 *
 * <p>
 * On each interaction:
 * <ol>
 * <li>Retrieves the session's event history and prepends it to the prompt messages.</li>
 * <li>Appends the current user message to the session.</li>
 * <li>After the model responds, appends the assistant message to the session.</li>
 * <li>Optionally triggers context compaction if the configured trigger fires.</li>
 * </ol>
 *
 * <p>
 * The session is identified by the {@link #SESSION_ID_CONTEXT_KEY} value in the advisor
 * context, falling back to {@code defaultSessionId}.
 *
 * <p>
 * <strong>Concurrent compaction safety:</strong> If two requests for the same session
 * complete concurrently, both {@code after()} calls may reach the compaction step
 * simultaneously. Compaction uses an optimistic compare-and-swap write via
 * {@link org.springframework.ai.session.SessionRepository#replaceEvents(String, java.util.List, long)},
 * so only the first writer succeeds; the second detects the version mismatch and skips
 * silently. No compaction result is lost or corrupted.
 *
 * @author Christian Tzolov
 * @since 2.0.0
 */
public final class SessionMemoryAdvisor implements BaseAdvisor {

	/**
	 * Context key used to pass the session ID into the advisor per-request. Set via:
	 * {@code .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, "my-session-id"))}
	 */
	public static final String SESSION_ID_CONTEXT_KEY = "chat_memory_session_id";

	/**
	 * Context key used to pass the user ID into the advisor per-request. Set via:
	 * {@code .advisors(a -> a.param(SessionMemoryAdvisor.USER_ID_CONTEXT_KEY, "my-user-id"))}
	 */
	public static final String USER_ID_CONTEXT_KEY = "chat_memory_user_id";

	public static final String EVENT_FILTER_CONTEXT_KEY = "chat_memory_event_filter_id";

	private final SessionService sessionService;

	private final String defaultSessionId;

	private final String defaultUserId;

	private final int order;

	private final Scheduler scheduler;

	private final EventFilter eventFilter;

	@Nullable private final CompactionTrigger compactionTrigger;

	@Nullable private final CompactionStrategy compactionStrategy;

	private SessionMemoryAdvisor(SessionService sessionService, String defaultSessionId, String defaultUserId,
			int order, Scheduler scheduler, EventFilter eventFilter, @Nullable CompactionTrigger compactionTrigger,
			@Nullable CompactionStrategy compactionStrategy) {
		this.sessionService = sessionService;
		this.defaultSessionId = defaultSessionId;
		this.defaultUserId = defaultUserId;
		this.order = order;
		this.scheduler = scheduler;
		this.eventFilter = eventFilter;
		this.compactionTrigger = compactionTrigger;
		this.compactionStrategy = compactionStrategy;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	@Override
	public Scheduler getScheduler() {
		return this.scheduler;
	}

	@Override
	public ChatClientRequest before(ChatClientRequest request, AdvisorChain advisorChain) {

		// 0. Determine the session ID for this request, either from the context or
		// falling back to the default.
		String sessionId = getSessionId(request.context());

		// 1. Find or create the session. The Session object is cached in the request
		// context so that after() can reuse it and skip a redundant findById()
		// repository round-trip when compaction is configured.
		Session session = this.sessionService.findById(sessionId);
		if (session == null) {
			String userId = getUserId(request.context());
			session = this.sessionService.create(CreateSessionRequest.builder().id(sessionId).userId(userId).build());
		}

		// 2. Retrieve history applying the configured filter (default: all events)

		// If the request context contains an EventFilter, merge it with the advisor's
		// configured filter so that request-level parameters override the advisor
		// defaults
		EventFilter eventFilter = this.eventFilter;
		if (request.context().containsKey(EVENT_FILTER_CONTEXT_KEY)) {
			EventFilter requestEventFilter = (EventFilter) request.context().get(EVENT_FILTER_CONTEXT_KEY);
			if (requestEventFilter != null) {
				eventFilter = this.eventFilter.merge(requestEventFilter);
			}
		}

		List<SessionEvent> events = this.sessionService.getEvents(sessionId, eventFilter);
		List<Message> history = events.stream().map(SessionEvent::getMessage).toList();

		List<Message> combined = new ArrayList<>(history);
		combined.addAll(request.prompt().getInstructions());

		// 3. Ensure all system messages appear first (preserving their relative order).
		// A single pass collects every SystemMessage, removes them in place, then
		// prepends them as a block — so a system message buried in history and a
		// second one on the current request both end up at the front rather than
		// leaving the second one stranded mid-list.
		List<Message> systemMessages = combined.stream().filter(SystemMessage.class::isInstance).toList();
		if (!systemMessages.isEmpty()) {
			combined.removeIf(SystemMessage.class::isInstance);
			combined.addAll(0, systemMessages);
		}

		// 4. Append the current user message to the session
		Message userMessage = request.prompt().getLastUserOrToolResponseMessage();
		if (userMessage != null) {
			this.sessionService.appendMessage(sessionId, userMessage);
		}

		return request.mutate().prompt(request.prompt().mutate().messages(combined).build()).build();
	}

	@Override
	public ChatClientResponse after(ChatClientResponse response, AdvisorChain advisorChain) {
		String sessionId = getSessionId(response.context());

		// 1. Append the assistant message(s) produced by the model
		if (response.chatResponse() != null) {
			response.chatResponse()
				.getResults()
				.stream()
				.map(g -> (Message) g.getOutput())
				.forEach(msg -> this.sessionService.appendMessage(sessionId, msg));
		}

		// 2. Compact synchronously if configured — the full turn (user + assistant) is
		// already written at this point so there is no race.
		if (this.compactionTrigger != null && this.compactionStrategy != null) {
			this.sessionService.compact(sessionId, this.compactionTrigger, this.compactionStrategy);
		}

		return response;
	}

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
		return Mono.just(request)
			.publishOn(this.scheduler)
			.map(r -> this.before(r, chain))
			.flatMapMany(chain::nextStream)
			// Re-pin to the scheduler so that the after() callback (which performs
			// synchronous session writes and optional compaction) always runs on the
			// configured scheduler rather than the LLM streaming thread.
			.publishOn(this.scheduler)
			.transform(flux -> new ChatClientMessageAggregator().aggregateChatClientResponse(flux,
					r -> this.after(r, chain)));
	}

	private String getSessionId(Map<String, @Nullable Object> context) {
		Object value = context.get(SESSION_ID_CONTEXT_KEY);
		return (value instanceof String s && !s.isBlank()) ? s : this.defaultSessionId;
	}

	private String getUserId(Map<String, @Nullable Object> context) {
		Object value = context.get(USER_ID_CONTEXT_KEY);
		return (value instanceof String s && !s.isBlank()) ? s : this.defaultUserId;
	}

	public static Builder builder(SessionService sessionService) {
		return new Builder(sessionService);
	}

	public static final class Builder {

		private final SessionService sessionService;

		private String defaultSessionId = "default";

		private String defaultUserId = "default-user";

		private int order = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER;

		private Scheduler scheduler = BaseAdvisor.DEFAULT_SCHEDULER;

		private EventFilter eventFilter = EventFilter.all();

		@Nullable private CompactionTrigger compactionTrigger;

		@Nullable private CompactionStrategy compactionStrategy;

		private Builder(SessionService sessionService) {
			Assert.notNull(sessionService, "sessionService must not be null");
			this.sessionService = sessionService;
		}

		public Builder defaultSessionId(String defaultSessionId) {
			this.defaultSessionId = defaultSessionId;
			return this;
		}

		public Builder defaultUserId(String defaultUserId) {
			this.defaultUserId = defaultUserId;
			return this;
		}

		public Builder order(int order) {
			this.order = order;
			return this;
		}

		public Builder scheduler(Scheduler scheduler) {
			this.scheduler = scheduler;
			return this;
		}

		/**
		 * Filter applied when loading the session's event history to inject into the
		 * prompt. Defaults to {@link EventFilter#all()} (all events).
		 * <p>
		 * Use {@link EventFilter#forBranch(String)} in multi-agent scenarios so each
		 * agent only sees events on its own branch and its ancestors': <pre>{@code
		 * SessionMemoryAdvisor.builder(sessionService)
		 *     .eventFilter(EventFilter.forBranch("orch.researcher"))
		 *     .build();
		 * }</pre>
		 */
		public Builder eventFilter(EventFilter eventFilter) {
			Assert.notNull(eventFilter, "eventFilter must not be null");
			this.eventFilter = eventFilter;
			return this;
		}

		public Builder compactionTrigger(CompactionTrigger trigger) {
			this.compactionTrigger = trigger;
			return this;
		}

		public Builder compactionStrategy(CompactionStrategy strategy) {
			this.compactionStrategy = strategy;
			return this;
		}

		public SessionMemoryAdvisor build() {
			if ((this.compactionTrigger == null) != (this.compactionStrategy == null)) {
				throw new IllegalArgumentException(
						"compactionTrigger and compactionStrategy must be set together — set both or neither");
			}
			return new SessionMemoryAdvisor(this.sessionService, this.defaultSessionId, this.defaultUserId, this.order,
					this.scheduler, this.eventFilter, this.compactionTrigger, this.compactionStrategy);
		}

	}

}
