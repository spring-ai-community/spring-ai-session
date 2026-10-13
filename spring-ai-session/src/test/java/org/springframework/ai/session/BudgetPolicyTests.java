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

import java.util.UUID;

import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Tests for {@link Budget} and {@link BudgetPolicy}.
 *
 * @author Christian Tzolov
 */
class BudgetPolicyTests {

	private static Session root(Object budget) {
		Session.Builder session = Session.builder().id(UUID.randomUUID().toString());
		return budget != null ? session.metadata("budget", budget).build() : session.build();
	}

	@Test
	void aBudgetIsReachedAtItsTotal() {
		Budget budget = Budget.ofTotalTokens(100);
		assertThat(budget.isReachedBy(new TokenUsage(90, 9, 99, 0, 0))).isFalse();
		assertThat(budget.isReachedBy(new TokenUsage(90, 10, 100, 0, 0))).isTrue();
		assertThatIllegalArgumentException().isThrownBy(() -> Budget.ofTotalTokens(0));
	}

	@Test
	void policiesDecideFromTheRoot() {
		assertThat(BudgetPolicy.none().budgetFor(root(null))).isNull();
		assertThat(BudgetPolicy.fixed(Budget.ofTotalTokens(5)).budgetFor(root(null))).isEqualTo(Budget.ofTotalTokens(5));
		BudgetPolicy fromMetadata = BudgetPolicy.fromMetadata("budget");
		assertThat(fromMetadata.budgetFor(root(null))).isNull();
		assertThat(fromMetadata.budgetFor(root(2000))).isEqualTo(Budget.ofTotalTokens(2000));
		assertThat(fromMetadata.budgetFor(root("3000"))).isEqualTo(Budget.ofTotalTokens(3000));
		assertThatIllegalArgumentException().isThrownBy(() -> fromMetadata.budgetFor(root("lots")))
			.withMessageContaining("budget");
	}

}
