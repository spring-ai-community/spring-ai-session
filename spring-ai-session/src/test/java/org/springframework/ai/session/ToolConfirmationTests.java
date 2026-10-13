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

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Tests for {@link ToolConfirmation} and the answer it is recorded as.
 *
 * @author Christian Tzolov
 */
class ToolConfirmationTests {

	@Test
	void decisionsRoundTripThroughAnAnswer() {
		List<ToolConfirmation> decisions = List.of(ToolConfirmation.allow("call-1"),
				ToolConfirmation.deny("call-2", "not on Fridays"), ToolConfirmation.deny("call-3", null));

		List<Map<String, Object>> answer = ToolConfirmation.asAnswer(decisions);

		assertThat(answer).hasSize(3);
		assertThat(answer.get(0)).containsExactly(Map.entry("toolCallId", "call-1"), Map.entry("allowed", true));
		assertThat(answer.get(1)).containsEntry("denyMessage", "not on Fridays");
		assertThat(ToolConfirmation.fromAnswer(answer)).isEqualTo(decisions);
		assertThat(ToolConfirmation.fromAnswer("yes")).isEmpty();
		assertThat(ToolConfirmation.fromAnswer(null)).isEmpty();
		assertThat(ToolConfirmation.deny("c", null).denyMessageOrDefault()).isEqualTo(ToolConfirmation.DEFAULT_DENY_MESSAGE);
		assertThatIllegalArgumentException().isThrownBy(() -> ToolConfirmation.allow(""));
	}

}
