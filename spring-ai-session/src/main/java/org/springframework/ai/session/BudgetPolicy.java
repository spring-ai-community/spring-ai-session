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

import org.springframework.util.Assert;

/**
 * Which {@link Budget} a session tree has, decided from its root session: a fixed one, one
 * read from the root's metadata, or none. Given to the {@code BudgetAdvisor}.
 *
 * @author Christian Tzolov
 * @since 0.11.0
 */
@FunctionalInterface
public interface BudgetPolicy {

	/** The tree's budget, or {@code null} for no limit. */
	@Nullable Budget budgetFor(Session root);

	/** No tree has a budget. */
	static BudgetPolicy none() {
		return root -> null;
	}

	/** Every tree has the same budget. */
	static BudgetPolicy fixed(Budget budget) {
		Assert.notNull(budget, "budget must not be null");
		return root -> budget;
	}

	/**
	 * The budget is the number the root's metadata holds under the key, in total tokens;
	 * a tree whose root has no such entry has no budget.
	 */
	static BudgetPolicy fromMetadata(String key) {
		Assert.hasText(key, "key must not be null or empty");
		return root -> {
			Object value = root.metadata().get(key);
			if (value == null) {
				return null;
			}
			try {
				long tokens = (value instanceof Number number) ? number.longValue()
						: Long.parseLong(String.valueOf(value).trim());
				return Budget.ofTotalTokens(tokens);
			}
			catch (NumberFormatException ex) {
				throw new IllegalArgumentException("Session '" + root.id() + "' has a budget in its metadata under '"
						+ key + "' that is not a number: " + value, ex);
			}
		};
	}

}
