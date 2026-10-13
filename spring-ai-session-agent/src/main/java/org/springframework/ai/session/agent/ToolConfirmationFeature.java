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

import org.jspecify.annotations.Nullable;

import org.springframework.ai.agent.ExecutionContext;
import org.springframework.ai.agent.InteractionRequiredException;
import org.springframework.ai.agent.tools.ToolCallInterceptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.session.ToolConfirmation;
import org.springframework.ai.session.ToolConfirmationPolicy;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.util.Assert;

/**
 * The tool-confirmation gate of a run: a tool call the {@link ToolConfirmationPolicy} says
 * must be confirmed interrupts the run with a {@link PendingToolCall} as the question, so
 * the {@link SessionFeature} parks the turn. The run is resumed with a
 * {@link ToolConfirmation}: an allowed call runs through the agent's own tools, a denied
 * one answers the model with its message. The {@link SessionFeature} records the decision
 * as the answer to the question the run parked on.
 *
 * @author Christian Tzolov
 * @since 0.11.0
 */
public final class ToolConfirmationFeature implements ToolCallInterceptor {

	private static final ParameterizedTypeReference<Object> ANY = ParameterizedTypeReference.forType(Object.class);

	private final ToolConfirmationPolicy policy;

	public ToolConfirmationFeature(ToolConfirmationPolicy policy) {
		Assert.notNull(policy, "policy must not be null");
		this.policy = policy;
	}

	@Override
	public String aroundToolCall(Joinpoint jp, String name, String arguments, ExecutionContext context) {
		AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(name, "function", name, arguments);
		if (!this.policy.requiresConfirmation(call)) {
			return jp.proceed(name, arguments, context);
		}
		// Only a confirmation is this gate's answer; any other resume value belongs to another
		// interrupt and is left where it was
		Object value = context.consumeResumeValue(ANY);
		if (!(value instanceof ToolConfirmation decision)) {
			context.setResumeValue(value);
			throw new InteractionRequiredException(new PendingToolCall(name, arguments), context.getRunId(),
					context.getPath() + name);
		}
		if (!decision.allowed()) {
			return decision.denyMessageOrDefault();
		}
		return jp.proceed(name, arguments, context);
	}

	/** The question a run interrupted by the gate asks: which tool, with which arguments. */
	public record PendingToolCall(String name, @Nullable String arguments) {
	}

}
