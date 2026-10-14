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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.MemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.EventType;
import org.springframework.ai.session.MessageFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.SessionStatus;
import org.springframework.ai.session.StopReason;
import org.springframework.ai.session.TokenUsage;
import org.springframework.ai.session.UsageEvents;
import org.springframework.core.Ordered;
import org.springframework.util.Assert;

/**
 * A {@link BaseAdvisor} that manages conversation history using the
 * {@link SessionService}.
 *
 * <p>
 * On each interaction:
 * <ol>
 * <li>Retrieves the session's event history and prepends it to the prompt messages. Of the
 * system messages stored in the session only the latest one is used (it is the system
 * prompt); all system messages are moved to the front, and exact-text duplicates are sent
 * only once.</li>
 * <li>Appends the current user message to the session, if accepted by the configured
 * {@link MessageFilter}; inside a tool loop, the trailing tool results, always.</li>
 * <li>After the model responds, appends the assistant message(s) to the session; messages
 * rejected by the configured {@link MessageFilter} are skipped, except a reply with tool
 * calls, which is always stored. By default, empty assistant messages (blank text, no
 * tool calls, and no media) are filtered out.</li>
 * <li>Records every model call's token usage as a
 * {@link org.springframework.ai.session.EventType#USAGE} event; see
 * {@link Builder#recordUsage(boolean)}.</li>
 * <li>Names the events it stored on the request and response context
 * ({@link #REQUEST_EVENT_ID_CONTEXT_KEY}, {@link #RESPONSE_EVENT_IDS_CONTEXT_KEY}), so a
 * caller can correlate what it sent and received with the log.</li>
 * <li>Stops a turn the {@link SessionTurnAdvisor} runs once it was interrupted
 * ({@link org.springframework.ai.session.SessionService#interrupt}), with a
 * {@link TurnInterruptedException}: before the next model call, once the results of the
 * tools that ran are stored, or after a reply that asks for tools, whose stored calls it
 * answers with {@link #INTERRUPTED_TOOL_RESULT} instead of letting them run.</li>
 * </ol>
 *
 * <p>
 * The turn around those model calls (its status, and compaction once it has ended) is the
 * {@link SessionTurnAdvisor}'s: register both. On its own this advisor keeps the history
 * and leaves the status alone.
 *
 * <p>
 * The session is identified by the {@link #SESSION_ID_CONTEXT_KEY} value in the advisor
 * context. The key must be present on every request; omitting it throws
 * {@link IllegalStateException} to prevent accidental cross-user session sharing.
 *
 * <p>
 * By default it runs inside the tool-calling loop (order {@code HIGHEST_PRECEDENCE + 1000}),
 * once per round: from the second round on, the loop's prompt carries the turn so far, and only the
 * history before it is prepended. A tool call or tool result left without its counterpart
 * is kept out of the prompt, and tool calls and results are always stored. An event's id
 * derives from its position in the call's run ({@link RunScopedSessionEventIdGenerator}),
 * so a replay of the run writes nothing twice. See the ChatClient integration docs for
 * each of these.
 *
 * @author Christian Tzolov
 * @since 0.1.0
 */
public final class SessionMemoryAdvisor implements BaseAdvisor, MemoryAdvisor {

	private static final Logger logger = LoggerFactory.getLogger(SessionMemoryAdvisor.class);

	/**
	 * Context key used to pass the session ID into the advisor per-request. Equals
	 * {@link org.springframework.ai.chat.memory.ChatMemory#CONVERSATION_ID} so that this
	 * advisor uses the same context key as the rest of Spring AI's memory API. Set via:
	 * {@code .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, "my-session-id"))}
	 */
	public static final String SESSION_ID_CONTEXT_KEY = ChatMemory.CONVERSATION_ID;

	/** The result a tool call gets in the log when its turn was interrupted before it ran. */
	public static final String INTERRUPTED_TOOL_RESULT = "The turn was interrupted before this tool call ran.";

	/**
	 * Context key for the metadata of a session the advisor creates on first use. The
	 * value must be a {@code Map<String, Object>}; it is ignored for an existing session.
	 * Scope attributes such as the principal name are normally not passed this way but
	 * resolved by the service's {@code SessionScopeResolver}. The agent attributes
	 * ({@link SessionAttributes#AGENT_NAME}) label the agent a session runs; set them once
	 * for every call of a {@code ChatClient} with
	 * {@code .defaultAdvisors(a -> a.param(SessionMemoryAdvisor.SESSION_METADATA_CONTEXT_KEY, Map.of(SessionAttributes.AGENT_NAME, "support")))}.
	 * A request that passes its own value replaces that default as a whole, so it includes
	 * the agent attributes too.
	 */
	public static final String SESSION_METADATA_CONTEXT_KEY = "chat_memory_session_metadata";

	/**
	 * Context key for a per-request {@link EventFilter} that is merged over the
	 * advisor's configured filter (see {@link EventFilter#merge(EventFilter)}). The value
	 * must be an {@code EventFilter} that selects a prompt's history (see
	 * {@link Builder#eventFilter(EventFilter)}). Set via:
	 * {@code .advisors(a -> a.param(SessionMemoryAdvisor.EVENT_FILTER_CONTEXT_KEY, EventFilter.lastN(10)))}
	 */
	public static final String EVENT_FILTER_CONTEXT_KEY = "chat_memory_event_filter_id";

	/**
	 * Context key the advisor sets on the request it forwards, and so on the response: the
	 * id of the event that stores the request's trailing user or tool message; absent
	 * when none was stored. Lets a caller name the message it sent by the id
	 * the log knows it by; inside a tool loop it is the current round's.
	 */
	public static final String REQUEST_EVENT_ID_CONTEXT_KEY = "chat_memory_request_event_id";

	/**
	 * Context key the advisor sets on the response: the ids of the events that store the
	 * reply's assistant messages, in reply order, as a {@code List<String>}; empty when
	 * none was stored. Inside a tool loop it is the current round's. A streamed reply's
	 * chunks do not carry it; subscribe to the session for the stored events instead.
	 */
	public static final String RESPONSE_EVENT_IDS_CONTEXT_KEY = "chat_memory_response_event_ids";

	private final SessionService sessionService;

	private final int order;

	private final Scheduler scheduler;

	private final EventFilter eventFilter;

	private final MessageFilter messageFilter;

	private final SessionEventRequestIdGenerator requestEventIdGenerator;

	private final SessionEventResponseIdGenerator responseEventIdGenerator;

	private final boolean recordUsage;

	private SessionMemoryAdvisor(Builder builder) {
		this.sessionService = builder.sessionService;
		this.order = builder.order;
		this.scheduler = builder.scheduler;
		this.eventFilter = builder.eventFilter;
		this.messageFilter = builder.messageFilter;
		this.requestEventIdGenerator = builder.requestEventIdGenerator;
		this.responseEventIdGenerator = builder.responseEventIdGenerator;
		this.recordUsage = builder.recordUsage;
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

		// 1. Resolve the session id (it must be in the context) and find or create the
		// session. Whether the caller may use it is not decided here: an authorizing
		// SessionService (see the security module) checks every read and append.
		String sessionId = SessionContexts.sessionId(request.context());
		Session session = SessionContexts.findOrCreate(this.sessionService, sessionId, request.context());
		// Each call of before() is a round of the turn: the run-scoped event ids count them
		SessionTurnAdvisor.nextRound(request.context());

		// 2. Read the history: always the active view, whatever the configured or
		// per-request filter says; the events a compaction covered live on for search only
		EventFilter eventFilter = resolveEventFilter(request.context()).merge(EventFilter.active());
		List<SessionEvent> events = this.sessionService.getEvents(sessionId, eventFilter);
		// A lastN window may leave the system prompt, which is configuration, outside the
		// events read: look it up on its own
		SessionEvent systemPrompt = PromptHistory.latestStoredSystemPrompt(events);
		if (systemPrompt == null && !(eventFilter.window() instanceof EventFilter.Window.All)) {
			systemPrompt = latestStoredSystemPrompt(sessionId);
		}

		// 3. The prompt: the history the request does not carry yet, valid for the model
		List<Message> prompt = PromptHistory.of(events, (systemPrompt != null) ? systemPrompt.getMessage() : null)
			.prompt(request.prompt().getInstructions(), sessionId);

		// 4. Store the request's trailing user message, subject to the message filter, or
		// tool results, always. Skipping only affects persistence, not the prompt. A
		// request without one stores nothing.
		Message userMessage = PromptHistory.lastUserOrToolResponse(request.prompt().getInstructions());
		String requestEventId = null;
		if (userMessage != null && shouldPersist(userMessage, sessionId)) {
			requestEventId = this.requestEventIdGenerator.generate(request, userMessage);
			this.sessionService.appendEvent(SessionEvent.builder()
				.id(requestEventId)
				.sessionId(sessionId)
				.message(userMessage)
				.build());
		}
		// 5. A turn interrupted since the previous round stops before the next model call,
		// once the results of the tools that ran are stored
		if (SessionTurnAdvisor.inTurn(request.context()) && isInterrupted(session)) {
			throw new TurnInterruptedException(sessionId);
		}

		// The forwarded request copies the context, so the id reaches the response too. No
		// null value: the model call advisor copies the context with Map.copyOf
		ChatClientRequest.Builder forwarded = request.mutate()
			.prompt(request.prompt().mutate().messages(prompt).build());
		if (requestEventId != null) {
			forwarded.context(REQUEST_EVENT_ID_CONTEXT_KEY, requestEventId);
		}
		return forwarded.build();
	}

	@Override
	public ChatClientResponse after(ChatClientResponse response, AdvisorChain advisorChain) {
		String sessionId = SessionContexts.sessionId(response.context());

		// 1. Append the assistant message(s) produced by the model, subject to the
		// configured message filter. By default excludes messages that carry no
		// content — blank text, no tool calls, and no media.
		List<String> responseEventIds = new ArrayList<>();
		// The stored replies that ask for tools, by event id
		Map<String, AssistantMessage> storedToolCalls = new LinkedHashMap<>();
		if (response.chatResponse() != null) {
			response.chatResponse().getResults().stream().map(Generation::getOutput).forEach(reply -> {
				if (!shouldPersist(reply, sessionId)) {
					return;
				}
				String eventId = this.responseEventIdGenerator.generate(response, reply);
				this.sessionService
					.appendEvent(SessionEvent.builder().id(eventId).sessionId(sessionId).message(reply).build());
				responseEventIds.add(eventId);
				if (reply.hasToolCalls()) {
					storedToolCalls.put(eventId, reply);
				}
			});
		}
		ChatClientResponse named = response.mutate()
			.context(RESPONSE_EVENT_IDS_CONTEXT_KEY, List.copyOf(responseEventIds))
			.build();

		// 1.1. Record the call's token usage. The id derives from the first stored reply's,
		// so a retried after() with a deterministic generator counts it once.
		if (this.recordUsage && response.chatResponse() != null) {
			recordUsage(response, response.chatResponse(), responseEventIds, sessionId);
		}

		// 2. A turn interrupted while the model answered: the reply's tool calls do not
		// run. The stored calls are answered, so the log stays valid and the next turn's
		// model learns why. The id derives from the reply's, so a retried after() with a
		// deterministic generator answers them once.
		if (response.chatResponse() != null && response.chatResponse().hasToolCalls()
				&& SessionTurnAdvisor.inTurn(response.context())
				&& isInterrupted(this.sessionService.findById(sessionId))) {
			storedToolCalls.forEach((replyEventId, reply) -> this.sessionService.appendEvent(SessionEvent.builder()
				.id(replyEventId + "-interrupted")
				.sessionId(sessionId)
				.message(interruptedResults(reply))
				.build()));
			throw new TurnInterruptedException(sessionId);
		}

		return named;
	}

	/** Whether the session's turn ended because it was interrupted. */
	private static boolean isInterrupted(@Nullable Session session) {
		return session != null && session.status() != SessionStatus.RUNNING
				&& session.stopReason() == StopReason.INTERRUPTED;
	}

	/** Answers every tool call of the reply with {@link #INTERRUPTED_TOOL_RESULT}. */
	private static ToolResponseMessage interruptedResults(AssistantMessage reply) {
		return ToolResponseMessage.builder()
			.responses(reply.getToolCalls()
				.stream()
				.map(call -> new ToolResponseMessage.ToolResponse(call.id(), call.name(), INTERRUPTED_TOOL_RESULT))
				.toList())
			.build();
	}

	/**
	 * Reads the latest stored (non-synthetic, active) system message of the session with
	 * one pushed-down query, for a read whose window may not contain it.
	 */
	private @Nullable SessionEvent latestStoredSystemPrompt(String sessionId) {
		EventFilter latestSystem = EventFilter.builder()
			.messageTypes(Set.of(MessageType.SYSTEM))
			.excludeSynthetic(true)
			.activeView(true)
			.lastN(1)
			.build();
		List<SessionEvent> found = this.sessionService.getEvents(sessionId, latestSystem);
		return found.isEmpty() ? null : found.get(0);
	}

	/**
	 * Records the call's usage under the id of the reply it belongs to, plus
	 * {@code -usage}: the first stored reply's, or, when none was stored, the one the
	 * generator gives the first reply.
	 */
	private void recordUsage(ChatClientResponse response, ChatResponse chatResponse, List<String> responseEventIds,
			String sessionId) {
		TokenUsage usage = TokenUsage.of(chatResponse.getMetadata().getUsage());
		if (usage.isEmpty() || chatResponse.getResults().isEmpty()) {
			return;
		}
		String replyId = !responseEventIds.isEmpty() ? responseEventIds.get(0)
				: this.responseEventIdGenerator.generate(response, chatResponse.getResults().get(0).getOutput());
		SessionEvent usageEvent = UsageEvents.of(sessionId, usage, chatResponse.getMetadata().getModel());
		this.sessionService.appendEvent(SessionEvent.builder()
			.id(replyId + "-usage")
			.sessionId(sessionId)
			.type(usageEvent.getType())
			.payload(usageEvent.getPayload())
			.build());
	}

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
		return Mono.just(request)
			.publishOn(this.scheduler)
			.map(r -> this.before(r, chain))
			.flatMapMany(chain::nextStream)
			// Re-pin to the scheduler so that the after() callback (which performs
			// synchronous session writes) always runs on the configured scheduler rather
			// than the LLM streaming thread.
			.publishOn(this.scheduler)
			.transform(flux -> new ChatClientMessageAggregator().aggregateChatClientResponse(flux,
					r -> this.after(r, chain)));
	}

	/**
	 * Returns the advisor's configured filter, merged with the per-request filter from
	 * {@link #EVENT_FILTER_CONTEXT_KEY} when present, so that request-level parameters
	 * override the advisor defaults.
	 */
	private EventFilter resolveEventFilter(Map<String, @Nullable Object> context) {
		Object requestFilterValue = context.get(EVENT_FILTER_CONTEXT_KEY);
		if (requestFilterValue == null) {
			return this.eventFilter;
		}
		if (!(requestFilterValue instanceof EventFilter requestEventFilter)) {
			throw new IllegalArgumentException("Advisor context value for '" + EVENT_FILTER_CONTEXT_KEY
					+ "' must be an EventFilter but was " + requestFilterValue.getClass().getName());
		}
		return this.eventFilter.merge(requirePromptHistory(requestEventFilter,
				"The per-request filter '" + EVENT_FILTER_CONTEXT_KEY + "'"));
	}

	/**
	 * The filter, when it selects a prompt's history: the active view, all of it or its
	 * newest N events, optionally by message type or without synthetic events.
	 */
	static EventFilter requirePromptHistory(EventFilter filter, String what) {
		List<String> unsupported = new ArrayList<>();
		if (filter.from() != null || filter.to() != null) {
			unsupported.add("a time range");
		}
		if (filter.eventTypes() != null && !filter.eventTypes().equals(Set.of(EventType.MESSAGE))) {
			unsupported.add("event types");
		}
		if (filter.keywords() != null || filter.pattern() != null) {
			unsupported.add("a text search");
		}
		if (filter.window() instanceof EventFilter.Window.Page) {
			unsupported.add("a page window");
		}
		else if (filter.window() instanceof EventFilter.Window.After) {
			unsupported.add("a cursor window");
		}
		if (filter.order() == EventFilter.Order.DESC) {
			unsupported.add("newest-first order");
		}
		if (!unsupported.isEmpty()) {
			throw new IllegalArgumentException(what + " selects a prompt's history: all of the active view or its "
					+ "last N events, optionally by message type; it has " + String.join(", ", unsupported)
					+ ". Search the log with SessionService.getEvents instead");
		}
		return filter;
	}

	/**
	 * Whether the message is stored. A tool call and a tool result always are: either half
	 * alone makes a prompt every provider rejects, and parking, resuming and interrupting a
	 * turn read them back from the log. The {@link MessageFilter} decides for every other
	 * message.
	 */
	private boolean shouldPersist(Message message, String sessionId) {
		if (message instanceof ToolResponseMessage
				|| (message instanceof AssistantMessage assistant && assistant.hasToolCalls())) {
			return true;
		}
		if (!this.messageFilter.shouldPersist(message)) {
			logger.debug("Skipping [{}] message for session [{}] — rejected by the configured MessageFilter",
					message.getMessageType(), sessionId);
			return false;
		}
		return true;
	}

	public static Builder builder(SessionService sessionService) {
		return new Builder(sessionService);
	}

	public static final class Builder {

		private final SessionService sessionService;

		// Deliberately higher (lower-precedence) than ToolCallingAdvisor's default order
		// (HIGHEST_PRECEDENCE + 300): with ascending order sort, the lower value runs
		// outer, so this places SessionMemoryAdvisor nested inside a default-configured
		// ToolCallingAdvisor's tool-call loop rather than wrapping it -- before()/after()
		// then run once per round, which PromptHistory's overlap with the loop's own
		// prompt is built to tolerate.
		private int order = Ordered.HIGHEST_PRECEDENCE + 1000;

		private Scheduler scheduler = BaseAdvisor.DEFAULT_SCHEDULER;

		private EventFilter eventFilter = EventFilter.all();

		private MessageFilter messageFilter = MessageFilter.skipEmptyMessages();

		private SessionEventRequestIdGenerator requestEventIdGenerator = new RunScopedSessionEventIdGenerator();

		private SessionEventResponseIdGenerator responseEventIdGenerator = new RunScopedSessionEventIdGenerator();

		private boolean recordUsage = true;

		private Builder(SessionService sessionService) {
			Assert.notNull(sessionService, "sessionService must not be null");
			this.sessionService = sessionService;
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
		 * prompt. Defaults to {@link EventFilter#all()} (all events). It selects a
		 * prompt's history: the active view (always), all of it or its newest
		 * {@link EventFilter#lastN(int) N events}, optionally only some
		 * {@link EventFilter#messageTypes() message types} or without synthetic events.
		 * A time range, event types, a text search, a page or cursor window or
		 * newest-first order describe a search, not a prompt, and are rejected; search
		 * the log with {@code SessionService.getEvents} instead.
		 * @throws IllegalArgumentException if the filter does not select a prompt's
		 * history
		 */
		public Builder eventFilter(EventFilter eventFilter) {
			Assert.notNull(eventFilter, "eventFilter must not be null");
			this.eventFilter = requirePromptHistory(eventFilter, "The eventFilter of the SessionMemoryAdvisor");
			return this;
		}

		/**
		 * Filter applied before appending messages to session memory — both the current
		 * user message persisted in {@code before()} and the assistant message(s)
		 * persisted in {@code after()}. Messages the filter rejects are not persisted and
		 * therefore never replayed on later requests. Tool calls and tool results are
		 * always stored, whatever the filter says: to keep them out of the prompt, use an
		 * {@link EventFilter}'s {@code messageTypes} on read. The outgoing prompt is
		 * unaffected. Defaults to {@link MessageFilter#skipEmptyMessages()}.
		 * <p>
		 * Note: replacing the default removes the empty-assistant-message protection
		 * (see issue #19 — some models reject empty messages replayed as history).
		 * Compose instead of replacing when you still want it: <pre>{@code
		 * SessionMemoryAdvisor.builder(sessionService)
		 *     .messageFilter(myFilter.and(MessageFilter.skipEmptyMessages()))
		 *     .build();
		 * }</pre>
		 */
		public Builder messageFilter(MessageFilter messageFilter) {
			Assert.notNull(messageFilter, "messageFilter must not be null");
			this.messageFilter = messageFilter;
			return this;
		}

		/**
		 * Overrides how the id is derived for the session event persisted in
		 * {@code before()} (the current user/tool-response message). Defaults to a
		 * {@link RunScopedSessionEventIdGenerator}: deterministic when the call carries a
		 * run id, random otherwise.
		 */
		public Builder requestEventIdGenerator(SessionEventRequestIdGenerator requestEventIdGenerator) {
			Assert.notNull(requestEventIdGenerator, "requestEventIdGenerator must not be null");
			this.requestEventIdGenerator = requestEventIdGenerator;
			return this;
		}

		/**
		 * Overrides how the id is derived for each session event persisted in
		 * {@code after()} (the assistant reply message(s)). Defaults to a
		 * {@link RunScopedSessionEventIdGenerator}.
		 */
		public Builder responseEventIdGenerator(SessionEventResponseIdGenerator responseEventIdGenerator) {
			Assert.notNull(responseEventIdGenerator, "responseEventIdGenerator must not be null");
			this.responseEventIdGenerator = responseEventIdGenerator;
			return this;
		}

		/**
		 * Whether every model call's token usage is recorded as a
		 * {@link org.springframework.ai.session.EventType#USAGE} event (the default).
		 * {@link SessionService#usage} sums them and a {@link BudgetAdvisor} reads them.
		 */
		public Builder recordUsage(boolean recordUsage) {
			this.recordUsage = recordUsage;
			return this;
		}

		public SessionMemoryAdvisor build() {
			return new SessionMemoryAdvisor(this);
		}

	}

}
