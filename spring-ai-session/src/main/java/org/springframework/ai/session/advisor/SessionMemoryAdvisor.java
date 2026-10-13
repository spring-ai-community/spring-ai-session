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
import java.util.HashMap;
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
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
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
import org.springframework.ai.content.MediaContent;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.MessageFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.SessionStatus;
import org.springframework.ai.session.StopReason;
import org.springframework.ai.session.TokenUsage;
import org.springframework.ai.session.UsageEvents;
import org.springframework.ai.session.compaction.CompactionStrategy;
import org.springframework.ai.session.compaction.CompactionTrigger;
import org.springframework.core.Ordered;
import org.springframework.util.Assert;

/**
 * A {@link BaseAdvisor} that manages conversation history using the
 * {@link SessionService}, with optional context compaction.
 *
 * <p>
 * On each interaction:
 * <ol>
 * <li>Retrieves the session's event history and prepends it to the prompt messages. Of the
 * system messages stored in the session only the latest one is used (it is the system
 * prompt); all system messages are moved to the front, and exact-text duplicates are sent
 * only once.</li>
 * <li>Appends the current user message to the session, if accepted by the configured
 * {@link MessageFilter}.</li>
 * <li>After the model responds, appends the assistant message(s) to the session; messages
 * rejected by the configured {@link MessageFilter} are skipped. By default, empty
 * assistant messages (blank text, no tool calls, and no media) are filtered out.</li>
 * <li>Optionally triggers context compaction if the configured trigger fires, once the
 * turn is complete (not after a reply that requests tool calls).</li>
 * <li>Records the turn in the session's status: {@link SessionService#startTurn} before
 * the model call and {@link SessionService#endTurn} once the turn is complete, with
 * {@link StopReason#ERROR} when the call fails. The start is a compare-and-set, so a
 * second concurrent turn on the same session is rejected; see
 * {@link Builder#trackStatus(boolean)}.</li>
 * <li>Records every model call's token usage as a
 * {@link org.springframework.ai.session.EventType#USAGE} event; see
 * {@link Builder#recordUsage(boolean)}.</li>
 * <li>Names the events it stored on the request and response context
 * ({@link #REQUEST_EVENT_ID_CONTEXT_KEY}, {@link #RESPONSE_EVENT_IDS_CONTEXT_KEY}), so a
 * caller can correlate what it sent and received with the log.</li>
 * </ol>
 *
 * <p>
 * The session is identified by the {@link #SESSION_ID_CONTEXT_KEY} value in the advisor
 * context. The key must be present on every request; omitting it throws
 * {@link IllegalStateException} to prevent accidental cross-user session sharing.
 *
 * <p>
 * <strong>Concurrent compaction safety:</strong> If two requests for the same session
 * complete concurrently, both {@code after()} calls may reach the compaction step
 * simultaneously. Compaction is recorded with an optimistic compare-and-swap append via
 * {@link org.springframework.ai.session.SessionRepository#appendEvents(String, java.util.List, long)},
 * so only the first writer's boundary lands; the second detects the version mismatch and
 * skips silently. The log is never changed, so nothing is lost or corrupted.
 *
 * <p>
 * <strong>Tool-call integrity:</strong> a prompt in which an assistant tool call is not
 * followed by the tool results answering it is rejected by every provider. The advisor
 * keeps that from happening at both ends. On read, the configured {@link EventFilter}'s
 * {@code lastN} window is extended by the repository to the start of the turn it lands
 * in, and any tool call or tool result that still has no counterpart in the history (a
 * {@code messageTypes} filter hiding {@code TOOL} events, a log written with a
 * {@link MessageFilter} that skipped them, or a turn interrupted mid-loop) is dropped from
 * the prompt with a warning; the log is left untouched. On write, a trailing tool
 * response is stored exactly when the tool call it answers was stored, overriding the
 * {@link MessageFilter} in either direction with a warning.
 *
 * <p>
 * <strong>Event id generation:</strong> by default every persisted event gets a fresh
 * random id ({@link SessionEventRequestIdGenerator#random()} /
 * {@link SessionEventResponseIdGenerator#random()}), so a retried append is always a new
 * event. Configure {@link Builder#requestEventIdGenerator} / {@link Builder#responseEventIdGenerator}
 * with a deterministic derivation (e.g. content-addressable, or reusing an upstream
 * durability layer's own idempotency key) to make a retried append an idempotent no-op
 * instead, via {@code SessionRepository.appendEvent}'s id-based replay contract.
 *
 * <p>
 * <strong>Nesting inside a tool-calling loop:</strong> an advisor such as
 * {@code ToolCallingAdvisor} that implements its own tool-call loop re-enters the rest of
 * the advisor chain once per round, so if this advisor's order places it deeper in the
 * chain than the looping advisor, {@code before()}/{@code after()} run once per round too.
 * From round 2 onward the prompt passed in already carries this turn's messages (they were
 * persisted to the session by the previous round), so {@code before()} detects that the
 * session history it just retrieved is already a contiguous run within the prompt and skips
 * prepending it again -- avoiding duplicate messages in the prompt sent to the model. System
 * messages are excluded from that check (they are moved to the front of the prompt, so
 * their position says nothing about what was already sent); stored system messages are
 * always included and exact-text duplicates are dropped. Actual
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
	 * must be an {@code EventFilter}. Set via:
	 * {@code .advisors(a -> a.param(SessionMemoryAdvisor.EVENT_FILTER_CONTEXT_KEY, EventFilter.lastN(10)))}
	 */
	public static final String EVENT_FILTER_CONTEXT_KEY = "chat_memory_event_filter_id";

	/**
	 * Context key the advisor sets on the request it forwards, and so on the response: the
	 * id of the event that stores the request's trailing user or tool message, or
	 * {@code null} when none was stored. Lets a caller name the message it sent by the id
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

	@Nullable private final CompactionTrigger compactionTrigger;

	@Nullable private final CompactionStrategy compactionStrategy;

	private final boolean trackStatus;

	private final boolean recordUsage;

	private SessionMemoryAdvisor(SessionService sessionService, int order, Scheduler scheduler,
			EventFilter eventFilter, MessageFilter messageFilter, SessionEventRequestIdGenerator requestEventIdGenerator,
			SessionEventResponseIdGenerator responseEventIdGenerator, @Nullable CompactionTrigger compactionTrigger,
			@Nullable CompactionStrategy compactionStrategy, boolean trackStatus, boolean recordUsage) {
		this.trackStatus = trackStatus;
		this.recordUsage = recordUsage;
		this.sessionService = sessionService;
		this.order = order;
		this.scheduler = scheduler;
		this.eventFilter = eventFilter;
		this.messageFilter = messageFilter;
		this.requestEventIdGenerator = requestEventIdGenerator;
		this.responseEventIdGenerator = responseEventIdGenerator;
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

		// 0. Resolve the session ID — must be present in the request context.
		String sessionId = getSessionId(request.context());

		// 1. Find or create the session.
		Session session = this.sessionService.findById(sessionId);
		if (session == null) {
			session = createSession(sessionId, request.context());
		}

		// Whether the caller may use this session is not decided here: an authorizing
		// SessionService (see the security module) checks every read and append.

		// 1.1. Start the turn, unless this is a later round of a tool loop (the prompt ends
		// with the tool results of the previous round), in which case the turn is already
		// running. The service's compare-and-set rejects a second concurrent turn. Once the
		// turn is ours, anything that fails below ends it, so the session never stays running
		// because of a bad filter value, a rejected message or a store that is down.
		if (this.trackStatus && !(request.prompt().getLastUserOrToolResponseMessage() instanceof ToolResponseMessage)) {
			this.sessionService.startTurn(sessionId);
		}
		try {
			return assemblePrompt(request, sessionId);
		}
		catch (RuntimeException ex) {
			failTurn(request.context(), ex);
			throw ex;
		}
	}

	/** Steps 2 to 4 of {@link #before}: load the history, build the prompt, persist the user message. */
	private ChatClientRequest assemblePrompt(ChatClientRequest request, String sessionId) {
		// 2. Retrieve history applying the configured filter (default: all events)

		// Always read the active view: the events a compaction covered live on only for
		// Recall Storage search. Merging forces the flag on regardless of the configured or
		// per-request filter.
		EventFilter eventFilter = resolveEventFilter(request.context()).merge(EventFilter.active());

		List<SessionEvent> events = this.sessionService.getEvents(sessionId, eventFilter);

		// 2.1. Skip re-prepending history that the prompt already carries. This
		// happens when this advisor is nested inside a looping advisor -- e.g. a
		// ToolCallingAdvisor with an order placing it deeper in the chain (see the
		// order Javadoc on the Builder) -- whose tool-call loop re-enters before()
		// once per round. From round 2 onward, request.prompt().getInstructions()
		// already contains this turn's user/assistant/tool messages, persisted to
		// the session by the previous round's before()/after(); without this guard
		// getEvents() would return that same prefix and it would be prepended a
		// second time.
		// System messages are left out of this check on both sides: step 3 below moves
		// every system message to the front, so a stored system message is never
		// contiguous with the rest of the history in a prompt produced by an earlier round
		// (e.g. when the request also carries its own system prompt). Stored system
		// messages are always added; step 3 then drops the exact-text copy an earlier
		// round already put in the prompt.
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
		// A tool call without its results (or the reverse) makes a prompt every provider
		// rejects; drop the stranded side before deciding what to prepend, so the check
		// below compares like with like on every round.
		List<Message> historyConversation = withoutOrphanToolMessages(events.stream()
			.filter(e -> e.getMessageType() != MessageType.SYSTEM)
			.map(SessionEvent::getMessage)
			.toList(), promptConversation, sessionId);
		List<Message> combined = new ArrayList<>(historySystem);
		if (!isHistoryAlreadyInPrompt(promptConversation, historyConversation)) {
			combined.addAll(historyConversation);
		}
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
		if (userMessage != null && shouldPersistTrailing(userMessage, events, eventFilter, sessionId)) {
			requestEventId = this.requestEventIdGenerator.generate(request, userMessage);
			this.sessionService.appendEvent(SessionEvent.builder()
				.id(requestEventId)
				.sessionId(sessionId)
				.message(userMessage)
				.build());
		}
		// The forwarded request copies the context, so the id reaches the response too
		return request.mutate()
			.prompt(request.prompt().mutate().messages(combined).build())
			.context(REQUEST_EVENT_ID_CONTEXT_KEY, requestEventId)
			.build();
	}

	@Override
	public ChatClientResponse after(ChatClientResponse response, AdvisorChain advisorChain) {
		String sessionId = getSessionId(response.context());

		// 1. Append the assistant message(s) produced by the model, subject to the
		// configured message filter. By default excludes messages that carry no
		// content — blank text, no tool calls, and no media.
		List<String> responseEventIds = new ArrayList<>();
		if (response.chatResponse() != null) {
			response.chatResponse().getResults().stream().map(g -> (Message) g.getOutput()).forEach(msg -> {
				if (!shouldPersist(msg, sessionId)) {
					return;
				}
				String eventId = this.responseEventIdGenerator.generate(response, msg);
				this.sessionService
					.appendEvent(SessionEvent.builder().id(eventId).sessionId(sessionId).message(msg).build());
				responseEventIds.add(eventId);
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

		// 2. Compact synchronously if configured, once the turn is complete. Inside a
		// tool-calling loop this advisor's after() runs once per round; a reply that
		// still requests tool calls means the turn is in progress. Compacting then could
		// insert a summary the looping advisor's next prompt doesn't carry, so the next
		// round's before() would re-send the whole history. Compaction is best-effort:
		// the model's reply is already produced and persisted, so a compaction failure
		// (e.g. the summarization LLM call failing) must not fail the user's request. The
		// log is left untouched and compaction is retried after the next turn.
		if (this.compactionTrigger != null && this.compactionStrategy != null && !requestsToolCalls(response)) {
			try {
				this.sessionService.compact(sessionId, this.compactionTrigger, this.compactionStrategy);
			}
			catch (RuntimeException ex) {
				logger.warn("Compaction failed for session [{}]; the event log is unchanged and compaction will be "
						+ "retried after the next turn", sessionId, ex);
			}
		}

		// 3. End the turn once it is complete: a reply that requests tool calls keeps the
		// session running until the loop's final reply (or until a ToolConfirmationAdvisor
		// parks it). A turn someone ended by hand in the meantime (an interrupt) must not
		// fail the reply that was already produced.
		if (this.trackStatus && !requestsToolCalls(response)) {
			try {
				this.sessionService.endTurn(sessionId, StopReason.END_TURN);
			}
			catch (IllegalStateException ex) {
				logger.warn("The turn of session [{}] was already ended: {}", sessionId, ex.getMessage());
			}
		}

		return named;
	}

	@Override
	public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
		Assert.notNull(request, "request must not be null");
		Assert.notNull(chain, "chain must not be null");
		ChatClientRequest processed = before(request, chain);
		ChatClientResponse response;
		try {
			response = chain.nextCall(processed);
		}
		catch (RuntimeException ex) {
			failTurn(processed.context(), ex);
			throw ex;
		}
		return after(response, chain);
	}

	/**
	 * Ends the turn with {@link StopReason#ERROR} when the chain fails after
	 * {@code before()} started it, so the session does not stay running. Inside a tool
	 * loop the innermost round ends it; the outer rounds then find it idle and do nothing.
	 */
	private void failTurn(Map<String, @Nullable Object> context, Throwable cause) {
		if (!this.trackStatus) {
			return;
		}
		try {
			String sessionId = getSessionId(context);
			Session session = this.sessionService.findById(sessionId);
			if (session != null && session.status() == SessionStatus.RUNNING) {
				this.sessionService.endTurn(sessionId, StopReason.ERROR);
			}
		}
		catch (RuntimeException ex) {
			logger.warn("Could not end the failed turn; the session may stay running until endTurn is called", ex);
		}
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

	/**
	 * Decides whether the trailing prompt message is persisted. A user message follows the
	 * configured {@link MessageFilter}. A tool response is stored exactly when the tool
	 * call it answers was stored: the filter is overridden, with a warning, when it would
	 * reject the response of a stored call or accept the response of a call it rejected.
	 * When the events read cannot show whether the call was stored (a {@code messageTypes}
	 * filter hiding assistant events, a time range, or a page window), the filter decides.
	 */
	private boolean shouldPersistTrailing(Message message, List<SessionEvent> events, EventFilter eventFilter,
			String sessionId) {
		if (!(message instanceof ToolResponseMessage response)) {
			return shouldPersist(message, sessionId);
		}
		boolean accepted = this.messageFilter.shouldPersist(message);
		Boolean callStored = isAnsweringStoredToolCall(response, events, eventFilter);
		if (callStored == null || callStored == accepted) {
			return shouldPersist(message, sessionId);
		}
		if (callStored) {
			logger.warn("Storing a tool response the MessageFilter rejected for session [{}]: the tool call it "
					+ "answers is in the session log and would be unanswerable without it", sessionId);
			return true;
		}
		logger.warn("Skipping a tool response for session [{}]: the tool call it answers was not stored "
				+ "(rejected by the MessageFilter), so the response alone would be an orphan", sessionId);
		return false;
	}

	/**
	 * Returns whether the newest stored conversation event is the assistant tool call this
	 * response answers, or {@code null} when the events read cannot tell.
	 */
	private static @Nullable Boolean isAnsweringStoredToolCall(ToolResponseMessage response,
			List<SessionEvent> events, EventFilter eventFilter) {
		if (eventFilter.window() instanceof EventFilter.Window.Page || eventFilter.from() != null
				|| eventFilter.to() != null
				|| (eventFilter.messageTypes() != null && !eventFilter.messageTypes().contains(MessageType.ASSISTANT))) {
			return null;
		}
		for (int i = events.size() - 1; i >= 0; i--) {
			SessionEvent event = events.get(i);
			if (event.getMessageType() == MessageType.SYSTEM) {
				continue;
			}
			if (event.getMessageType() == MessageType.TOOL) {
				continue; // an earlier response of the same parallel call set
			}
			return event.hasToolCalls() && answers((AssistantMessage) event.getMessage(), response);
		}
		return false;
	}

	/**
	 * Returns {@code true} if the model's reply asks for tool calls, i.e. the turn is
	 * still in progress inside a tool-calling loop.
	 */
	private static boolean requestsToolCalls(ChatClientResponse response) {
		return response.chatResponse() != null && response.chatResponse()
			.getResults()
			.stream()
			.anyMatch(generation -> generation.getOutput() != null && generation.getOutput().hasToolCalls());
	}

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
		return Mono.just(request)
			.publishOn(this.scheduler)
			.map(r -> this.before(r, chain))
			.flatMapMany(r -> chain.nextStream(r).doOnError(ex -> failTurn(r.context(), ex)))
			// Re-pin to the scheduler so that the after() callback (which performs
			// synchronous session writes and optional compaction) always runs on the
			// configured scheduler rather than the LLM streaming thread.
			.publishOn(this.scheduler)
			.transform(flux -> new ChatClientMessageAggregator().aggregateChatClientResponse(flux,
					r -> this.after(r, chain)));
	}

	private String getSessionId(Map<String, @Nullable Object> context) {
		Object value = context.get(SESSION_ID_CONTEXT_KEY);
		if (value instanceof String s && !s.isBlank()) {
			return s;
		}
		throw new IllegalStateException(
				"No session ID found in advisor context. " + "Set SESSION_ID_CONTEXT_KEY on every request: "
						+ ".advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId))");
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
		return this.eventFilter.merge(requestEventFilter);
	}

	/**
	 * Creates the session on first use. {@code SessionService.create} inserts atomically,
	 * so if a concurrent request for the same new session ID created it first, this
	 * request's create is rejected and the <em>stored</em> session is re-read instead.
	 */
	private Session createSession(String sessionId, Map<String, @Nullable Object> context) {
		try {
			return this.sessionService
				.create(CreateSessionRequest.builder().id(sessionId).metadata(sessionMetadata(context)).build());
		}
		catch (IllegalStateException ex) {
			Session existing = this.sessionService.findById(sessionId);
			if (existing == null) {
				throw ex;
			}
			return existing;
		}
	}

	private static Map<String, Object> sessionMetadata(Map<String, @Nullable Object> context) {
		Object value = context.get(SESSION_METADATA_CONTEXT_KEY);
		if (value == null) {
			return Map.of();
		}
		if (!(value instanceof Map<?, ?> map)) {
			throw new IllegalArgumentException("Advisor context value for '" + SESSION_METADATA_CONTEXT_KEY
					+ "' must be a Map but was " + value.getClass().getName());
		}
		Map<String, Object> metadata = new HashMap<>();
		map.forEach((k, v) -> {
			if (k != null && v != null) {
				metadata.put(String.valueOf(k), v);
			}
		});
		return metadata;
	}

	/**
	 * Returns {@code true} if {@code history} already occurs as a contiguous run
	 * somewhere in {@code promptMessages}, in which case prepending it again would
	 * duplicate it. Meant to guard against the same class of duplication when a memory
	 * advisor is re-entered inside a tool-calling loop.
	 */
	private static boolean isHistoryAlreadyInPrompt(List<Message> promptMessages, List<Message> history) {
		if (history.isEmpty()) {
			return true;
		}
		if (promptMessages.size() < history.size()) {
			return false;
		}
		for (int offset = 0; offset <= promptMessages.size() - history.size(); offset++) {
			if (startsWith(promptMessages, history, offset)) {
				return true;
			}
		}
		return false;
	}

	private static boolean startsWith(List<Message> messages, List<Message> prefix, int offset) {
		if (messages.size() - offset < prefix.size()) {
			return false;
		}
		for (int i = 0; i < prefix.size(); i++) {
			if (!sameContent(messages.get(i + offset), prefix.get(i))) {
				return false;
			}
		}
		return true;
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
	 * Returns {@code true} if the message should be persisted to the session, delegating
	 * to the configured {@link MessageFilter}. Rejected messages are logged and not
	 * replayed on later requests.
	 */
	private boolean shouldPersist(Message message, String sessionId) {
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
		// then run once per round, which is what the isHistoryAlreadyInPrompt() guard in
		// before() is built to tolerate (see the class Javadoc "Nesting inside a
		// tool-calling loop" section).
		private int order = Ordered.HIGHEST_PRECEDENCE + 1000;

		private Scheduler scheduler = BaseAdvisor.DEFAULT_SCHEDULER;

		private EventFilter eventFilter = EventFilter.all();

		private MessageFilter messageFilter = MessageFilter.skipEmptyMessages();

		private SessionEventRequestIdGenerator requestEventIdGenerator = SessionEventRequestIdGenerator.random();

		private SessionEventResponseIdGenerator responseEventIdGenerator = SessionEventResponseIdGenerator.random();

		@Nullable private CompactionTrigger compactionTrigger;

		@Nullable private CompactionStrategy compactionStrategy;

		private boolean trackStatus = true;

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
		 * prompt. Defaults to {@link EventFilter#all()} (all events).
		 */
		public Builder eventFilter(EventFilter eventFilter) {
			Assert.notNull(eventFilter, "eventFilter must not be null");
			this.eventFilter = eventFilter;
			return this;
		}

		/**
		 * Filter applied before appending messages to session memory — both the current
		 * user (or tool-response) message persisted in {@code before()} and the
		 * assistant message(s) persisted in {@code after()}. Messages the filter rejects
		 * are not persisted and therefore never replayed on later requests. The outgoing
		 * prompt is unaffected. Defaults to
		 * {@link MessageFilter#skipEmptyMessages()}.
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

		public Builder compactionTrigger(CompactionTrigger trigger) {
			this.compactionTrigger = trigger;
			return this;
		}

		public Builder compactionStrategy(CompactionStrategy strategy) {
			this.compactionStrategy = strategy;
			return this;
		}

		/**
		 * Overrides how the id is derived for the session event persisted in
		 * {@code before()} (the current user/tool-response message). Defaults to
		 * {@link SessionEventRequestIdGenerator#random()} -- a fresh random id every
		 * call, i.e. today's behaviour. Supply a deterministic generator to make a
		 * retried append idempotent instead of a duplicate.
		 */
		public Builder requestEventIdGenerator(SessionEventRequestIdGenerator requestEventIdGenerator) {
			Assert.notNull(requestEventIdGenerator, "requestEventIdGenerator must not be null");
			this.requestEventIdGenerator = requestEventIdGenerator;
			return this;
		}

		/**
		 * Overrides how the id is derived for each session event persisted in
		 * {@code after()} (the assistant reply message(s)). Defaults to
		 * {@link SessionEventResponseIdGenerator#random()}.
		 */
		public Builder responseEventIdGenerator(SessionEventResponseIdGenerator responseEventIdGenerator) {
			Assert.notNull(responseEventIdGenerator, "responseEventIdGenerator must not be null");
			this.responseEventIdGenerator = responseEventIdGenerator;
			return this;
		}

		/**
		 * Whether the advisor records the session's turn status: {@code true} (the default)
		 * starts a turn in {@code before()} ({@link SessionService#startTurn}, which rejects
		 * a second concurrent turn on the same session), ends it in {@code after()} once the
		 * reply no longer requests tool calls, and ends it with {@link StopReason#ERROR}
		 * when the call fails. {@code false} leaves the status alone.
		 */
		public Builder trackStatus(boolean trackStatus) {
			this.trackStatus = trackStatus;
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
			if ((this.compactionTrigger == null) != (this.compactionStrategy == null)) {
				throw new IllegalArgumentException(
						"compactionTrigger and compactionStrategy must be set together — set both or neither");
			}
			return new SessionMemoryAdvisor(this.sessionService, this.order, this.scheduler,
					this.eventFilter, this.messageFilter, this.requestEventIdGenerator, this.responseEventIdGenerator,
					this.compactionTrigger, this.compactionStrategy, this.trackStatus, this.recordUsage);
		}

	}

}
