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
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import reactor.core.scheduler.Scheduler;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.model.tool.ToolCallDecision;
import org.springframework.ai.model.tool.ToolCallDecisions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.session.AgentSnapshot;
import org.springframework.ai.session.BudgetPolicy;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.compaction.CompactionStrategy;
import org.springframework.ai.session.compaction.CompactionTrigger;
import org.springframework.util.Assert;

/**
 * The session advisors of a {@code ChatClient}, configured together: the
 * {@link SessionTurnAdvisor} (the turn, tool confirmation, compaction), the
 * {@link SessionMemoryAdvisor} (the history, the messages, the usage) and, with a budget
 * policy, the {@link BudgetAdvisor}. Each sits at its default order around the tool-calling
 * loop, which the {@code ChatClient} registers itself:
 *
 * <pre class="code">
 * ChatClient chatClient = ChatClient.builder(chatModel)
 *     .defaultAdvisors(SessionAdvisors.builder(sessionService)
 *         .toolCallingManager(toolCallingManager)
 *         .compaction(new TurnCountTrigger(20), SlidingWindowCompactionStrategy.builder().build())
 *         .build())
 *     .build();
 *
 * chatClient.prompt().user("Hi").advisors(SessionAdvisors.session(sessionId)).call().content();
 * </pre>
 *
 * The advisors can still be built one by one, for an order of your own.
 *
 * @author Christian Tzolov
 * @since 0.11.0
 */
public final class SessionAdvisors {

	private SessionAdvisors() {
	}

	public static Builder builder(SessionService sessionService) {
		return new Builder(sessionService);
	}

	/** The request runs on the session: {@code .advisors(SessionAdvisors.session(sessionId))}. */
	public static Consumer<ChatClient.AdvisorSpec> session(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be empty");
		return advisors -> advisors.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId);
	}

	/**
	 * The request resumes the session's parked turn with the decisions on the tool calls
	 * it waits on; it carries no new user message.
	 */
	public static Consumer<ChatClient.AdvisorSpec> decide(String sessionId, ToolCallDecision... decisions) {
		return decide(sessionId, ToolCallDecisions.of(decisions));
	}

	/** See {@link #decide(String, ToolCallDecision...)}. */
	public static Consumer<ChatClient.AdvisorSpec> decide(String sessionId, ToolCallDecisions decisions) {
		Assert.notNull(decisions, "decisions must not be null");
		Assert.notEmpty(decisions.decisions(), "decisions must not be empty");
		return session(sessionId)
			.andThen(advisors -> advisors.param(SessionTurnAdvisor.TOOL_CALL_DECISIONS_CONTEXT_KEY, decisions));
	}

	public static final class Builder {

		private final SessionService sessionService;

		@Nullable private ToolCallingManager toolCallingManager;

		@Nullable private AgentSnapshot agent;

		@Nullable private Scheduler scheduler;

		@Nullable private CompactionTrigger compactionTrigger;

		@Nullable private CompactionStrategy compactionStrategy;

		@Nullable private BudgetPolicy budgetPolicy;

		private Consumer<SessionMemoryAdvisor.Builder> memory = memory -> {
		};

		private Builder(SessionService sessionService) {
			Assert.notNull(sessionService, "sessionService must not be null");
			this.sessionService = sessionService;
		}

		/**
		 * The manager that runs the tool calls of a resumed turn. Give it the manager of
		 * the {@code ChatClient}'s tool-calling loop (the {@code ToolCallingManager} bean in
		 * a Spring Boot application), so the confirmation policy and the tool call limits
		 * are the same on both sides of a pause; defaults to
		 * {@code ToolCallingManager.builder().build()}.
		 */
		public Builder toolCallingManager(ToolCallingManager toolCallingManager) {
			Assert.notNull(toolCallingManager, "toolCallingManager must not be null");
			this.toolCallingManager = toolCallingManager;
			return this;
		}

		/** The agent the sessions the advisors create on first use are stamped with. */
		public Builder agent(@Nullable AgentSnapshot agent) {
			this.agent = agent;
			return this;
		}

		/** The scheduler the session writes of a streamed call run on. */
		public Builder scheduler(Scheduler scheduler) {
			Assert.notNull(scheduler, "scheduler must not be null");
			this.scheduler = scheduler;
			return this;
		}

		/** Compacts the session once a turn has ended, when the trigger fires. */
		public Builder compaction(CompactionTrigger trigger, CompactionStrategy strategy) {
			Assert.notNull(trigger, "trigger must not be null");
			Assert.notNull(strategy, "strategy must not be null");
			this.compactionTrigger = trigger;
			this.compactionStrategy = strategy;
			return this;
		}

		/** Adds a {@link BudgetAdvisor} that refuses model calls once the budget is reached. */
		public Builder budgetPolicy(BudgetPolicy budgetPolicy) {
			Assert.notNull(budgetPolicy, "budgetPolicy must not be null");
			this.budgetPolicy = budgetPolicy;
			return this;
		}

		/**
		 * Customizes the {@link SessionMemoryAdvisor}: its filters, event id generators and
		 * usage recording. Customizers run in the order they are added, after the shared
		 * settings, so they can override them.
		 */
		public Builder memory(Consumer<SessionMemoryAdvisor.Builder> memory) {
			Assert.notNull(memory, "memory must not be null");
			this.memory = this.memory.andThen(memory);
			return this;
		}

		/** The advisors, in their order: the turn, the memory and, with a budget policy, the budget. */
		public List<Advisor> build() {
			Scheduler scheduler = (this.scheduler != null) ? this.scheduler : BaseAdvisor.DEFAULT_SCHEDULER;

			SessionTurnAdvisor.Builder turn = SessionTurnAdvisor.builder(this.sessionService)
				.agent(this.agent)
				.scheduler(scheduler);
			if (this.toolCallingManager != null) {
				turn.toolCallingManager(this.toolCallingManager);
			}
			if (this.compactionTrigger != null && this.compactionStrategy != null) {
				turn.compactionTrigger(this.compactionTrigger).compactionStrategy(this.compactionStrategy);
			}

			SessionMemoryAdvisor.Builder memory = SessionMemoryAdvisor.builder(this.sessionService)
				.agent(this.agent)
				.scheduler(scheduler);
			this.memory.accept(memory);

			List<Advisor> advisors = new ArrayList<>();
			advisors.add(turn.build());
			advisors.add(memory.build());
			if (this.budgetPolicy != null) {
				advisors.add(BudgetAdvisor.builder(this.sessionService).policy(this.budgetPolicy).build());
			}
			return List.copyOf(advisors);
		}

	}

}
