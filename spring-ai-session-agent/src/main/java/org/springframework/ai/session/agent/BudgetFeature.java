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
import org.springframework.ai.session.BudgetGuard;
import org.springframework.ai.session.BudgetPolicy;
import org.springframework.ai.session.SessionService;

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

	private final BudgetGuard guard;

	public BudgetFeature(SessionService sessions, BudgetPolicy policy) {
		this.guard = new BudgetGuard(sessions, policy);
	}

	@Override
	public ChatResponse aroundModelCall(Joinpoint jp, Prompt prompt, ExecutionContext context) {
		this.guard.check(context.getRunId().sessionId());
		return jp.proceed(prompt, context);
	}

}
