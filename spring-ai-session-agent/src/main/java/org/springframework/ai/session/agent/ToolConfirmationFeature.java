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

import java.util.List;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.agent.ExecutionContext;
import org.springframework.ai.agent.InteractionRequiredException;
import org.springframework.ai.agent.tools.ToolCallInterceptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.model.tool.ToolCallConfirmationPolicy;
import org.springframework.ai.model.tool.ToolCallDecision;
import org.springframework.ai.model.tool.ToolCallDecisions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.util.Assert;

/**
 * The tool-confirmation gate of a run: a tool call the {@link ToolCallConfirmationPolicy}
 * says must be confirmed interrupts the run with a {@link PendingToolCall} as the
 * question, so the {@link SessionFeature} parks the turn. The run is resumed with a
 * {@link ToolCallDecision}, or {@link ToolCallDecisions} with one that names the call by
 * its tool: an allowed call runs through the agent's own tools, a denied one answers
 * the model with its message. The {@link SessionFeature} records the decision as the
 * answer to the question the run parked on.
 * <p>
 * The policy sees the metadata of the tools given to the gate, so
 * {@code @Tool(requiresConfirmation = true)} works with
 * {@link ToolCallConfirmationPolicy#fromToolMetadata()}; for a tool the gate was not given,
 * it sees {@code null}.
 *
 * @author Christian Tzolov
 * @since 0.11.0
 */
public final class ToolConfirmationFeature implements ToolCallInterceptor {

	private static final ParameterizedTypeReference<Object> ANY = ParameterizedTypeReference.forType(Object.class);

	private final ToolCallConfirmationPolicy policy;

	private final List<ToolCallback> tools;

	public ToolConfirmationFeature(ToolCallConfirmationPolicy policy) {
		this(policy, List.of());
	}

	/**
	 * @param policy which calls need a person's confirmation
	 * @param tools the agent's tools, whose metadata the policy sees
	 */
	public ToolConfirmationFeature(ToolCallConfirmationPolicy policy, List<ToolCallback> tools) {
		Assert.notNull(policy, "policy must not be null");
		Assert.notNull(tools, "tools must not be null");
		this.policy = policy;
		this.tools = List.copyOf(tools);
	}

	@Override
	public String aroundToolCall(Joinpoint jp, String name, String arguments, ExecutionContext context) {
		AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(name, "function", name, arguments);
		if (!this.policy.requiresConfirmation(call, metadata(name))) {
			return jp.proceed(name, arguments, context);
		}
		// Only a decision on this call is this gate's answer; any other resume value belongs
		// to another interrupt and is left where it was
		Object value = context.consumeResumeValue(ANY);
		ToolCallDecision decision = decisionOn(name, value);
		if (decision == null) {
			context.setResumeValue(value);
			throw new InteractionRequiredException(new PendingToolCall(name, arguments), context.getRunId(),
					context.getPath() + name);
		}
		if (!decision.allowed()) {
			return decision.denyMessage();
		}
		return jp.proceed(name, arguments, context);
	}

	private @Nullable ToolMetadata metadata(String name) {
		return this.tools.stream()
			.filter(tool -> tool.getToolDefinition().name().equals(name))
			.map(ToolCallback::getToolMetadata)
			.findFirst()
			.orElse(null);
	}

	/**
	 * The decision on the call: a lone decision is the answer to the one call the run
	 * parked on, whatever id it names; of several, the one that names the tool.
	 */
	private static @Nullable ToolCallDecision decisionOn(String name, @Nullable Object value) {
		if (value instanceof ToolCallDecision decision) {
			return decision;
		}
		if (value instanceof ToolCallDecisions decisions) {
			return decisions.decisionOn(name);
		}
		return null;
	}

	/** The question a run interrupted by the gate asks: which tool, with which arguments. */
	public record PendingToolCall(String name, @Nullable String arguments) {
	}

}
