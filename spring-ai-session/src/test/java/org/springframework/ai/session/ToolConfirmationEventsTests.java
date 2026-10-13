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

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Tests for {@link ToolConfirmation} and the {@link ToolConfirmationEvents} it is recorded with.
 *
 * @author Christian Tzolov
 */
class ToolConfirmationEventsTests {

	@Test
	void aDecisionRoundTripsThroughItsEvent() {
		SessionEvent allow = ToolConfirmationEvents.of("s", ToolConfirmation.allow("call-1"));
		SessionEvent deny = ToolConfirmationEvents.of("s", ToolConfirmation.deny("call-2", "not on Fridays"));
		SessionEvent denyQuietly = ToolConfirmationEvents.of("s", ToolConfirmation.deny("call-3", null));

		assertThat(allow.getType()).isEqualTo(EventType.TOOL_CONFIRMATION);
		assertThat(allow.hasMessage()).isFalse();
		assertThat(allow.getPayload()).containsEntry(ToolConfirmationEvents.PAYLOAD_TOOL_CALL_ID, "call-1")
			.containsEntry(ToolConfirmationEvents.PAYLOAD_RESULT, ToolConfirmationEvents.RESULT_ALLOW)
			.doesNotContainKey(ToolConfirmationEvents.PAYLOAD_DENY_MESSAGE);
		assertThat(ToolConfirmationEvents.confirmation(allow)).isEqualTo(ToolConfirmation.allow("call-1"));
		assertThat(ToolConfirmationEvents.confirmation(deny)).isEqualTo(ToolConfirmation.deny("call-2", "not on Fridays"));
		assertThat(ToolConfirmationEvents.confirmation(denyQuietly).denyMessageOrDefault())
			.isEqualTo(ToolConfirmation.DEFAULT_DENY_MESSAGE);
		assertThat(ToolConfirmationEvents.confirmation(
				SessionEvent.builder().sessionId("s").message(new UserMessage("hi")).build())).isNull();
		assertThatIllegalArgumentException().isThrownBy(() -> ToolConfirmation.allow(""));
		// A malformed event is rejected rather than read as a call named "null"
		SessionEvent malformed = SessionEvent.builder()
			.sessionId("s")
			.type(EventType.TOOL_CONFIRMATION)
			.payload(ToolConfirmationEvents.PAYLOAD_RESULT, ToolConfirmationEvents.RESULT_ALLOW)
			.build();
		assertThatIllegalArgumentException().isThrownBy(() -> ToolConfirmationEvents.confirmation(malformed))
			.withMessageContaining(ToolConfirmationEvents.PAYLOAD_TOOL_CALL_ID);
	}

}
