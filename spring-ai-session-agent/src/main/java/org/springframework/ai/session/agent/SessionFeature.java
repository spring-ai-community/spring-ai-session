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

package org.springframework.ai.session.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.agent.AgentEvent;
import org.springframework.ai.agent.AgentEventListener;
import org.springframework.ai.agent.Conversation;
import org.springframework.ai.agent.ConversationEvent.MessageEvent;
import org.springframework.ai.agent.ConversationInterceptor;
import org.springframework.ai.agent.ExecutionContext;
import org.springframework.ai.agent.RunEvent;
import org.springframework.ai.agent.RunEvent.RunCompletedEvent;
import org.springframework.ai.agent.RunEvent.RunFailedEvent;
import org.springframework.ai.agent.RunEvent.RunInterruptedEvent;
import org.springframework.ai.agent.RunEvent.RunStartedEvent;
import org.springframework.ai.agent.RunIdentification;
import org.springframework.ai.agent.RunListener;
import org.springframework.ai.agent.TurnProgressedEvent;
import org.springframework.ai.agent.graph.NodeEvent.NodeCompletedEvent;
import org.springframework.ai.agent.model.ModelEvent.ChatResponseEvent;
import org.springframework.ai.agent.model.ModelEvent.ModelCallCompletedEvent;
import org.springframework.ai.agent.serialization.MessageModule;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.tool.ToolCallDecision;
import org.springframework.ai.model.tool.ToolCallDecisions;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.EventType;
import org.springframework.ai.session.Interaction;
import org.springframework.ai.session.InterruptEvents;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionAlreadyExistsException;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.SessionStatus;
import org.springframework.ai.session.SessionStatusEvents;
import org.springframework.ai.session.SessionTreeEvents;
import org.springframework.ai.session.StopReason;
import org.springframework.ai.session.TokenUsage;
import org.springframework.ai.session.ToolConfirmations;
import org.springframework.ai.session.UsageEvents;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.CompactionStrategy;
import org.springframework.ai.session.compaction.CompactionTrigger;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.util.Assert;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps a spring-ai-agent run in a session tree: the main conversation in the session the
 * run's {@link RunIdentification#sessionId() session id} names, every nested conversation
 * in a child session keyed by its context path, the run as a turn that names its run id,
 * the agent's node, model, tool and custom events as typed log events, and an
 * interruption as a parked turn that a resume answers, on any node, out of the session.
 * <p>
 * Messages get ids derived from the run, the path, their position and their content, so a
 * re-attempt of the same run that appends the same messages again appends nothing. With
 * {@link #checkpointer()} given to a {@code CheckpointerFeature}, the log is the one
 * durable store of the run: checkpoints are events of the root session, and the
 * conversations they resume come from the sessions.
 * <p>
 * Enable it on the top-level agent; nested agents are observed through their paths. A
 * run resumed from a session that was parked by an interruption records the answer it is
 * resumed with as an {@link EventType#INTERACTION} event.
 *
 * @author Christian Tzolov
 * @since 0.11.0
 */
public final class SessionFeature implements RunListener, ConversationInterceptor, AgentEventListener<AgentEvent> {

	/** Metadata of a child session: how many messages it inherited from its parent at creation. */
	public static final String INHERITED_MESSAGES = "inheritedMessages";

	private static final Logger logger = LoggerFactory.getLogger(SessionFeature.class);

	private static final ParameterizedTypeReference<Object> ANY = ParameterizedTypeReference.forType(Object.class);

	private final SessionService sessions;

	private final Map<String, Object> sessionMetadata;

	@Nullable private final CompactionTrigger trigger;

	@Nullable private final CompactionStrategy strategy;

	private final JsonMapper jsonMapper = JsonMapper.builder().addModule(new MessageModule()).build();

	private final AgentEventCodec codec = new AgentEventCodec(this.jsonMapper);

	final Map<String, RunState> runs = new ConcurrentHashMap<>();

	private SessionFeature(SessionService sessions, Map<String, Object> sessionMetadata,
			@Nullable CompactionTrigger trigger, @Nullable CompactionStrategy strategy) {
		this.sessions = sessions;
		this.sessionMetadata = Map.copyOf(sessionMetadata);
		this.trigger = trigger;
		this.strategy = strategy;
	}

	public static Builder builder(SessionService sessions) {
		return new Builder(sessions);
	}

	/** A checkpointer that keeps a run's checkpoints in its root session's log. */
	public SessionCheckpointer checkpointer() {
		return new SessionCheckpointer(this.sessions, this, this.jsonMapper);
	}

	/** The state of a run in flight, created on first sight; keyed by the session and the run. */
	RunState state(RunIdentification runId) {
		return this.runs.computeIfAbsent(key(runId), id -> new RunState());
	}

	private static String key(RunIdentification runId) {
		return runId.sessionId() + "\u0000" + runId.runId();
	}

	// --- the run is a turn ----------------------------------------------------------------

	@Override
	public void onRunStarted(RunStartedEvent event, ExecutionContext context) {
		RunIdentification runId = context.getRunId();
		String sessionId = runId.sessionId();
		RunState state = state(runId);
		try {
			state.sessions.put("", sessionId);
			state.rootConversation = context.getConversation();
			Session session = this.sessions.findById(sessionId);
			if (session == null) {
				this.sessions
					.create(CreateSessionRequest.builder().id(sessionId).metadata(this.sessionMetadata).build());
				state.stored.put("", 0);
			}
			else {
				endAbandonedAttempt(session, runId);
				context.getConversation().reset(this.sessions.getActiveMessages(sessionId));
				state.stored.putIfAbsent("", 0);
			}
			Object answer = resumeValue(context);
			if (answer != null && session != null && session.stopReason() == StopReason.REQUIRES_ACTION) {
				// Resumed with an answer to the question the session parked on: record it
				this.sessions.resume(sessionId, runId.runId(), encodeAnswer(answer));
			}
			else {
				this.sessions.startTurn(sessionId, runId.runId());
			}
		}
		catch (RuntimeException ex) {
			// The run cannot start (another run holds the session, or the session is archived):
			// forget it, so its end never touches a turn this run does not own
			this.runs.remove(key(runId));
			throw ex;
		}
	}

	/** A session left running by a crashed attempt of this run is ended, so the re-attempt can start. */
	private void endAbandonedAttempt(Session session, RunIdentification runId) {
		if (session.status() == SessionStatus.RUNNING && ownsTurn(session.id(), runId)) {
			logger.info("Run [{}] of session [{}] was left running by an earlier attempt; ending it", runId.runId(),
					session.id());
			this.sessions.endTurn(session.id(), StopReason.INTERRUPTED);
		}
	}

	/** Whether the newest status event of the session names this run: the turn is this run's to end. */
	private boolean ownsTurn(String sessionId, RunIdentification runId) {
		List<SessionEvent> newest = this.sessions.getEvents(sessionId,
				EventFilter.builder().eventTypes(Set.of(EventType.STATUS)).lastN(1).build());
		return !newest.isEmpty() && runId.runId().equals(SessionStatusEvents.runId(newest.get(0)));
	}

	/** An answer as the log stores it: decisions in the shape every integration writes, anything else as JSON. */
	private Object encodeAnswer(Object answer) {
		if (answer instanceof ToolCallDecision decision) {
			return ToolConfirmations.asAnswer(ToolCallDecisions.of(decision));
		}
		if (answer instanceof ToolCallDecisions decisions) {
			return ToolConfirmations.asAnswer(decisions);
		}
		if (answer instanceof java.util.Collection<?> decisions && !decisions.isEmpty()
				&& decisions.stream().allMatch(ToolCallDecision.class::isInstance)) {
			return ToolConfirmations
				.asAnswer(new ToolCallDecisions(decisions.stream().map(ToolCallDecision.class::cast).toList()));
		}
		return this.jsonMapper.convertValue(answer, Object.class);
	}

	/** The value the run was resumed with, left in place: the runtime offers no non-consuming read. */
	private static @Nullable Object resumeValue(ExecutionContext context) {
		Object value = context.consumeResumeValue(ANY);
		context.setResumeValue(value);
		return value;
	}

	@Override
	public void onRunCompleted(RunCompletedEvent event, ExecutionContext context) {
		endRun(context, StopReason.END_TURN, List.of(), null);
	}

	@Override
	public void onRunFailed(RunFailedEvent event, ExecutionContext context) {
		if (isCancellation(event.error())) {
			// The caller cancelled the run (an AgentRun was closed): the turn was interrupted, not failed
			endRun(context, StopReason.INTERRUPTED, List.of(),
					root -> this.sessions.appendEvent(InterruptEvents.of(root, event.error().getMessage())));
			return;
		}
		endRun(context, StopReason.ERROR, List.of(), null);
	}

	private static boolean isCancellation(Throwable error) {
		for (Throwable t = error; t != null; t = t.getCause()) {
			if (t instanceof CancellationException || t instanceof InterruptedException) {
				return true;
			}
		}
		return false;
	}

	@Override
	public void onRunInterrupted(RunInterruptedEvent event, ExecutionContext context) {
		RunIdentification runId = context.getRunId();
		RunState state = this.runs.remove(key(runId));
		if (state == null) {
			return;
		}
		String root = state.root();
		if (!ownsTurn(root, runId)) {
			logger.warn("Run [{}] does not hold the turn of session [{}]; leaving it as it is", runId.runId(), root);
			return;
		}
		Interaction question;
		if (event.payload() instanceof ToolConfirmationFeature.PendingToolCall pending) {
			// The same question as a paused tool-calling loop parks on; the gate names the
			// call by its tool
			AssistantMessage reply = AssistantMessage.builder()
				.content("")
				.toolCalls(List.of(
						new AssistantMessage.ToolCall(pending.name(), "function", pending.name(), pending.arguments())))
				.build();
			Interaction asked = ToolConfirmations.question(reply, List.of(pending.name()));
			question = new Interaction(asked.kind(), event.path(), asked.payload(), ToolCallDecision.class.getName());
		}
		else {
			question = new Interaction(Interaction.KIND_INTERRUPT, event.path(),
					this.jsonMapper.convertValue(event.payload(), Object.class), null);
		}
		try {
			this.sessions.park(root, question, List.of());
		}
		catch (IllegalStateException ex) {
			logger.warn("The turn of session [{}] was already ended: {}", root, ex.getMessage());
		}
	}

	/**
	 * Ends the run's turn, when the run holds it: optionally records something first (an
	 * interruption), then the status.
	 */
	private void endRun(ExecutionContext context, StopReason stopReason, List<String> eventIds,
			@Nullable Consumer<String> beforeEnding) {
		RunIdentification runId = context.getRunId();
		RunState state = this.runs.remove(key(runId));
		if (state == null) {
			return; // the run never started a turn
		}
		String root = state.root();
		if (!ownsTurn(root, runId)) {
			logger.warn("Run [{}] does not hold the turn of session [{}]; leaving it as it is", runId.runId(), root);
			return;
		}
		if (beforeEnding != null) {
			beforeEnding.accept(root);
		}
		try {
			this.sessions.endTurn(root, stopReason, eventIds);
		}
		catch (IllegalStateException ex) {
			logger.warn("The turn of session [{}] was already ended: {}", root, ex.getMessage());
		}
	}

	// --- the main conversation is the root session's log ---------------------------------

	@Override
	public List<Message> aroundUpdate(Joinpoint jp, List<Message> oldMessages, List<Message> newMessages,
			RunIdentification runId) {
		RunState state = this.runs.get(key(runId));
		if (state == null || !startsWith(newMessages, oldMessages)) {
			// Not a run this feature follows, or a reset: nothing to store
			return jp.proceed(oldMessages, newMessages, runId);
		}
		String sessionId = state.root();
		for (Message message : newMessages.subList(oldMessages.size(), newMessages.size())) {
			append(sessionId, runId.runId(), "", state, message);
		}
		if (this.trigger == null || this.strategy == null) {
			return jp.proceed(oldMessages, newMessages, runId);
		}
		// Compaction is a projection: when it happened, the conversation becomes the active
		// view the log now projects. It is best effort, as in the memory advisor: the
		// messages are in the log, so a failing summarizer must not fail the run
		try {
			CompactionResult result = this.sessions.compact(sessionId, this.trigger, this.strategy);
			if (result.isUnchanged()) {
				return jp.proceed(oldMessages, newMessages, runId);
			}
			return jp.proceed(oldMessages, this.sessions.getActiveMessages(sessionId), runId);
		}
		catch (RuntimeException ex) {
			logger.warn("Compaction of session [{}] failed; the conversation continues uncompacted", sessionId, ex);
			return jp.proceed(oldMessages, newMessages, runId);
		}
	}

	private void append(String sessionId, String runId, String path, RunState state, Message message) {
		int ordinal = state.nextOrdinal(path);
		this.sessions.appendEvent(SessionEvent.builder()
			.id(AgentEventCodec.messageId(runId, path, ordinal, message))
			.sessionId(sessionId)
			.message(message)
			.build());
	}

	private static boolean startsWith(List<Message> messages, List<Message> prefix) {
		return messages.size() >= prefix.size() && messages.subList(0, prefix.size()).equals(prefix);
	}

	// --- every event is a log event; nested paths are child sessions ----------------------

	@Override
	public void onEvent(AgentEvent event, ExecutionContext context) {
		if (event instanceof RunEvent || event instanceof ChatResponseEvent || event instanceof TurnProgressedEvent
				|| event instanceof MessageEvent) {
			return; // run events are the turn, chunks are streamed, messages come from the conversations
		}
		RunState state = this.runs.get(key(context.getRunId()));
		if (state == null) {
			return;
		}
		String sessionId = sessionFor(context, state);
		if (!context.getPath().isEmpty() && !isShared(context, state)) {
			syncNested(context, state, sessionId);
		}
		if (event instanceof ModelCallCompletedEvent completed) {
			// A completed model call is a USAGE event: its counts, model, place and size
			this.sessions.appendEvent(UsageEvents.of(sessionId, TokenUsage.of(completed.response().getMetadata().getUsage()),
					completed.response().getMetadata().getModel(), event.path(),
					completed.prompt().getInstructions().size()));
		}
		SessionEvent encoded = this.codec.encode(sessionId, event);
		if (encoded != null) {
			this.sessions.appendEvent(encoded);
		}
		if (event instanceof NodeCompletedEvent completed) {
			answerFromChild(context, state, sessionId, completed);
		}
	}

	/** The session of a context: the root's, a shared parent's, or a child created on first sight. */
	private String sessionFor(ExecutionContext context, RunState state) {
		ExecutionContext parent = context.getParent();
		if (parent == null) {
			return state.root();
		}
		if (isShared(context, state)) {
			return sessionFor(parent, state);
		}
		String path = context.getPath();
		String known = state.sessions.get(path);
		if (known != null) {
			return known;
		}
		String parentId = sessionFor(parent, state);
		String segment = AgentEventCodec.lastSegment(path);
		String childId = parentId + "/" + segment;
		int inheritedMessages = context.getConversation().getMessages().size();
		try {
			this.sessions.createChild(parentId, CreateSessionRequest.builder()
				.id(childId)
				.metadata(SessionAttributes.AGENT_NAME, segment)
				.metadata(INHERITED_MESSAGES, inheritedMessages)
				.build());
			state.inherited.put(path, inheritedMessages);
			state.stored.put(path, 0);
		}
		catch (SessionAlreadyExistsException alreadyCreated) {
			// A re-attempt of the same run: the child exists, its log holds what was stored
			Session child = this.sessions.findById(childId);
			Object inherited = child != null ? child.metadata().get(INHERITED_MESSAGES) : null;
			state.inherited.put(path, inherited instanceof Number n ? n.intValue() : inheritedMessages);
			state.stored.put(path, 0);
			state.requestMarked.add(path);
		}
		state.sessions.put(path, childId);
		return childId;
	}

	/** A nested context sharing its parent's conversation writes to the parent's session. */
	private static boolean isShared(ExecutionContext context, RunState state) {
		ExecutionContext parent = context.getParent();
		Conversation conversation = context.getConversation();
		return parent != null && (conversation == parent.getConversation() || conversation == state.rootConversation);
	}

	/**
	 * Stores the messages a nested conversation gained since the last event from its path:
	 * nested conversations carry no interceptor, so their log is kept from what the
	 * context shows. The messages the nested conversation started with are stored too,
	 * so the child's log is self-contained.
	 */
	private void syncNested(ExecutionContext context, RunState state, String childId) {
		String path = context.getPath();
		List<Message> messages = context.getConversation().getMessages();
		int stored = state.stored.getOrDefault(path, 0);
		int inherited = state.inherited.getOrDefault(path, 0);
		Session child = null;
		for (int i = stored; i < messages.size(); i++) {
			Message message = messages.get(i);
			append(childId, context.getRunId().runId(), path, state, message);
			if (i >= inherited && message instanceof UserMessage && state.requestMarked.add(path)) {
				child = child != null ? child : this.sessions.findById(childId);
				if (child != null) {
					this.sessions.appendEvent(SessionTreeEvents.messageSent(child, message));
				}
			}
		}
	}

	/** The node that ran a nested agent completed: the parent records the child's answer. */
	private void answerFromChild(ExecutionContext context, RunState state, String sessionId,
			NodeCompletedEvent completed) {
		String childPath = context.getPath() + completed.node() + "/";
		String childId = state.sessions.get(childPath);
		if (childId == null || childId.equals(sessionId)) {
			return;
		}
		Session child = this.sessions.findById(childId);
		if (child == null) {
			return;
		}
		List<Message> childMessages = this.sessions.getActiveMessages(childId);
		Message answer = null;
		for (int i = childMessages.size() - 1; i >= 0; i--) {
			if (childMessages.get(i) instanceof AssistantMessage assistant) {
				answer = assistant;
				break;
			}
		}
		if (answer == null) {
			answer = new AssistantMessage(String.valueOf(completed.output()));
		}
		this.sessions.appendEvent(SessionTreeEvents.messageReceived(child, answer));
	}

	/** The id of the session a context path of a run maps to, as this feature derives it. */
	static String sessionIdFor(String rootSessionId, String path) {
		if (path.isEmpty()) {
			return rootSessionId;
		}
		StringBuilder id = new StringBuilder(rootSessionId);
		for (String segment : path.split("/")) {
			if (!segment.isEmpty()) {
				id.append('/').append(segment);
			}
		}
		return id.toString();
	}

	public static final class Builder {

		private final SessionService sessions;

		private final Map<String, Object> sessionMetadata = new LinkedHashMap<>();

		@Nullable private CompactionTrigger trigger;

		@Nullable private CompactionStrategy strategy;

		private Builder(SessionService sessions) {
			Assert.notNull(sessions, "sessions must not be null");
			this.sessions = sessions;
		}

		/**
		 * Metadata for the root sessions the feature creates, such as the agent attributes
		 * ({@link SessionAttributes#AGENT_NAME}) that label the agent the runs belong to. A
		 * nested agent's child session is labeled with its own name.
		 */
		public Builder sessionMetadata(Map<String, Object> sessionMetadata) {
			Assert.notNull(sessionMetadata, "sessionMetadata must not be null");
			this.sessionMetadata.putAll(sessionMetadata);
			return this;
		}

		/** Compacts the root session after each update the trigger fires on. */
		public Builder compaction(CompactionTrigger trigger, CompactionStrategy strategy) {
			Assert.notNull(trigger, "trigger must not be null");
			Assert.notNull(strategy, "strategy must not be null");
			this.trigger = trigger;
			this.strategy = strategy;
			return this;
		}

		public SessionFeature build() {
			return new SessionFeature(this.sessions, this.sessionMetadata, this.trigger, this.strategy);
		}

	}

}
