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
import org.springframework.ai.session.AgentSnapshot;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.SessionStatus;
import org.springframework.ai.session.StopReason;
import org.springframework.ai.session.compaction.CompactionStrategy;
import org.springframework.ai.session.compaction.CompactionTrigger;
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
 * so a second concurrent turn on the same session is rejected. A request that confirms
 * tool calls resumes the parked turn instead: the {@link ToolConfirmationAdvisor} starts
 * it.</li>
 * <li>Ends the turn with {@link StopReason#END_TURN} once the call returns, or with
 * {@link StopReason#ERROR} when it fails or a stream errors ({@link StopReason#INTERRUPTED}
 * when a stream is cancelled). A turn that already ended (parked for a tool
 * confirmation, interrupted, or out of budget) is left as it is.</li>
 * <li>Compacts the session once the turn has ended, if a {@link Builder#compactionTrigger
 * trigger} fires. Compaction is best-effort: a failure is logged and the reply returned,
 * and it is retried after the next turn. It appends a boundary with an optimistic
 * compare-and-swap, so of two concurrent compactions only the first lands.</li>
 * </ol>
 * The {@link ToolConfirmationAdvisor} and the {@link BudgetAdvisor} end or park the turn
 * this advisor starts, so they need it.
 *
 * @author Christian Tzolov
 * @since 0.11.0
 */
public final class SessionTurnAdvisor implements CallAdvisor, StreamAdvisor {

	/** Outside the tool-calling advisor ({@code HIGHEST_PRECEDENCE + 300}), so the loop runs inside the turn. */
	public static final int DEFAULT_ORDER = Ordered.HIGHEST_PRECEDENCE + 200;

	private static final Logger logger = LoggerFactory.getLogger(SessionTurnAdvisor.class);

	private final SessionService sessionService;

	private final int order;

	private final Scheduler scheduler;

	@Nullable private final CompactionTrigger compactionTrigger;

	@Nullable private final CompactionStrategy compactionStrategy;

	@Nullable private final AgentSnapshot agent;

	private SessionTurnAdvisor(Builder builder) {
		this.sessionService = builder.sessionService;
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
		SessionTurn turn = begin(request, sessionId);
		ChatClientResponse response;
		try {
			response = chain.nextCall(withTurn(request, turn));
		}
		catch (RuntimeException ex) {
			end(sessionId, turn, StopReason.ERROR);
			throw ex;
		}
		finish(sessionId, turn);
		return response;
	}

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
		Assert.notNull(request, "request must not be null");
		Assert.notNull(chain, "chain must not be null");
		String sessionId = SessionContexts.sessionId(request.context());
		return Mono.just(request).publishOn(this.scheduler).flatMapMany(r -> {
			SessionTurn turn = begin(r, sessionId);
			// Back on the scheduler, so the session writes never run on the model's thread
			return chain.nextStream(withTurn(r, turn))
				.publishOn(this.scheduler)
				.doOnError(ex -> end(sessionId, turn, StopReason.ERROR))
				.doOnComplete(() -> finish(sessionId, turn))
				.doOnCancel(() -> end(sessionId, turn, StopReason.INTERRUPTED));
		});
	}

	private SessionTurn begin(ChatClientRequest request, String sessionId) {
		SessionContexts.findOrCreate(this.sessionService, sessionId, request.context(), this.agent);
		SessionTurn turn = new SessionTurn();
		if (request.context().get(ToolConfirmationAdvisor.TOOL_CONFIRMATION_CONTEXT_KEY) == null) {
			this.sessionService.startTurn(sessionId);
			turn.hold();
		}
		return turn;
	}

	private static ChatClientRequest withTurn(ChatClientRequest request, SessionTurn turn) {
		return request.mutate().context(SessionTurn.CONTEXT_KEY, turn).build();
	}

	/** Ends the completed turn, then compacts the session if the trigger fires. */
	private void finish(String sessionId, SessionTurn turn) {
		if (!end(sessionId, turn, StopReason.END_TURN) || this.compactionTrigger == null
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
	 * Ends the turn this advisor holds while it is still running; returns {@code false}
	 * when there was none to end: the turn was never held (a resumption that failed before
	 * it started), or someone ended it already (parked, interrupted, out of budget).
	 */
	private boolean end(String sessionId, SessionTurn turn, StopReason stopReason) {
		if (!turn.isHeld()) {
			return false;
		}
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

	public static final class Builder {

		private final SessionService sessionService;

		private int order = DEFAULT_ORDER;

		private Scheduler scheduler = BaseAdvisor.DEFAULT_SCHEDULER;

		@Nullable private CompactionTrigger compactionTrigger;

		@Nullable private CompactionStrategy compactionStrategy;

		@Nullable private AgentSnapshot agent;

		private Builder(SessionService sessionService) {
			Assert.notNull(sessionService, "sessionService must not be null");
			this.sessionService = sessionService;
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
