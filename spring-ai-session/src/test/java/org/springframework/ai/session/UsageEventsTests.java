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
import org.springframework.ai.chat.metadata.DefaultUsage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Tests for {@link TokenUsage} and the {@link UsageEvents} it is recorded with.
 *
 * @author Christian Tzolov
 */
class UsageEventsTests {

	@Test
	void usageIsBuiltFromAModelCallAndSummed() {
		TokenUsage call = TokenUsage.of(new DefaultUsage(100, 20));
		assertThat(call).isEqualTo(new TokenUsage(100, 20, 120, 0, 0));
		assertThat(TokenUsage.of(null)).isEqualTo(TokenUsage.ZERO);
		assertThat(TokenUsage.ZERO.isEmpty()).isTrue();
		assertThat(call.plus(new TokenUsage(1, 2, 3, 4, 5))).isEqualTo(new TokenUsage(101, 22, 123, 4, 5));
		assertThatIllegalArgumentException().isThrownBy(() -> new TokenUsage(-1, 0, 0, 0, 0));
	}

	@Test
	void aUsageEventRoundTrips() {
		SessionEvent event = UsageEvents.of("s", new TokenUsage(100, 20, 120, 80, 0), "claude");
		SessionEvent bare = UsageEvents.of("s", new DefaultUsage(1, 1), null);

		assertThat(event.getType()).isEqualTo(EventType.USAGE);
		assertThat(event.hasMessage()).isFalse();
		assertThat(event.getPayload()).containsEntry(UsageEvents.PAYLOAD_TOTAL_TOKENS, 120L)
			.containsEntry(UsageEvents.PAYLOAD_CACHE_READ_INPUT_TOKENS, 80L)
			.containsEntry(UsageEvents.PAYLOAD_MODEL, "claude")
			.doesNotContainKey(UsageEvents.PAYLOAD_CACHE_WRITE_INPUT_TOKENS);
		assertThat(UsageEvents.usage(event)).isEqualTo(new TokenUsage(100, 20, 120, 80, 0));
		assertThat(UsageEvents.model(event)).isEqualTo("claude");
		assertThat(UsageEvents.usage(bare)).isEqualTo(new TokenUsage(1, 1, 2, 0, 0));
		assertThat(UsageEvents.model(bare)).isNull();
		assertThat(UsageEvents.usage(SessionEvent.builder().sessionId("s").message(new UserMessage("hi")).build()))
			.isNull();
		// Counts read back from a store as integers or strings still add up
		SessionEvent stored = SessionEvent.builder()
			.sessionId("s")
			.type(EventType.USAGE)
			.payload(UsageEvents.PAYLOAD_PROMPT_TOKENS, 7)
			.payload(UsageEvents.PAYLOAD_COMPLETION_TOKENS, "3")
			.payload(UsageEvents.PAYLOAD_TOTAL_TOKENS, 10)
			.build();
		assertThat(UsageEvents.usage(stored)).isEqualTo(new TokenUsage(7, 3, 10, 0, 0));
	}

}
