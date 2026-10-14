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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.context.ContextSnapshotFactory;
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
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallDecision;
import org.springframework.ai.model.tool.ToolCallDecisions;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.EventType;
import org.springframework.ai.session.Interaction;
import org.springframework.ai.session.InteractionEvents;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
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
 * {@link SessionMemoryAdvisor#SESSION_METADATA_CONTEXT_KEY}, such as the agent
 * attributes.</li>
 * <li>Starts the turn ({@link SessionService#startTurn}). The start is a compare-and-set,
 * so a second concurrent turn on the same session is rejected.</li>
 * <li>Ends the turn with {@link StopReason#END_TURN} once the call returns, or with
 * {@link StopReason#ERROR} when it fails or a stream errors ({@link StopReason#INTERRUPTED}
 * when a stream is cancelled). A turn someone else ended (interrupted, or out of budget)
 * is left as it is. After a loop that ended on a {@code returnDirect} tool or a breached
 * tool call limit, it first stores the reply's tool results, which no model round
 * stored.</li>
 * <li>Compacts the session once the turn has ended, if a {@link Builder#compactionTrigger
 * trigger} fires: before the call returns, or after it on the scheduler with
 * {@link Builder#asyncCompaction(boolean)}. Compaction is best-effort: a failure is logged
 * and the reply returned, and it is retried after the next turn. It appends a boundary
 * with an optimistic compare-and-swap, so of two concurrent compactions only the first
 * lands, and a compaction that a new turn's writes overtake is skipped and retried.</li>
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

	/**
	 * Context key for the run a call belongs to: the caller's idempotency key for one
	 * {@code ChatClient} call, a {@code String}. A retry of the call uses the same run id,
	 * a new call a new one; the call that resumes a parked turn may keep the run id of the
	 * call it resumes. The turn records it ({@link SessionService#startTurn(String,
	 * String)}), and the {@link RunScopedSessionEventIdGenerator} derives event ids from it.
	 */
	public static final String RUN_ID_CONTEXT_KEY = "session_run_id";

	/**
	 * Context key that marks a request as part of a turn this advisor runs, so the
	 * {@link SessionMemoryAdvisor} can stop the loop when the turn was interrupted.
	 */
	static final String TURN_CONTEXT_KEY = "chat_memory_session_turn";

	/** Context key for the turn's round counter, which the memory advisor advances. */
	static final String ROUND_CONTEXT_KEY = "chat_memory_session_round";

	/** Context key for how the turn began: a new call or a resumption. */
	static final String PHASE_CONTEXT_KEY = "chat_memory_session_phase";

	/** The result a tool call gets in the log when the loop ended before it ran. */
	public static final String NOT_RUN_TOOL_RESULT = "The tool call did not run: the tool call limit was reached.";

	private static final Logger logger = LoggerFactory.getLogger(SessionTurnAdvisor.class);

	/** Carries the caller's thread-local context, such as the security context, to an async compaction. */
	private static final ContextSnapshotFactory CONTEXT_SNAPSHOTS = ContextSnapshotFactory.builder().build();

	private final SessionService sessionService;

	private final ToolCallingManager toolCallingManager;

	private final int order;

	private final Scheduler scheduler;

	@Nullable private final CompactionTrigger compactionTrigger;

	@Nullable private final CompactionStrategy compactionStrategy;

	private final boolean asyncCompaction;

	/** The sessions an asynchronous compaction of this advisor runs on, so it runs once each. */
	private final Set<String> compacting = ConcurrentHashMap.newKeySet();

	private SessionTurnAdvisor(Builder builder) {
		this.sessionService = builder.sessionService;
		this.toolCallingManager = builder.toolCallingManager;
		this.order = builder.order;
		this.scheduler = builder.scheduler;
		this.compactionTrigger = builder.compactionTrigger;
		this.compactionStrategy = builder.compactionStrategy;
		this.asyncCompaction = builder.asyncCompaction;
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
			end(sessionId, start.runId(), StopReason.ERROR);
			throw ex;
		}
		complete(sessionId, start.runId(), response.chatResponse());
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
			// A paused stream ends on a chunk with the ids, which the aggregated reply drops;
			// a returnDirect result or a breached limit ends it on a chunk of its own
			AtomicReference<ChatResponse> paused = new AtomicReference<>();
			AtomicReference<ChatResponse> unanswered = new AtomicReference<>();
			// Back on the scheduler, so the session writes never run on the model's thread
			return chain.nextStream(start.request())
				.doOnNext(response -> {
					if (ToolCallDecisions.isAwaitingDecision(response.chatResponse())) {
						paused.set(response.chatResponse());
					}
					else if (endsWithoutModel(response.chatResponse())) {
						unanswered.set(response.chatResponse());
					}
				})
				.publishOn(this.scheduler)
				.doOnError(ex -> end(sessionId, start.runId(), StopReason.ERROR))
				.doOnComplete(() -> complete(sessionId, start.runId(),
						(paused.get() != null) ? paused.get() : unanswered.get()))
				.doOnCancel(() -> end(sessionId, start.runId(), StopReason.INTERRUPTED));
		});
	}

	/**
	 * Starts the turn, or resumes the parked one when the request carries decisions. A
	 * resumption whose results go straight back to the caller has the response already.
	 * The turn always has a run: the caller's run id, or one made up for this call, so it
	 * is ended only while it is still this call's.
	 */
	private Start begin(ChatClientRequest request, String sessionId) {
		List<ToolCallDecision> decisions = decisions(request.context());
		String callerRunId = runId(request.context());
		String turnRunId = (callerRunId != null) ? callerRunId : UUID.randomUUID().toString();
		SessionContexts.findOrCreate(this.sessionService, sessionId, request.context());
		if (decisions.isEmpty()) {
			this.sessionService.startTurn(sessionId, turnRunId);
			return new Start(inTurn(request, turnRunId, "start"), null, turnRunId);
		}
		return resume(request, sessionId, turnRunId, decisions);
	}

	/**
	 * Marks the request as part of the turn of the run, with a round counter every round
	 * of the tool loop shares: the loop copies the context of the request into each round.
	 */
	private static ChatClientRequest inTurn(ChatClientRequest request, String turnRunId, String phase) {
		return request.mutate()
			.context(TURN_CONTEXT_KEY, turnRunId)
			.context(PHASE_CONTEXT_KEY, phase)
			.context(ROUND_CONTEXT_KEY, new AtomicInteger())
			.build();
	}

	/**
	 * How the turn began: {@code start} for a new call, {@code resume-<question event id>}
	 * for a resumption, so a call and its resumption under one run id get their own event
	 * ids; {@code null} outside a turn this advisor runs.
	 */
	static @Nullable String phase(Map<String, @Nullable Object> context) {
		return (context.get(PHASE_CONTEXT_KEY) instanceof String phase) ? phase : null;
	}

	/** The run the call belongs to, or {@code null} when the caller gave none. */
	static @Nullable String runId(Map<String, @Nullable Object> context) {
		Object runId = context.get(RUN_ID_CONTEXT_KEY);
		if (runId == null) {
			return null;
		}
		if (!(runId instanceof String id) || id.isBlank()) {
			throw new IllegalArgumentException(
					"Advisor context value for '" + RUN_ID_CONTEXT_KEY + "' must be a non-blank String but was " + runId);
		}
		return id;
	}

	/** The current round of the turn, or {@code null} outside a turn this advisor runs. */
	static @Nullable Integer round(Map<String, @Nullable Object> context) {
		return (context.get(ROUND_CONTEXT_KEY) instanceof AtomicInteger round) ? round.get() : null;
	}

	/** Starts the next round of the turn; a no-op outside a turn this advisor runs. */
	static void nextRound(Map<String, @Nullable Object> context) {
		if (context.get(ROUND_CONTEXT_KEY) instanceof AtomicInteger round) {
			round.incrementAndGet();
		}
	}

	/**
	 * The run of the turn a {@link SessionTurnAdvisor} runs the request or response in, or
	 * {@code null} outside one.
	 */
	static @Nullable String turnRunId(Map<String, @Nullable Object> context) {
		return (context.get(TURN_CONTEXT_KEY) instanceof String turnRunId) ? turnRunId : null;
	}

	private Start resume(ChatClientRequest request, String sessionId, String turnRunId,
			List<ToolCallDecision> given) {
		List<Message> messages = request.prompt().getInstructions();
		if (messages.stream().anyMatch(UserMessage.class::isInstance)) {
			throw new IllegalArgumentException("A request that decides on tool calls of session '" + sessionId
					+ "' carries no new user message; send the text in the next turn");
		}
		SessionEvent asked = waitingQuestion(sessionId);
		Interaction question = InteractionEvents.interaction(asked);
		Assert.state(question != null, "redundant check that should never fail, but here to help NullAway");
		// The reply comes from the log, never from the caller
		AssistantMessage reply = ToolConfirmations.reply(question);
		ToolCallDecisions decisions = ToolConfirmations.complete(question, given);
		requireTools(sessionId, reply, decisions, request.prompt().getOptions());

		// Starts the turn; the compare-and-set makes the resumption exclusive
		this.sessionService.resume(sessionId, turnRunId, ToolConfirmations.asAnswer(decisions));
		AtomicInteger ran = new AtomicInteger();
		try {
			List<Message> turn = currentTurn(sessionId);
			ToolExecutionResult result = this.toolCallingManager.executeToolCalls(
					new Prompt(turn, counting(request.prompt().getOptions(), ran)),
					ChatResponse.builder().generations(List.of(new Generation(reply))).build(), decisions);
			ToolResponseMessage results = toolResults(result);
			if (result.returnDirect()) {
				this.sessionService.appendMessage(sessionId, results);
				finish(sessionId, turnRunId);
				ChatResponse direct = ChatResponse.builder()
					.generations(ToolExecutionResult.buildGenerations(result))
					.build();
				return new Start(request, ChatClientResponse.builder()
					.chatResponse(direct)
					.context(new HashMap<>(request.context()))
					.build(), turnRunId);
			}
			List<Message> withResults = new ArrayList<>(messages.size() + 1);
			withResults.add(results);
			withResults.addAll(messages);
			return new Start(inTurn(request.mutate().prompt(request.prompt().mutate().messages(withResults).build()).build(),
					turnRunId, "resume-" + asked.getId()), null, turnRunId);
		}
		catch (RuntimeException ex) {
			if (ran.get() > 0) {
				answerFailed(sessionId, reply, ex);
			}
			end(sessionId, turnRunId, StopReason.ERROR);
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
	private void answerFailed(String sessionId, AssistantMessage reply, RuntimeException cause) {
		String message = "The tool calls failed after some of them ran: " + cause.getMessage();
		List<ToolResponseMessage.ToolResponse> responses = reply.getToolCalls()
			.stream()
			.map(toolCall -> new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), message))
			.toList();
		try {
			this.sessionService.appendMessage(sessionId, ToolResponseMessage.builder().responses(responses).build());
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
	private SessionEvent waitingQuestion(String sessionId) {
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
		if (pending == null || question == null || !Interaction.KIND_TOOL_CONFIRMATION.equals(question.kind())) {
			throw new IllegalArgumentException("Session '" + sessionId + "' is not waiting on a tool call decision");
		}
		return pending;
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
		List<String> keys = ToolCallDecisions.toolCallKeys(reply);
		for (int i = 0; i < keys.size(); i++) {
			AssistantMessage.ToolCall toolCall = reply.getToolCalls().get(i);
			ToolCallDecision decision = decisions.decisionOn(keys.get(i));
			if (decision == null || !decision.allowed()) {
				continue;
			}
			boolean carried = options instanceof ToolCallingChatOptions toolOptions
					&& toolOptions.getToolCallbacks() != null && toolOptions.getToolCallbacks()
						.stream()
						.anyMatch(callback -> callback.getToolDefinition().name().equals(toolCall.name()));
			if (!carried) {
				throw new IllegalArgumentException("The request must carry the tool '" + toolCall.name()
						+ "' to run the allowed call '" + keys.get(i) + "' of session '" + sessionId + "'");
			}
		}
	}

	/** Parks the turn when the loop paused, or ends it. */
	private void complete(String sessionId, String turnRunId, @Nullable ChatResponse response) {
		if (response != null && ToolCallDecisions.isAwaitingDecision(response)) {
			park(sessionId, turnRunId, response);
		}
		else {
			if (response != null && endsWithoutModel(response)) {
				storeResults(sessionId, turnRunId, response);
			}
			finish(sessionId, turnRunId);
		}
	}

	/**
	 * Whether the loop ended on a reply's tool calls without a model round after them: a
	 * {@code returnDirect} result or a breached tool call limit. No round stored the
	 * results then.
	 */
	private static boolean endsWithoutModel(@Nullable ChatResponse response) {
		return response != null && response.getResults().stream().map(Generation::getMetadata).anyMatch(metadata ->
				ToolExecutionResult.FINISH_REASON.equals(metadata.getFinishReason())
						|| ToolCallLimitExceededException.FINISH_REASON.equals(metadata.getFinishReason()));
	}

	/**
	 * Answers the stored reply's tool calls in the log after a loop that ended without a
	 * model round: with the results that came back directly, or, for a breached limit, with
	 * the results of the calls that ran, the breach for the call that broke it and a note
	 * for the calls after it. The results come in the order of the calls. Only while the
	 * turn is still this run's, and once: the id derives from the reply's.
	 */
	private void storeResults(String sessionId, String turnRunId, ChatResponse response) {
		try {
			Session session = this.sessionService.findById(sessionId);
			SessionEvent stored = newestConversationEvent(sessionId);
			if (session == null || !turnRunId.equals(session.runId()) || stored == null || !stored.hasToolCalls()) {
				return;
			}
			List<AssistantMessage.ToolCall> calls = ((AssistantMessage) stored.getMessage()).getToolCalls();
			List<String> answers = answers(response, calls.size());
			List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
			for (int i = 0; i < calls.size(); i++) {
				responses.add(new ToolResponseMessage.ToolResponse(calls.get(i).id(), calls.get(i).name(), answers.get(i)));
			}
			this.sessionService.appendEvent(SessionEvent.builder()
				.id(stored.getId() + "-results")
				.sessionId(sessionId)
				.message(ToolResponseMessage.builder().responses(responses).build())
				.build());
		}
		catch (RuntimeException ex) {
			logger.warn("Could not store the tool results of session [{}]: {}", sessionId, ex.getMessage());
		}
	}

	/** One answer per call, in the order of the calls. */
	private static List<String> answers(ChatResponse response, int calls) {
		List<String> answers = new ArrayList<>(calls);
		for (Generation generation : response.getResults()) {
			ChatGenerationMetadata metadata = generation.getMetadata();
			if (ToolExecutionResult.FINISH_REASON.equals(metadata.getFinishReason())) {
				answers.add(Objects.requireNonNullElse(generation.getOutput().getText(), ""));
			}
			else if (ToolCallLimitExceededException.FINISH_REASON.equals(metadata.getFinishReason())) {
				if (metadata.get(ToolCallLimitExceededException.METADATA_PARTIAL_TOOL_RESPONSES) instanceof List<?> ran) {
					ran.stream()
						.filter(ToolResponseMessage.ToolResponse.class::isInstance)
						.map(result -> ((ToolResponseMessage.ToolResponse) result).responseData())
						.forEach(answers::add);
				}
				answers.add(Objects.requireNonNullElse(generation.getOutput().getText(), ""));
			}
		}
		while (answers.size() < calls) {
			answers.add(NOT_RUN_TOOL_RESULT);
		}
		return answers.subList(0, calls);
	}

	private void park(String sessionId, String turnRunId, ChatResponse response) {
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
			this.sessionService.park(sessionId, turnRunId,
					ToolConfirmations.question(reply, ToolCallDecisions.awaitingDecision(response)), related);
		}
		catch (IllegalStateException ex) {
			// Ended by someone else while the reply was in flight (interrupted, out of
			// budget): the reply stands and the calls stay in the log
			logger.warn("Could not park the turn of session [{}]: {}", sessionId, ex.getMessage());
		}
		catch (RuntimeException ex) {
			// The store failed: end the turn, so the session does not stay running
			end(sessionId, turnRunId, StopReason.ERROR);
			throw ex;
		}
	}

	/**
	 * Ends the completed turn, then compacts the session if the trigger fires: now, or on
	 * the scheduler when compaction is asynchronous and none runs on the session yet.
	 */
	private void finish(String sessionId, String turnRunId) {
		if (!end(sessionId, turnRunId, StopReason.END_TURN) || this.compactionTrigger == null
				|| this.compactionStrategy == null) {
			return;
		}
		if (!this.asyncCompaction) {
			compact(sessionId, this.compactionTrigger, this.compactionStrategy);
		}
		else if (this.compacting.add(sessionId)) {
			CompactionTrigger trigger = this.compactionTrigger;
			CompactionStrategy strategy = this.compactionStrategy;
			// The caller's thread-local context goes with it: an authorizing SessionService
			// takes the caller from the security context, and tracing keeps its parent
			Runnable task = CONTEXT_SNAPSHOTS.captureAll().wrap(() -> {
				try {
					compact(sessionId, trigger, strategy);
				}
				finally {
					this.compacting.remove(sessionId);
				}
			});
			try {
				this.scheduler.schedule(task);
			}
			catch (RuntimeException ex) {
				this.compacting.remove(sessionId);
				logger.warn("Could not schedule the compaction of session [{}]; it will be retried after the next "
						+ "turn", sessionId, ex);
			}
		}
	}

	/** Whether an asynchronous compaction of this advisor runs on the session. */
	boolean isCompacting(String sessionId) {
		return this.compacting.contains(sessionId);
	}

	/** Compacts the session if the trigger fires; a failure is logged, not thrown. */
	private void compact(String sessionId, CompactionTrigger trigger, CompactionStrategy strategy) {
		try {
			this.sessionService.compact(sessionId, trigger, strategy);
		}
		catch (RuntimeException ex) {
			logger.warn("Compaction failed for session [{}]; the event log is unchanged and compaction will be "
					+ "retried after the next turn", sessionId, ex);
		}
	}

	/**
	 * Ends the run's turn while it is still running; returns {@code false} when someone
	 * ended it already (interrupted, out of budget), whatever turn runs now.
	 */
	private boolean end(String sessionId, String turnRunId, StopReason stopReason) {
		try {
			this.sessionService.endTurn(sessionId, turnRunId, stopReason, List.of());
			return true;
		}
		catch (IllegalStateException ex) {
			logger.debug("The turn of run [{}] of session [{}] was already ended: {}", turnRunId, sessionId,
					ex.getMessage());
			return false;
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
	private record Start(ChatClientRequest request, @Nullable ChatClientResponse response, String runId) {
	}

	public static final class Builder {

		private final SessionService sessionService;

		private ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();

		private int order = DEFAULT_ORDER;

		private Scheduler scheduler = BaseAdvisor.DEFAULT_SCHEDULER;

		@Nullable private CompactionTrigger compactionTrigger;

		@Nullable private CompactionStrategy compactionStrategy;

		private boolean asyncCompaction;

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
		 * Whether compaction runs after the call returns, on the {@link #scheduler}, rather
		 * than before it: a summarizing strategy then adds no model call to the reply's
		 * latency. For a {@code call()} it runs with the caller's thread-local context (the
		 * security context, the current observation), captured through Micrometer's context
		 * propagation; a {@code stream()} ends on the scheduler, which has none to carry. This
		 * advisor runs one compaction per session at a time; across advisors, the
		 * compare-and-swap keeps only one. A new turn that writes to the session while it
		 * runs makes it skip, and it is retried after that turn. With an immediate
		 * scheduler it runs before the call returns after all. Defaults to {@code false}.
		 */
		public Builder asyncCompaction(boolean asyncCompaction) {
			this.asyncCompaction = asyncCompaction;
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
