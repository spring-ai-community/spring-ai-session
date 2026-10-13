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

import java.util.Map;

import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.session.BudgetGuard;
import org.springframework.ai.session.BudgetPolicy;
import org.springframework.ai.session.SessionService;
import org.springframework.core.Ordered;
import org.springframework.util.Assert;

/**
 * Enforces a session tree's {@link Budget} between model calls: before every call, the
 * tree's usage so far ({@link SessionService#aggregateUsage}) is checked against the
 * budget the {@link BudgetPolicy} gives its root. Once the usage has reached the budget,
 * the running turn ends with {@link StopReason#BUDGET_REACHED} and the call is refused
 * with a {@link BudgetReachedException}; the next request is refused the same way until
 * the policy returns a higher budget. A call that takes the usage over the budget
 * completes: the budget is checked before calls, not after them.
 * <p>
 * The usage comes from the {@link org.springframework.ai.session.EventType#USAGE} events
 * the {@link SessionMemoryAdvisor} records, so the advisor sits <em>inside</em> the memory
 * advisor (order {@link #DEFAULT_ORDER}), where the turn is running and every round of a
 * tool loop passes through it.
 *
 * @author Christian Tzolov
 * @since 0.11.0
 */
public final class BudgetAdvisor implements BaseAdvisor {

	/** Inside the memory advisor's default ({@code HIGHEST_PRECEDENCE + 1000}). */
	public static final int DEFAULT_ORDER = Ordered.HIGHEST_PRECEDENCE + 1100;

	private final BudgetGuard guard;

	private final int order;

	private BudgetAdvisor(SessionService sessionService, BudgetPolicy policy, int order) {
		this.guard = new BudgetGuard(sessionService, policy);
		this.order = order;
	}

	public static Builder builder(SessionService sessionService) {
		return new Builder(sessionService);
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	@Override
	public ChatClientRequest before(ChatClientRequest request, AdvisorChain advisorChain) {
		this.guard.check(getSessionId(request.context()));
		return request;
	}

	@Override
	public ChatClientResponse after(ChatClientResponse response, AdvisorChain advisorChain) {
		return response;
	}

	/** The refusal reaches the subscriber as it is, not wrapped by the default stream handling. */
	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
		return Mono.just(request).publishOn(getScheduler()).map(r -> before(r, chain)).flatMapMany(chain::nextStream);
	}

	private static String getSessionId(Map<String, @Nullable Object> context) {
		Object value = context.get(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY);
		if (value instanceof String s && !s.isBlank()) {
			return s;
		}
		throw new IllegalStateException("No session ID found in advisor context. Set SESSION_ID_CONTEXT_KEY on "
				+ "every request: .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId))");
	}

	public static final class Builder {

		private final SessionService sessionService;

		private BudgetPolicy policy = BudgetPolicy.none();

		private int order = DEFAULT_ORDER;

		private Builder(SessionService sessionService) {
			Assert.notNull(sessionService, "sessionService must not be null");
			this.sessionService = sessionService;
		}

		/** Which budget a tree has; defaults to {@link BudgetPolicy#none()}. */
		public Builder policy(BudgetPolicy policy) {
			Assert.notNull(policy, "policy must not be null");
			this.policy = policy;
			return this;
		}

		/** Keep it inside the memory advisor (a higher value than its order); defaults to {@link #DEFAULT_ORDER}. */
		public Builder order(int order) {
			this.order = order;
			return this;
		}

		public BudgetAdvisor build() {
			return new BudgetAdvisor(this.sessionService, this.policy, this.order);
		}

	}

}
