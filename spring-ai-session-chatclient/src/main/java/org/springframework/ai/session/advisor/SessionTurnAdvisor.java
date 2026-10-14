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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallDecision;
import org.springframework.ai.model.tool.ToolCallDecisions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.session.AgentSnapshot;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.EventType;
import org.springframework.ai.session.Interaction;
import org.springframework.ai.session.InteractionEvents;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.SessionStatus;
import org.springframework.ai.session.StopReason;
import org.springframework.ai.session.ToolConfirmations;
import org.springframework.ai.session.compaction.CompactionStrategy;
import org.springframework.ai.session.compaction.CompactionTrigger;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.core.Ordered;
import org.springframework.util.Assert;

/**
 * Runs each {@code ChatClient} call as one turn of the session: the counterpart of the
 * {@link SessionMemoryAdvisor}, which works on each model call. It sits outside the
 * tool-calling loop (order {@link #DEFAULT_ORDER}), so it sees the request before the
 * first round and the turn's final response after the last one, however the loop ended.
 * <ol>
 * <li>Finds the session or creates it on first use, with the metadata from
 * {@link SessionMemoryAdvisor#SESSION_METADATA_CONTEXT_KEY} and the {@link Builder#agent
 * agent}.</li>
 * <li>Starts the turn ({@link SessionService#startTurn}). The start is a compare-and-set,
 * so a second concurrent turn on the same session is rejected.</li>
 * <li>Ends the turn with {@link StopReason#END_TURN} once the call returns, or with
 * {@link StopReason#ERROR} when it fails or a stream errors ({@link StopReason#INTERRUPTED}
 * when a stream is cancelled). A turn someone else ended (interrupted, or out of budget)
 * is left as it is.</li>
 * <li>Compacts the session once the turn has ended, if a {@link Builder#compactionTrigger
 * trigger} fires. Compaction is best-effort: a failure is logged and the reply returned,
 * and it is retried after the next turn. It appends a boundary with an optimistic
 * compare-and-swap, so of two concurrent compactions only the first lands.</li>
 * </ol>
 * <p>
 * <b>Tool calls that need confirmation.</b> When the tool-calling loop pauses on calls
 * that need a person's confirmation ({@link ToolCallDecisions#isAwaitingDecision}), the
 * turn <em>parks</em> instead of ending: it ends with {@link StopReason#REQUIRES_ACTION}
 * on a question that holds the paused reply and the ids that wait
 * ({@link ToolConfirmations}). The session is idle, can be archived, and the decisions can
 * come in a later request on any node. That request carries them under
 * {@link #TOOL_CALL_DECISIONS_CONTEXT_KEY} and no new user message. The advisor then
 * <em>resumes</em> the turn: it records the decisions as the answer, runs the paused reply
 * from the log with them through the {@link Builder#toolCallingManager tool calling
 * manager}, and sends the tool results on as the request's message, which the memory
 * advisor stores. A call of the reply that does not wait is allowed. When the results
 * are {@code returnDirect}, the advisor stores them and returns them without calling the
 * model. The resumption is a compare-and-set too: of two requests with the same decisions
 * only the first runs the tools.
 * <p>
 * A resumption that fails before any call ran leaves the calls open, so they can be
 * decided on again; once a call ran, the calls are answered with the failure instead, so
 * none runs twice. The tool-calling loop counts its limits from the resumption on, so a
 * pause restarts them once. {@code returnDirect} results are stored by this advisor, with
 * a random event id rather than the memory advisor's id generators.
 *
 * @author Christian Tzolov
 * @since 0.11.0
 */
public final class SessionTurnAdvisor implements CallAdvisor, StreamAdvisor {

	/** Outside the tool-calling advisor ({@code HIGHEST_PRECEDENCE + 300}), so the loop runs inside the turn. */
	public static final int DEFAULT_ORDER = Ordered.HIGHEST_PRECEDENCE + 200;

	/**
	 * Context key for a person's decisions on the tool calls a parked turn waits on: a
	 * {@link ToolCallDecisions}, a {@link ToolCallDecision} or a collection of them.
	 */
	public static final String TOOL_CALL_DECISIONS_CONTEXT_KEY = "tool_call_decisions";

	private static final Logger logger = LoggerFactory.getLogger(SessionTurnAdvisor.class);

	private final SessionService sessionService;

	private final ToolCallingManager toolCallingManager;

	private final int order;

	private final Scheduler scheduler;

	@Nullable private final CompactionTrigger compactionTrigger;

	@Nullable private final CompactionStrategy compactionStrategy;

	@Nullable private final AgentSnapshot agent;

	private SessionTurnAdvisor(Builder builder) {
		this.sessionService = builder.sessionService;
		this.toolCallingManager = builder.toolCallingManager;
		this.order = builder.order;
		this.scheduler = builder.scheduler;
		this.compactionTrigger = builder.compactionTrigger;
		this.compactionStrategy = builder.compactionStrategy;
		this.agent = builder.agent;
	}

	public static Builder builder(SessionService sessionService) {
		return new Builder(sessionService);
	}

	@Override
	public String getName() {
		return getClass().getSimpleName();
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	@Override
	public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
		Assert.notNull(request, "request must not be null");
		Assert.notNull(chain, "chain must not be null");
		String sessionId = SessionContexts.sessionId(request.context());
		Start start = begin(request, sessionId);
		if (start.response() != null) {
			return start.response();
		}
		ChatClientResponse response;
		try {
			response = chain.nextCall(start.request());
		}
		catch (RuntimeException ex) {
			end(sessionId, StopReason.ERROR);
			throw ex;
		}
		complete(sessionId, response.chatResponse());
		return response;
	}

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
		Assert.notNull(request, "request must not be null");
		Assert.notNull(chain, "chain must not be null");
		String sessionId = SessionContexts.sessionId(request.context());
		return Mono.just(request).publishOn(this.scheduler).flatMapMany(r -> {
			Start start = begin(r, sessionId);
			if (start.response() != null) {
				return Flux.just(start.response());
			}
			// A paused stream ends on a chunk with the ids; the aggregated reply drops them
			AtomicReference<ChatResponse> paused = new AtomicReference<>();
			// Back on the scheduler, so the session writes never run on the model's thread
			return chain.nextStream(start.request())
				.doOnNext(response -> {
					if (ToolCallDecisions.isAwaitingDecision(response.chatResponse())) {
						paused.set(response.chatResponse());
					}
				})
				.publishOn(this.scheduler)
				.doOnError(ex -> end(sessionId, StopReason.ERROR))
				.doOnComplete(() -> complete(sessionId, paused.get()))
				.doOnCancel(() -> end(sessionId, StopReason.INTERRUPTED));
		});
	}

	/**
	 * Starts the turn, or resumes the parked one when the request carries decisions. A
	 * resumption whose results go straight back to the caller has the response already.
	 */
	private Start begin(ChatClientRequest request, String sessionId) {
		List<ToolCallDecision> decisions = decisions(request.context());
		SessionContexts.findOrCreate(this.sessionService, sessionId, request.context(), this.agent);
		if (decisions.isEmpty()) {
			this.sessionService.startTurn(sessionId);
			return new Start(request, null);
		}
		return resume(request, sessionId, decisions);
	}

	private Start resume(ChatClientRequest request, String sessionId, List<ToolCallDecision> given) {
		List<Message> messages = request.prompt().getInstructions();
		if (messages.stream().anyMatch(UserMessage.class::isInstance)) {
			throw new IllegalArgumentException("A request that decides on tool calls of session '" + sessionId
					+ "' carries no new user message; send the text in the next turn");
		}
		Interaction question = waitingQuestion(sessionId);
		// The reply comes from the log, never from the caller
		AssistantMessage reply = ToolConfirmations.reply(question);
		ToolCallDecisions decisions = ToolConfirmations.complete(question, given);
		requireTools(sessionId, reply, decisions, request.prompt().getOptions());

		// Starts the turn; the compare-and-set makes the resumption exclusive
		this.sessionService.resume(sessionId, null, ToolConfirmations.asAnswer(decisions));
		AtomicInteger ran = new AtomicInteger();
		List<Message> turn = List.of();
		try {
			turn = currentTurn(sessionId);
			ToolExecutionResult result = this.toolCallingManager.executeToolCalls(
					new Prompt(turn, counting(request.prompt().getOptions(), ran)),
					ChatResponse.builder().generations(List.of(new Generation(reply))).build(), decisions);
			ToolResponseMessage results = withStoredIds(toolResults(result), reply, turn);
			if (result.returnDirect()) {
				this.sessionService.appendMessage(sessionId, results);
				finish(sessionId);
				ChatResponse direct = ChatResponse.builder()
					.generations(ToolExecutionResult.buildGenerations(result))
					.build();
				return new Start(request, ChatClientResponse.builder()
					.chatResponse(direct)
					.context(new HashMap<>(request.context()))
					.build());
			}
			List<Message> withResults = new ArrayList<>(messages.size() + 1);
			withResults.add(results);
			withResults.addAll(messages);
			return new Start(request.mutate().prompt(request.prompt().mutate().messages(withResults).build()).build(),
					null);
		}
		catch (RuntimeException ex) {
			if (ran.get() > 0) {
				answerFailed(sessionId, reply, turn, ex);
			}
			end(sessionId, StopReason.ERROR);
			throw ex;
		}
	}

	/**
	 * The options with tools that count their runs, so a failed resumption knows whether a
	 * call ran before it failed.
	 */
	private static @Nullable ChatOptions counting(@Nullable ChatOptions options, AtomicInteger ran) {
		if (!(options instanceof ToolCallingChatOptions toolOptions) || toolOptions.getToolCallbacks() == null) {
			return options;
		}
		List<ToolCallback> counted = toolOptions.getToolCallbacks()
			.stream()
			.<ToolCallback>map(callback -> new CountingToolCallback(callback, ran))
			.toList();
		return toolOptions.mutate().toolCallbacks(counted).build();
	}

	/**
	 * Answers every call of the reply in the log after a resumption failed once a call
	 * had run: the calls are no longer open, so deciding again cannot run one twice, and
	 * the model learns what happened in the next turn.
	 */
	private void answerFailed(String sessionId, AssistantMessage reply, List<Message> turn, RuntimeException cause) {
		String message = "The tool calls failed after some of them ran: " + cause.getMessage();
		List<ToolResponseMessage.ToolResponse> responses = reply.getToolCalls()
			.stream()
			.map(toolCall -> new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), message))
			.toList();
		try {
			this.sessionService.appendMessage(sessionId,
					withStoredIds(ToolResponseMessage.builder().responses(responses).build(), reply, turn));
		}
		catch (RuntimeException ex) {
			cause.addSuppressed(ex);
		}
	}

	/**
	 * The tool confirmation question the session waits on. An earlier resumption that
	 * failed before any call ran answered the question, but left the calls unanswered in
	 * the log: they can be decided on again.
	 */
	private Interaction waitingQuestion(String sessionId) {
		SessionEvent pending = this.sessionService.pendingInteraction(sessionId);
		if (pending == null) {
			SessionEvent newest = newestConversationEvent(sessionId);
			if (newest != null && newest.hasToolCalls()) {
				List<SessionEvent> questions = this.sessionService
					.getEvents(sessionId, EventFilter.builder().eventTypes(Set.of(EventType.INTERACTION)).build())
					.stream()
					.filter(InteractionEvents::isRequired)
					.toList();
				SessionEvent last = questions.isEmpty() ? null : questions.get(questions.size() - 1);
				Interaction asked = (last != null) ? InteractionEvents.interaction(last) : null;
				// Only the question about the unanswered calls: not one of an earlier turn
				if (asked != null && Interaction.KIND_TOOL_CONFIRMATION.equals(asked.kind())
						&& sameCalls(ToolConfirmations.reply(asked), (AssistantMessage) newest.getMessage())) {
					pending = last;
				}
			}
		}
		Interaction question = (pending != null) ? InteractionEvents.interaction(pending) : null;
		if (question == null || !Interaction.KIND_TOOL_CONFIRMATION.equals(question.kind())) {
			throw new IllegalArgumentException("Session '" + sessionId + "' is not waiting on a tool call decision");
		}
		return question;
	}

	/** The same calls in the same order, whatever their ids. */
	private static boolean sameCalls(AssistantMessage reply, AssistantMessage stored) {
		List<AssistantMessage.ToolCall> calls = reply.getToolCalls();
		List<AssistantMessage.ToolCall> storedCalls = stored.getToolCalls();
		if (calls.size() != storedCalls.size()) {
			return false;
		}
		for (int i = 0; i < calls.size(); i++) {
			if (!calls.get(i).name().equals(storedCalls.get(i).name())
					|| !String.valueOf(calls.get(i).arguments()).equals(String.valueOf(storedCalls.get(i).arguments()))) {
				return false;
			}
		}
		return true;
	}

	/** An allowed call runs with a tool of the request; checked before anything is recorded. */
	private static void requireTools(String sessionId, AssistantMessage reply, ToolCallDecisions decisions,
			@Nullable ChatOptions options) {
		for (AssistantMessage.ToolCall toolCall : reply.getToolCalls()) {
			ToolCallDecision decision = decisions.decisionOn(toolCall.id());
			if (decision == null || !decision.allowed()) {
				continue;
			}
			boolean carried = options instanceof ToolCallingChatOptions toolOptions
					&& toolOptions.getToolCallbacks() != null && toolOptions.getToolCallbacks()
						.stream()
						.anyMatch(callback -> callback.getToolDefinition().name().equals(toolCall.name()));
			if (!carried) {
				throw new IllegalArgumentException("The request must carry the tool '" + toolCall.name()
						+ "' to run the allowed call '" + toolCall.id() + "' of session '" + sessionId + "'");
			}
		}
	}

	/** Parks the turn when the loop paused, or ends it. */
	private void complete(String sessionId, @Nullable ChatResponse response) {
		if (response != null && ToolCallDecisions.isAwaitingDecision(response)) {
			park(sessionId, response);
		}
		else {
			finish(sessionId);
		}
	}

	private void park(String sessionId, ChatResponse response) {
		AssistantMessage reply = response.getResults()
			.stream()
			.map(Generation::getOutput)
			.filter(AssistantMessage::hasToolCalls)
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("The paused reply has no tool calls"));
		List<String> related = new ArrayList<>();
		SessionEvent stored = newestConversationEvent(sessionId);
		if (stored != null && stored.hasToolCalls()) {
			related.add(stored.getId());
		}
		else {
			logger.warn("Parking the turn of session [{}] without the assistant event: the reply with the tool "
					+ "calls is not the newest message in the log; is the memory advisor registered?", sessionId);
		}
		try {
			this.sessionService.park(sessionId,
					ToolConfirmations.question(reply, ToolCallDecisions.awaitingDecision(response)), related);
		}
		catch (IllegalStateException ex) {
			// Ended by someone else while the reply was in flight (interrupted, out of
			// budget): the reply stands and the calls stay in the log
			logger.warn("Could not park the turn of session [{}]: {}", sessionId, ex.getMessage());
		}
		catch (RuntimeException ex) {
			// The store failed: end the turn, so the session does not stay running
			end(sessionId, StopReason.ERROR);
			throw ex;
		}
	}

	/** Ends the completed turn, then compacts the session if the trigger fires. */
	private void finish(String sessionId) {
		if (!end(sessionId, StopReason.END_TURN) || this.compactionTrigger == null
				|| this.compactionStrategy == null) {
			return;
		}
		try {
			this.sessionService.compact(sessionId, this.compactionTrigger, this.compactionStrategy);
		}
		catch (RuntimeException ex) {
			logger.warn("Compaction failed for session [{}]; the event log is unchanged and compaction will be "
					+ "retried after the next turn", sessionId, ex);
		}
	}

	/**
	 * Ends the turn while it is still running; returns {@code false} when someone ended it
	 * already (interrupted, out of budget).
	 */
	private boolean end(String sessionId, StopReason stopReason) {
		try {
			Session session = this.sessionService.findById(sessionId);
			if (session == null || session.status() != SessionStatus.RUNNING) {
				return false;
			}
			this.sessionService.endTurn(sessionId, stopReason);
			return true;
		}
		catch (RuntimeException ex) {
			logger.warn("Could not end the turn of session [{}] with {}: {}", sessionId, stopReason, ex.getMessage());
			return false;
		}
	}

	/**
	 * The messages of the turn the paused reply ends, so the manager's limits count its
	 * earlier rounds. The window is one event, extended by the repository to the start of
	 * its turn.
	 */
	private List<Message> currentTurn(String sessionId) {
		return this.sessionService.getEvents(sessionId, EventFilter.active().toBuilder().lastN(1).build())
			.stream()
			.filter(SessionEvent::hasMessage)
			.map(SessionEvent::getMessage)
			.filter(message -> message.getMessageType() != MessageType.SYSTEM)
			.toList();
	}

	/**
	 * The newest message of the active view that is not a system message, or {@code null}.
	 */
	private @Nullable SessionEvent newestConversationEvent(String sessionId) {
		List<SessionEvent> events = this.sessionService.getEvents(sessionId,
				EventFilter.active().toBuilder().lastN(1).build());
		for (int i = events.size() - 1; i >= 0; i--) {
			if (events.get(i).hasMessage() && events.get(i).getMessageType() != MessageType.SYSTEM) {
				return events.get(i);
			}
		}
		return null;
	}

	private static ToolResponseMessage toolResults(ToolExecutionResult result) {
		List<Message> history = result.conversationHistory();
		if (!history.isEmpty() && history.get(history.size() - 1) instanceof ToolResponseMessage results) {
			return results;
		}
		throw new IllegalStateException("The tool calling manager returned no tool results");
	}

	/**
	 * The results under the ids of the calls the log holds. A model that gives its calls no
	 * ids gets some from the manager when the loop pauses, after the memory advisor stored
	 * the reply; the manager keeps the order of the calls, so the stored call at the same
	 * place is the one a result answers.
	 */
	private static ToolResponseMessage withStoredIds(ToolResponseMessage results, AssistantMessage reply,
			List<Message> turn) {
		if (turn.isEmpty() || !(turn.get(turn.size() - 1) instanceof AssistantMessage stored)
				|| !sameCalls(reply, stored) || stored.getToolCalls().equals(reply.getToolCalls())) {
			return results;
		}
		Map<String, String> storedIds = new HashMap<>();
		for (int i = 0; i < reply.getToolCalls().size(); i++) {
			storedIds.put(reply.getToolCalls().get(i).id(), stored.getToolCalls().get(i).id());
		}
		List<ToolResponseMessage.ToolResponse> responses = results.getResponses()
			.stream()
			.map(response -> new ToolResponseMessage.ToolResponse(
					storedIds.getOrDefault(response.id(), response.id()), response.name(), response.responseData()))
			.toList();
		return ToolResponseMessage.builder().responses(responses).metadata(results.getMetadata()).build();
	}

	private static List<ToolCallDecision> decisions(Map<String, @Nullable Object> context) {
		Object value = context.get(TOOL_CALL_DECISIONS_CONTEXT_KEY);
		if (value == null) {
			return List.of();
		}
		if (value instanceof ToolCallDecisions decisions) {
			return decisions.decisions();
		}
		if (value instanceof ToolCallDecision decision) {
			return List.of(decision);
		}
		if (value instanceof Collection<?> collection && !collection.isEmpty()
				&& collection.stream().allMatch(ToolCallDecision.class::isInstance)) {
			return collection.stream().map(ToolCallDecision.class::cast).toList();
		}
		throw new IllegalArgumentException("Advisor context value for '" + TOOL_CALL_DECISIONS_CONTEXT_KEY
				+ "' must be a ToolCallDecisions, a ToolCallDecision or a collection of them but was "
				+ value.getClass().getName());
	}

	/** A tool that counts its runs. */
	private static final class CountingToolCallback implements ToolCallback {

		private final ToolCallback delegate;

		private final AtomicInteger ran;

		CountingToolCallback(ToolCallback delegate, AtomicInteger ran) {
			this.delegate = delegate;
			this.ran = ran;
		}

		@Override
		public ToolDefinition getToolDefinition() {
			return this.delegate.getToolDefinition();
		}

		@Override
		public ToolMetadata getToolMetadata() {
			return this.delegate.getToolMetadata();
		}

		@Override
		public String call(String toolInput) {
			this.ran.incrementAndGet();
			return this.delegate.call(toolInput);
		}

		@Override
		public String call(String toolInput, @Nullable ToolContext toolContext) {
			this.ran.incrementAndGet();
			return this.delegate.call(toolInput, toolContext);
		}

	}

	/** The request to forward, or the response when there is nothing to forward. */
	private record Start(ChatClientRequest request, @Nullable ChatClientResponse response) {
	}

	public static final class Builder {

		private final SessionService sessionService;

		private ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();

		private int order = DEFAULT_ORDER;

		private Scheduler scheduler = BaseAdvisor.DEFAULT_SCHEDULER;

		@Nullable private CompactionTrigger compactionTrigger;

		@Nullable private CompactionStrategy compactionStrategy;

		@Nullable private AgentSnapshot agent;

		private Builder(SessionService sessionService) {
			Assert.notNull(sessionService, "sessionService must not be null");
			this.sessionService = sessionService;
		}

		/**
		 * The manager that runs the tool calls of a resumed turn with the decisions;
		 * defaults to {@code ToolCallingManager.builder().build()}. Give it the
		 * tool-calling advisor's own manager, so the confirmation policy and the tool call
		 * limits are the same on both sides of the pause.
		 */
		public Builder toolCallingManager(ToolCallingManager toolCallingManager) {
			Assert.notNull(toolCallingManager, "toolCallingManager must not be null");
			this.toolCallingManager = toolCallingManager;
			return this;
		}

		/** Keep it outside the tool-calling advisor (a lower value than its order); defaults to {@link #DEFAULT_ORDER}. */
		public Builder order(int order) {
			this.order = order;
			return this;
		}

		/** The scheduler the session writes of a streamed call run on. */
		public Builder scheduler(Scheduler scheduler) {
			Assert.notNull(scheduler, "scheduler must not be null");
			this.scheduler = scheduler;
			return this;
		}

		/** When to compact once a turn has ended; set together with {@link #compactionStrategy}. */
		public Builder compactionTrigger(CompactionTrigger trigger) {
			this.compactionTrigger = trigger;
			return this;
		}

		/** How to compact; set together with {@link #compactionTrigger}. */
		public Builder compactionStrategy(CompactionStrategy strategy) {
			this.compactionStrategy = strategy;
			return this;
		}

		/**
		 * The agent this {@code ChatClient} runs: a session the advisor creates on first use
		 * is stamped with it ({@link SessionAttributes#agent(Session)}). Metadata given with
		 * {@link SessionMemoryAdvisor#SESSION_METADATA_CONTEXT_KEY} that names an agent
		 * replaces the snapshot as a whole; an existing session is never changed.
		 */
		public Builder agent(@Nullable AgentSnapshot agent) {
			this.agent = agent;
			return this;
		}

		public SessionTurnAdvisor build() {
			if ((this.compactionTrigger == null) != (this.compactionStrategy == null)) {
				throw new IllegalArgumentException(
						"compactionTrigger and compactionStrategy must be set together — set both or neither");
			}
			return new SessionTurnAdvisor(this);
		}

	}

}
