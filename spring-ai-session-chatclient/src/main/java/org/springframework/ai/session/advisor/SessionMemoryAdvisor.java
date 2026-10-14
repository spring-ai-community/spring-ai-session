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
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.content.MediaContent;
import org.springframework.ai.session.AgentSnapshot;
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
 * <strong>Tool-call integrity:</strong> a prompt in which an assistant tool call is not
 * followed by the tool results answering it is rejected by every provider. The advisor
 * keeps that from happening at both ends. On read, the configured {@link EventFilter}'s
 * {@code lastN} window is extended by the repository to the start of the turn it lands
 * in, and any tool call or tool result that still has no counterpart in the history (a
 * {@code messageTypes} filter hiding {@code TOOL} events, a log written by an earlier
 * version with a {@link MessageFilter} that skipped them, or a turn interrupted mid-loop)
 * is dropped from the prompt with a warning; the log is left untouched. On write, tool
 * calls and tool results are always stored, whatever the {@link MessageFilter} says; to
 * keep them out of the prompt, hide them on read with an {@link EventFilter}'s
 * {@code messageTypes}.
 *
 * <p>
 * <strong>Event id generation:</strong> by default ({@link RunScopedSessionEventIdGenerator})
 * an event's id derives from its position in a run: the session, the run id the caller
 * gives with {@link SessionTurnAdvisor#RUN_ID_CONTEXT_KEY}, the round of the turn and the
 * slot in the round. A replay of the same run then writes nothing twice, via
 * {@code SessionRepository.appendEvent}'s id-based replay contract. Without a run id (or
 * without the {@link SessionTurnAdvisor}) the ids are random, so a retried append is a new
 * event. Configure {@link Builder#requestEventIdGenerator} /
 * {@link Builder#responseEventIdGenerator} for another derivation.
 *
 * <p>
 * <strong>Nesting inside a tool-calling loop:</strong> an advisor such as
 * {@code ToolCallingAdvisor} that implements its own tool-call loop re-enters the rest of
 * the advisor chain once per round, so if this advisor's order places it deeper in the
 * chain than the looping advisor, {@code before()}/{@code after()} run once per round too.
 * From round 2 onward the prompt passed in starts with this turn's messages, which the
 * session history ends with (the previous rounds persisted them), so {@code before()}
 * prepends only the history before that overlap -- avoiding duplicate messages in the
 * prompt sent to the model. System messages are excluded from that check (they are moved to
 * the front of the prompt, so their position says nothing about what was already sent);
 * stored system messages are always included and exact-text duplicates are dropped. Actual
 * persistence is unaffected by nesting depth: only the current turn's trailing
 * user/tool-response message and the model's own reply are ever appended, and with a
 * deterministic {@link IdempotentSessionEventIdGenerator} configured, a re-derived id makes
 * a repeated append of the same message an idempotent no-op rather than a duplicate event.
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
	 * resolved by the service's {@code SessionScopeResolver}. Set via:
	 * {@code .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_METADATA_CONTEXT_KEY, Map.of("agent", "support")))}
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

	@Nullable private final AgentSnapshot agent;

	private SessionMemoryAdvisor(Builder builder) {
		this.sessionService = builder.sessionService;
		this.order = builder.order;
		this.scheduler = builder.scheduler;
		this.eventFilter = builder.eventFilter;
		this.messageFilter = builder.messageFilter;
		this.requestEventIdGenerator = builder.requestEventIdGenerator;
		this.responseEventIdGenerator = builder.responseEventIdGenerator;
		this.recordUsage = builder.recordUsage;
		this.agent = builder.agent;
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
		Session session = SessionContexts.findOrCreate(this.sessionService, sessionId, request.context(),
				this.agent);
		// Each call of before() is a round of the turn: the run-scoped event ids count them
		SessionTurnAdvisor.nextRound(request.context());

		// 2. Retrieve history applying the configured filter (default: all events)

		// Always read the active view: the events a compaction covered live on only for
		// Recall Storage search. Merging forces the flag on regardless of the configured or
		// per-request filter.
		EventFilter eventFilter = resolveEventFilter(request.context()).merge(EventFilter.active());

		List<SessionEvent> events = this.sessionService.getEvents(sessionId, eventFilter);

		// Latest wins: of the system messages stored in the session, only the latest one
		// is the system prompt. Earlier ones are superseded (a later compaction covers them),
		// so they are not sent. Synthetic events (e.g. legacy SYSTEM summaries) are kept.
		SessionEvent latestStoredSystem = null;
		for (int i = events.size() - 1; i >= 0; i--) {
			SessionEvent event = events.get(i);
			if (!event.isSynthetic() && event.getMessageType() == MessageType.SYSTEM) {
				latestStoredSystem = event;
				break;
			}
		}
		// A window (lastN or a page) may leave the stored system prompt outside the events
		// read; it is configuration, so look it up on its own.
		if (latestStoredSystem == null && !(eventFilter.window() instanceof EventFilter.Window.All)) {
			latestStoredSystem = latestStoredSystemPrompt(sessionId);
		}
		SessionEvent sessionSystemPrompt = latestStoredSystem;
		List<Message> promptMessages = request.prompt().getInstructions();
		List<Message> historySystem = new ArrayList<>();
		if (sessionSystemPrompt != null && !events.contains(sessionSystemPrompt)) {
			historySystem.add(sessionSystemPrompt.getMessage());
		}
		events.stream()
			.filter(e -> e.getMessageType() == MessageType.SYSTEM && (e.isSynthetic() || e == sessionSystemPrompt))
			.map(SessionEvent::getMessage)
			.forEach(historySystem::add);
		List<Message> promptConversation = promptMessages.stream()
			.filter(m -> !(m instanceof SystemMessage))
			.toList();
		// 2.1. Prepend only the history the prompt does not already start with. A looping
		// advisor such as ToolCallingAdvisor re-enters before() once per round with the
		// turn so far (its own copy: the user message, then each round's tool calls and
		// results), which the log ends with, as the previous rounds stored them. System
		// messages are left out on both sides: step 3 moves them all to the front.
		List<Message> historyConversation = events.stream()
			.filter(e -> e.getMessageType() != MessageType.SYSTEM)
			.map(SessionEvent::getMessage)
			.toList();
		List<Message> missing = historyConversation.subList(0,
				historyConversation.size() - overlap(historyConversation, promptConversation));
		// A tool call without its results (or the reverse) makes a prompt every provider
		// rejects; drop the stranded side of what is prepended.
		List<Message> combined = new ArrayList<>(historySystem);
		combined.addAll(withoutOrphanToolMessages(missing, promptConversation, sessionId));
		combined.addAll(promptMessages);

		// 3. Ensure all system messages appear first (preserving their relative order).
		// A single pass collects every SystemMessage, removes them in place, then
		// prepends them as a block — so a system message buried in history and a
		// second one on the current request both end up at the front rather than
		// leaving the second one stranded mid-list. Exact-text duplicates (e.g. a
		// system message stored in the session that is also sent on the request) are
		// kept only once; texts are never merged or rewritten, so the system prompt
		// stays byte-stable for prompt caching.
		Set<String> seenSystemTexts = new HashSet<>();
		List<Message> systemMessages = combined.stream()
			.filter(SystemMessage.class::isInstance)
			.filter(m -> seenSystemTexts.add(Objects.requireNonNullElse(m.getText(), "")))
			.toList();
		if (!systemMessages.isEmpty()) {
			combined.removeIf(SystemMessage.class::isInstance);
			combined.addAll(0, systemMessages);
		}

		// 4. Append the current user message to the session, subject to the configured
		// message filter. Skipping only affects persistence — the outgoing prompt is
		// untouched. A tool response is stored exactly when the tool call it answers was
		// stored, whatever the filter says: either half alone makes an unusable log. A
		// request without one stores nothing.
		Message userMessage = lastUserOrToolResponse(request.prompt().getInstructions());
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
			.prompt(request.prompt().mutate().messages(combined).build());
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

		// 1.1. Record the call's token usage. The id derives from the reply's first
		// message, so a retried after() with a deterministic generator counts it once.
		if (this.recordUsage && response.chatResponse() != null) {
			recordUsage(response, response.chatResponse(), sessionId);
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
	 * Returns {@code history} without tool messages that have no counterpart: an assistant
	 * message whose tool calls are not all answered by the tool responses that directly
	 * follow it (in the history, then in {@code following}, the prompt's own messages), or a
	 * tool response that does not answer the assistant tool call directly before it. Calls
	 * and responses are matched by id; one response message may answer several parallel
	 * calls, and a partially answered call is dropped together with its responses. Prompt
	 * messages are never dropped.
	 */
	private static List<Message> withoutOrphanToolMessages(List<Message> history, List<Message> following,
			String sessionId) {
		if (history.stream()
			.noneMatch(m -> m instanceof ToolResponseMessage
					|| (m instanceof AssistantMessage assistant && assistant.hasToolCalls()))) {
			return history;
		}
		List<Message> all = new ArrayList<>(history);
		all.addAll(following);
		boolean[] keep = new boolean[history.size()];
		Arrays.fill(keep, true);
		for (int i = 0; i < history.size(); i++) {
			Message message = history.get(i);
			if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
				Set<String> unanswered = new HashSet<>();
				assistant.getToolCalls().forEach(call -> unanswered.add(call.id()));
				for (int j = i + 1; j < all.size() && !unanswered.isEmpty(); j++) {
					if (!(all.get(j) instanceof ToolResponseMessage responses)) {
						break;
					}
					responses.getResponses().forEach(response -> unanswered.remove(response.id()));
				}
				keep[i] = unanswered.isEmpty();
			}
			else if (message instanceof ToolResponseMessage responses) {
				int call = i - 1;
				while (call >= 0 && history.get(call) instanceof ToolResponseMessage) {
					call--;
				}
				keep[i] = call >= 0 && keep[call] && history.get(call) instanceof AssistantMessage assistant
						&& assistant.hasToolCalls() && answers(assistant, responses);
			}
		}
		List<Message> kept = new ArrayList<>(history.size());
		int dropped = 0;
		for (int i = 0; i < history.size(); i++) {
			if (keep[i]) {
				kept.add(history.get(i));
			}
			else {
				dropped++;
			}
		}
		if (dropped > 0) {
			logger.warn("Dropped {} tool message(s) without their counterpart from the prompt for session [{}]. "
					+ "A messageTypes filter that hides TOOL events, a MessageFilter that skipped them, or a turn "
					+ "interrupted mid-loop is the likely cause; the session log is unchanged", dropped, sessionId);
		}
		return kept;
	}

	private void recordUsage(ChatClientResponse response, ChatResponse chatResponse, String sessionId) {
		TokenUsage usage = TokenUsage.of(chatResponse.getMetadata().getUsage());
		if (usage.isEmpty() || chatResponse.getResults().isEmpty()) {
			return;
		}
		Message first = chatResponse.getResults().get(0).getOutput();
		SessionEvent usageEvent = UsageEvents.of(sessionId, usage, chatResponse.getMetadata().getModel());
		this.sessionService.appendEvent(SessionEvent.builder()
			.id(this.responseEventIdGenerator.generate(response, first) + "-usage")
			.sessionId(sessionId)
			.type(usageEvent.getType())
			.payload(usageEvent.getPayload())
			.build());
	}

	/** The last user or tool response message of the prompt, or {@code null} when it has none. */
	private static @Nullable Message lastUserOrToolResponse(List<Message> messages) {
		for (int i = messages.size() - 1; i >= 0; i--) {
			Message message = messages.get(i);
			if (message instanceof UserMessage || message instanceof ToolResponseMessage) {
				return message;
			}
		}
		return null;
	}

	/** Returns {@code true} if every response answers one of the assistant's tool calls. */
	private static boolean answers(AssistantMessage assistant, ToolResponseMessage responses) {
		Set<String> callIds = new HashSet<>();
		assistant.getToolCalls().forEach(call -> callIds.add(call.id()));
		return responses.getResponses().stream().allMatch(response -> callIds.contains(response.id()));
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
	 * Returns how many of the last {@code history} messages the prompt starts with: the
	 * largest {@code k} for which the history's last {@code k} messages are the prompt's
	 * first {@code k}, compared by {@link #sameContent}.
	 */
	private static int overlap(List<Message> history, List<Message> prompt) {
		for (int k = Math.min(history.size(), prompt.size()); k > 0; k--) {
			int offset = history.size() - k;
			int i = 0;
			while (i < k && sameContent(history.get(offset + i), prompt.get(i))) {
				i++;
			}
			if (i == k) {
				return k;
			}
		}
		return 0;
	}

	/**
	 * Compares two messages by what the model sees rather than by {@code equals}: the
	 * type, the text, the tool calls and the tool responses. A repository may not
	 * round-trip everything else (e.g. {@code JdbcSessionRepository} stores neither
	 * metadata such as the finish reason nor media), so a message reloaded from the
	 * session would otherwise never equal the one the tool-calling loop re-sends. Media
	 * is compared only when both messages carry it.
	 */
	private static boolean sameContent(Message a, Message b) {
		if (a == b) {
			return true;
		}
		if (a.getMessageType() != b.getMessageType()
				|| !Objects.requireNonNullElse(a.getText(), "").equals(Objects.requireNonNullElse(b.getText(), ""))) {
			return false;
		}
		if (a instanceof AssistantMessage assistantA && b instanceof AssistantMessage assistantB
				&& !assistantA.getToolCalls().equals(assistantB.getToolCalls())) {
			return false;
		}
		if (a instanceof ToolResponseMessage toolA && b instanceof ToolResponseMessage toolB
				&& !toolA.getResponses().equals(toolB.getResponses())) {
			return false;
		}
		if (a instanceof MediaContent mediaA && b instanceof MediaContent mediaB && !mediaA.getMedia().isEmpty()
				&& !mediaB.getMedia().isEmpty() && !mediaA.getMedia().equals(mediaB.getMedia())) {
			return false;
		}
		return true;
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
		// then run once per round, which is what the overlap() check in before() is built
		// to tolerate (see the class Javadoc "Nesting inside a
		// tool-calling loop" section).
		private int order = Ordered.HIGHEST_PRECEDENCE + 1000;

		private Scheduler scheduler = BaseAdvisor.DEFAULT_SCHEDULER;

		private EventFilter eventFilter = EventFilter.all();

		private MessageFilter messageFilter = MessageFilter.skipEmptyMessages();

		private SessionEventRequestIdGenerator requestEventIdGenerator = new RunScopedSessionEventIdGenerator();

		private SessionEventResponseIdGenerator responseEventIdGenerator = new RunScopedSessionEventIdGenerator();

		private boolean recordUsage = true;

		@Nullable private AgentSnapshot agent;

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

		/**
		 * The agent this advisor's {@code ChatClient} runs: a session the advisor creates on
		 * first use is stamped with it ({@link SessionAttributes#agent(Session)}), the way
		 * an agent runtime snapshots the agent a session was created with. Metadata given
		 * with {@link #SESSION_METADATA_CONTEXT_KEY} that names an agent replaces the
		 * snapshot as a whole; an existing session is never changed.
		 */
		public Builder agent(@Nullable AgentSnapshot agent) {
			this.agent = agent;
			return this;
		}

		public SessionMemoryAdvisor build() {
			return new SessionMemoryAdvisor(this);
		}

	}

}
