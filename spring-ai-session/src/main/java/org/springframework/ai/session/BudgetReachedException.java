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


/**
 * Thrown by the {@link org.springframework.ai.session.advisor.BudgetAdvisor} instead of a model call when the session tree's
 * usage has reached its {@link Budget}. The session is idle with
 * {@link org.springframework.ai.session.StopReason#BUDGET_REACHED}; raise the budget to
 * continue.
 *
 * @author Christian Tzolov
 * @since 0.11.0
 */
public class BudgetReachedException extends RuntimeException {

	private final String sessionId;

	private final TokenUsage usage;

	private final Budget budget;

	public BudgetReachedException(String sessionId, TokenUsage usage, Budget budget) {
		super("Session '" + sessionId + "' has reached its budget: " + usage.totalTokens() + " of "
				+ budget.maxTotalTokens() + " total tokens used by its tree");
		this.sessionId = sessionId;
		this.usage = usage;
		this.budget = budget;
	}

	/** The session whose turn was refused. */
	public String getSessionId() {
		return this.sessionId;
	}

	/** The tree's usage at the time. */
	public TokenUsage getUsage() {
		return this.usage;
	}

	/** The budget that was reached. */
	public Budget getBudget() {
		return this.budget;
	}

}
