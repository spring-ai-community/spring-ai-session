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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Interaction;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.ToolConfirmationPolicy;
import org.springframework.ai.session.EventType;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.SessionStatus;
import org.springframework.ai.session.StopReason;
import org.springframework.ai.session.ToolConfirmation;
import org.springframework.core.Ordered;
import org.springframework.util.Assert;

/**
 * The human-in-the-loop gate for tool calls: the counterpart of an agent runtime's
 * {@code always_ask} tool permission, {@code requires_action} stop reason and
 * {@code user.tool_confirmation} event.
 * <p>
 * A {@link ToolConfirmationPolicy} names the tool calls a caller must confirm. Its
 * {@link ToolConfirmationPolicy#eligibilityChecker()} goes on the tool-calling advisor, so
 * the loop returns a reply with such a call instead of running it. This advisor then:
 * <ol>
 * <li><b>Parks</b> the turn in {@link #after}: once the {@link SessionMemoryAdvisor} has
 * stored the reply, the turn ends with {@link StopReason#REQUIRES_ACTION} naming the
 * assistant event the caller has to answer. The session is idle, can be archived, and the
 * pending call is the newest message in the log, so the decision can come in a later
 * request or on another node.</li>
 * <li><b>Resumes</b> the turn in {@link #before} when the request carries the caller's
 * decisions ({@link #TOOL_CONFIRMATION_CONTEXT_KEY}: a {@link ToolConfirmation} or a
 * collection of them, one per gated call; a call of the same reply the policy does not
 * gate is allowed implicitly): it starts the turn, records a
 * {@link org.springframework.ai.session.EventType#TOOL_CONFIRMATION} event per decision,
 * runs the allowed calls through the {@link ToolCallingManager} with the tools the request
 * carries, answers the denied ones with their message, and puts the tool results in front
 * of the prompt. The memory advisor stores them like any tool result and the model
 * continues the turn where it was parked. A request that resumes a turn carries no new
 * user message; new text goes in the next turn.</li>
 * </ol>
 * The advisor sits between the tool-calling advisor and the memory advisor (order
 * {@link #DEFAULT_ORDER}) and needs the {@link SessionTurnAdvisor}, which starts the turn
 * it parks and ends the turn it resumes. Whatever the loop's position, it must stay
 * <em>outside</em> the memory advisor (a lower order value):
 * it parks after the reply is stored and resumes before the history is loaded. A confirmed
 * tool marked {@code returnDirect} is a known limitation: its result goes to the model
 * like any other, since a resumption cannot short-circuit the chain.
 *
 * @author Christian Tzolov
 * @since 0.11.0
 * @see ToolConfirmationPolicy
 */
public final class ToolConfirmationAdvisor implements BaseAdvisor {

	/**
	 * Context key for the caller's decisions on the tool calls a parked turn waits on: a
	 * {@link ToolConfirmation} or a collection of them, one per pending tool call.
	 */
	public static final String TOOL_CONFIRMATION_CONTEXT_KEY = "tool_confirmations";

	/**
	 * Between the tool-calling advisor ({@code HIGHEST_PRECEDENCE + 300}) and the memory
	 * advisor's default ({@code HIGHEST_PRECEDENCE + 1000}).
	 */
	public static final int DEFAULT_ORDER = Ordered.HIGHEST_PRECEDENCE + 900;

	private static final Logger logger = LoggerFactory.getLogger(ToolConfirmationAdvisor.class);

	private final SessionService sessionService;

	private final ToolConfirmationPolicy policy;

	private final ToolCallingManager toolCallingManager;

	private final int order;

	private ToolConfirmationAdvisor(SessionService sessionService, ToolConfirmationPolicy policy,
			ToolCallingManager toolCallingManager, int order) {
		this.sessionService = sessionService;
		this.policy = policy;
		this.toolCallingManager = toolCallingManager;
		this.order = order;
	}

	public static Builder builder(SessionService sessionService) {
		return new Builder(sessionService);
	}

	/** The policy, to put its {@link ToolConfirmationPolicy#eligibilityChecker()} on the tool-calling advisor. */
	public ToolConfirmationPolicy getPolicy() {
		return this.policy;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	@Override
	public ChatClientRequest before(ChatClientRequest request, AdvisorChain advisorChain) {
		List<ToolConfirmation> confirmations = toolConfirmations(request.context());
		if (confirmations.isEmpty()) {
			return request;
		}
		String sessionId = SessionContexts.sessionId(request.context());
		List<Message> messages = request.prompt().getInstructions();
		// The tool-calling loop gives every round a copy of the request's context, so the
		// decisions are seen again on the rounds that follow the resumption. Those rounds
		// carry the previous round's tool results; the decisions were consumed before.
		if (messages.stream().anyMatch(ToolResponseMessage.class::isInstance)) {
			return request;
		}
		if (messages.stream().anyMatch(UserMessage.class::isInstance)) {
			throw new IllegalArgumentException("A request that confirms tool calls of session '" + sessionId
					+ "' carries no new user message; send the text in the next turn");
		}
		AssistantMessage pending = pendingToolCalls(sessionId);
		Map<String, ToolConfirmation> decisions = decisions(sessionId, pending, confirmations, this.policy);
		List<AssistantMessage.ToolCall> allowed = pending.getToolCalls()
			.stream()
			.filter(call -> decisions.get(call.id()).allowed())
			.toList();
		if (!allowed.isEmpty() && !(request.prompt().getOptions() instanceof ToolCallingChatOptions)) {
			throw new IllegalArgumentException("The request must carry the tools (ToolCallingChatOptions) to run "
					+ "the confirmed tool calls of session '" + sessionId + "'");
		}

		// The resumption starts the turn, its compare-and-set making it exclusive (a second
		// request with the same decisions fails instead of running the tool twice), and
		// records the decisions as the answer to the question the turn parked on
		this.sessionService.resume(sessionId, null, ToolConfirmation.asAnswer(decisions.values()));
		// The turn advisor ends the turn the resumption started
		SessionTurn turn = SessionTurn.of(request.context());
		if (turn != null) {
			turn.hold();
		}
		ToolResponseMessage results;
		try {
			results = answer(pending, decisions, allowed, request.prompt().getOptions());
		}
		catch (RuntimeException ex) {
			failTurn(sessionId, ex);
			throw ex;
		}
		List<Message> withResults = new ArrayList<>(messages.size() + 1);
		withResults.add(results);
		withResults.addAll(messages);
		return request.mutate().prompt(request.prompt().mutate().messages(withResults).build()).build();
	}

	@Override
	public ChatClientResponse after(ChatClientResponse response, AdvisorChain advisorChain) {
		ChatResponse chatResponse = response.chatResponse();
		if (chatResponse == null || !chatResponse.hasToolCalls() || !this.policy.requiresConfirmation(chatResponse)) {
			return response;
		}
		String sessionId = SessionContexts.sessionId(response.context());
		List<String> eventIds = new ArrayList<>();
		SessionEvent newest = newestConversationEvent(sessionId);
		if (newest != null && newest.hasToolCalls()) {
			eventIds.add(newest.getId());
		}
		else {
			logger.warn("Parking the turn of session [{}] without the assistant event: the reply with the tool "
					+ "call is not the newest message in the log; is this advisor ordered inside the memory advisor?",
					sessionId);
		}
		// The question the turn parks on: the calls that need a decision
		List<Map<String, Object>> pendingCalls = new ArrayList<>();
		chatResponse.getResults().stream().filter(g -> g.getOutput().hasToolCalls()).forEach(g -> g.getOutput()
			.getToolCalls()
			.stream()
			.filter(this.policy::requiresConfirmation)
			.forEach(call -> pendingCalls.add(Map.of("id", call.id(), "name", call.name(), "arguments",
					call.arguments() != null ? call.arguments() : ""))));
		Interaction question = new Interaction(Interaction.KIND_TOOL_CONFIRMATION, "", Map.of("toolCalls", pendingCalls),
				ToolConfirmation.class.getName());
		try {
			this.sessionService.park(sessionId, question, eventIds);
		}
		catch (IllegalStateException ex) {
			Session session = this.sessionService.findById(sessionId);
			if (session.status() == SessionStatus.IDLE && session.stopReason() == StopReason.INTERRUPTED) {
				// Ended by hand while the reply was in flight: the reply stands, the pending
				// call stays in the log and can still be confirmed
				logger.warn("The turn of session [{}] was interrupted before it could be parked", sessionId);
				return response;
			}
			throw new IllegalStateException("Could not park the turn of session '" + sessionId
					+ "' for a tool confirmation: " + ex.getMessage() + ". The gate needs the SessionTurnAdvisor "
					+ "to run the turn", ex);
		}
		return response;
	}

	/**
	 * A streamed reply is aggregated first, so {@link #after} sees the tool calls of the
	 * whole reply and not only the chunk that carries the finish reason.
	 */
	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
		return Mono.just(request)
			.publishOn(getScheduler())
			.map(r -> before(r, chain))
			.flatMapMany(chain::nextStream)
			.publishOn(getScheduler())
			.transform(flux -> new ChatClientMessageAggregator().aggregateChatClientResponse(flux,
					r -> after(r, chain)));
	}

	/** Runs the allowed calls and answers every pending call, in the order the model asked for them. */
	private ToolResponseMessage answer(AssistantMessage pending, Map<String, ToolConfirmation> decisions,
			List<AssistantMessage.ToolCall> allowed, @Nullable ChatOptions options) {
		Map<String, ToolResponseMessage.ToolResponse> results = new LinkedHashMap<>();
		if (!allowed.isEmpty()) {
			ChatResponse toRun = ChatResponse.builder()
				.generations(List.of(new Generation(AssistantMessage.builder().toolCalls(allowed).build())))
				.build();
			List<Message> history = this.toolCallingManager.executeToolCalls(new Prompt(List.of(), options), toRun)
				.conversationHistory();
			if (!history.isEmpty() && history.get(history.size() - 1) instanceof ToolResponseMessage responses) {
				responses.getResponses().forEach(result -> results.put(result.id(), result));
			}
		}
		List<ToolResponseMessage.ToolResponse> ordered = new ArrayList<>();
		for (AssistantMessage.ToolCall call : pending.getToolCalls()) {
			ToolConfirmation decision = decisions.get(call.id());
			ToolResponseMessage.ToolResponse result = results.get(call.id());
			ordered.add(result != null ? result : new ToolResponseMessage.ToolResponse(call.id(), call.name(),
					decision.allowed() ? "The tool returned no result." : decision.denyMessageOrDefault()));
		}
		return ToolResponseMessage.builder().responses(ordered).build();
	}

	/**
	 * One decision per pending call, none for a call that is not pending. A pending call
	 * the policy does not gate (parked along with a gated one of the same reply) is
	 * allowed when no decision names it.
	 */
	private static Map<String, ToolConfirmation> decisions(String sessionId, AssistantMessage pending,
			List<ToolConfirmation> confirmations, ToolConfirmationPolicy policy) {
		Set<String> callIds = new LinkedHashSet<>();
		pending.getToolCalls().forEach(call -> callIds.add(call.id()));
		Map<String, ToolConfirmation> decisions = new LinkedHashMap<>();
		for (ToolConfirmation confirmation : confirmations) {
			if (!callIds.contains(confirmation.toolCallId())) {
				throw new IllegalArgumentException("Session '" + sessionId + "' is not waiting on tool call '"
						+ confirmation.toolCallId() + "'; pending: " + callIds);
			}
			decisions.put(confirmation.toolCallId(), confirmation);
		}
		Set<String> missing = new LinkedHashSet<>();
		for (AssistantMessage.ToolCall call : pending.getToolCalls()) {
			if (!decisions.containsKey(call.id())) {
				if (policy.requiresConfirmation(call)) {
					missing.add(call.id());
				}
				else {
					decisions.put(call.id(), ToolConfirmation.allow(call.id()));
				}
			}
		}
		if (!missing.isEmpty()) {
			throw new IllegalArgumentException(
					"Session '" + sessionId + "' waits on a decision for every gated tool call; missing: " + missing);
		}
		return decisions;
	}

	/** The assistant message the session waits on: the newest conversation event, which must carry tool calls. */
	private AssistantMessage pendingToolCalls(String sessionId) {
		SessionEvent newest = newestConversationEvent(sessionId);
		if (newest == null || !newest.hasToolCalls()) {
			throw new IllegalArgumentException("Session '" + sessionId + "' is not waiting on a tool call");
		}
		return (AssistantMessage) newest.getMessage();
	}

	/**
	 * The newest message of the active view that is not a system message, or {@code null}.
	 * The window is one event, extended by the repository to the start of its turn.
	 */
	private @Nullable SessionEvent newestConversationEvent(String sessionId) {
		List<SessionEvent> events = this.sessionService.getEvents(sessionId,
				EventFilter.active().toBuilder().lastN(1).build());
		for (int i = events.size() - 1; i >= 0; i--) {
			if (events.get(i).getMessageType() != MessageType.SYSTEM) {
				return events.get(i);
			}
		}
		return null;
	}

	private void failTurn(String sessionId, RuntimeException cause) {
		try {
			this.sessionService.endTurn(sessionId, StopReason.ERROR);
		}
		catch (RuntimeException ex) {
			logger.warn("Could not end the turn of session [{}] after a failed tool confirmation: {}", sessionId,
					ex.getMessage());
			cause.addSuppressed(ex);
		}
	}

	private static List<ToolConfirmation> toolConfirmations(Map<String, @Nullable Object> context) {
		Object value = context.get(TOOL_CONFIRMATION_CONTEXT_KEY);
		if (value == null) {
			return List.of();
		}
		if (value instanceof ToolConfirmation confirmation) {
			return List.of(confirmation);
		}
		if (value instanceof Collection<?> collection
				&& collection.stream().allMatch(ToolConfirmation.class::isInstance)) {
			return collection.stream().map(ToolConfirmation.class::cast).toList();
		}
		throw new IllegalArgumentException("Advisor context value for '" + TOOL_CONFIRMATION_CONTEXT_KEY
				+ "' must be a ToolConfirmation or a collection of them but was " + value.getClass().getName());
	}

	public static final class Builder {

		private final SessionService sessionService;

		private ToolConfirmationPolicy policy = ToolConfirmationPolicy.none();

		private ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();

		private int order = DEFAULT_ORDER;

		private Builder(SessionService sessionService) {
			Assert.notNull(sessionService, "sessionService must not be null");
			this.sessionService = sessionService;
		}

		/** Which tool calls need a caller's confirmation; put its {@code eligibilityChecker()} on the tool-calling advisor. */
		public Builder policy(ToolConfirmationPolicy policy) {
			Assert.notNull(policy, "policy must not be null");
			this.policy = policy;
			return this;
		}

		/**
		 * The manager that runs a confirmed call with the tools the request carries;
		 * defaults to {@code ToolCallingManager.builder().build()}. Share the tool-calling
		 * advisor's manager to keep its limits and observations.
		 */
		public Builder toolCallingManager(ToolCallingManager toolCallingManager) {
			Assert.notNull(toolCallingManager, "toolCallingManager must not be null");
			this.toolCallingManager = toolCallingManager;
			return this;
		}

		/**
		 * Keep it outside the memory advisor (a lower value than its order), between the
		 * tool-calling advisor and the memory advisor by default ({@link #DEFAULT_ORDER}).
		 */
		public Builder order(int order) {
			this.order = order;
			return this;
		}

		public ToolConfirmationAdvisor build() {
			return new ToolConfirmationAdvisor(this.sessionService, this.policy, this.toolCallingManager, this.order);
		}

	}

}
