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

package org.springframework.ai.session;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.util.Assert;

/**
 * The budget check both integrations run before a model call: the session tree's usage
 * against the {@link Budget} the {@link BudgetPolicy} gives its root. Once reached, the
 * session's running turn ends with {@link StopReason#BUDGET_REACHED} and the call is
 * refused with a {@link BudgetReachedException}, until the policy returns a higher budget.
 *
 * @author Christian Tzolov
 * @since 0.11.0
 */
public final class BudgetGuard {

	private static final Logger logger = LoggerFactory.getLogger(BudgetGuard.class);

	private final SessionService sessions;

	private final BudgetPolicy policy;

	public BudgetGuard(SessionService sessions, BudgetPolicy policy) {
		Assert.notNull(sessions, "sessions must not be null");
		Assert.notNull(policy, "policy must not be null");
		this.sessions = sessions;
		this.policy = policy;
	}

	/**
	 * Lets a model call of the session proceed, or refuses it.
	 * @throws BudgetReachedException if the tree's usage has reached its budget; the
	 * session's turn is ended with {@link StopReason#BUDGET_REACHED} first
	 */
	public void check(String sessionId) {
		Session session = this.sessions.findById(sessionId);
		if (session == null) {
			return;
		}
		Session root = session.isRoot() ? session : this.sessions.findById(session.rootSessionId());
		@Nullable Budget budget = (root != null) ? this.policy.budgetFor(root) : null;
		if (budget == null) {
			return;
		}
		TokenUsage usage = this.sessions.aggregateUsage(root.id());
		if (!budget.isReachedBy(usage)) {
			return;
		}
		try {
			this.sessions.endTurn(sessionId, StopReason.BUDGET_REACHED);
		}
		catch (IllegalStateException ex) {
			logger.warn("Session [{}] reached its budget but no running turn could be ended: {}", sessionId,
					ex.getMessage());
		}
		throw new BudgetReachedException(sessionId, usage, budget);
	}

}
