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

import org.springframework.ai.agent.ExecutionContext;
import org.springframework.ai.agent.model.ModelCallInterceptor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.session.Budget;
import org.springframework.ai.session.BudgetPolicy;
import org.springframework.ai.session.BudgetReachedException;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.StopReason;
import org.springframework.ai.session.TokenUsage;
import org.springframework.util.Assert;

/**
 * A token budget per session tree, checked before every model call of a run: once the
 * tree's usage ({@link SessionService#aggregateUsage}) has reached the {@link Budget} the
 * {@link BudgetPolicy} gives its root, the turn ends with
 * {@link StopReason#BUDGET_REACHED} and the call is refused with a
 * {@link BudgetReachedException}, until the policy returns a higher budget. The usage comes
 * from the events the {@link SessionFeature} records, so enable both.
 *
 * @author Christian Tzolov
 * @since 0.11.0
 */
public final class BudgetFeature implements ModelCallInterceptor {

	private final SessionService sessions;

	private final BudgetPolicy policy;

	public BudgetFeature(SessionService sessions, BudgetPolicy policy) {
		Assert.notNull(sessions, "sessions must not be null");
		Assert.notNull(policy, "policy must not be null");
		this.sessions = sessions;
		this.policy = policy;
	}

	@Override
	public ChatResponse aroundModelCall(Joinpoint jp, Prompt prompt, ExecutionContext context) {
		String sessionId = context.getRunId().sessionId();
		Session session = this.sessions.findById(sessionId);
		if (session == null) {
			return jp.proceed(prompt, context);
		}
		Session root = session.isRoot() ? session : this.sessions.findById(session.rootSessionId());
		Budget budget = root != null ? this.policy.budgetFor(root) : null;
		if (budget == null) {
			return jp.proceed(prompt, context);
		}
		TokenUsage usage = this.sessions.aggregateUsage(root.id());
		if (!budget.isReachedBy(usage)) {
			return jp.proceed(prompt, context);
		}
		try {
			this.sessions.endTurn(sessionId, StopReason.BUDGET_REACHED);
		}
		catch (IllegalStateException ignored) {
			// no running turn to end: the feature that tracks it is not enabled
		}
		throw new BudgetReachedException(sessionId, usage, budget);
	}

}
