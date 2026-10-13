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
 * The token budget of a session tree: a turn is refused once the tree's usage reaches
 * it. The counterpart of an agent runtime's session {@code budget} and
 * {@code budget_reached} stop reason.
 *
 * @param maxTotalTokens the total tokens (prompt plus completion, summed over the tree)
 * the tree may use
 * @author Christian Tzolov
 * @since 0.11.0
 */
public record Budget(long maxTotalTokens) {

	public Budget {
		if (maxTotalTokens <= 0) {
			throw new IllegalArgumentException("maxTotalTokens must be greater than 0");
		}
	}

	/** A budget of the given total tokens. */
	public static Budget ofTotalTokens(long maxTotalTokens) {
		return new Budget(maxTotalTokens);
	}

	/** Whether the usage has reached this budget. */
	public boolean isReachedBy(TokenUsage usage) {
		return usage.totalTokens() >= this.maxTotalTokens;
	}

}
