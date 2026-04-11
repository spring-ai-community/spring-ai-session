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

package org.springframework.ai.session.compaction;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.MediaContent;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.tokenizer.TokenCountEstimator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Tests for {@link TokenCountTrigger}.
 */
class TokenCountTriggerTests {

	private static final String SESSION_ID = "test-session";

	/**
	 * Deterministic estimator: each character counts as one token. Makes token arithmetic
	 * exact and independent of the real BPE tokenizer.
	 */
	private static final TokenCountEstimator CHAR_ESTIMATOR = new TokenCountEstimator() {
		@Override
		public int estimate(String text) {
			return (text != null) ? text.length() : 0;
		}

		@Override
		public int estimate(MediaContent content) {
			return 0;
		}

		@Override
		public int estimate(Iterable<MediaContent> messages) {
			return 0;
		}
	};

	@Test
	void firesWhenTokenCountReachesThreshold() {
		// "hello"(5) + "world"(5) = 10 tokens, threshold = 10 → fires (>=)
		TokenCountTrigger trigger = TokenCountTrigger.builder()
			.threshold(10)
			.tokenCountEstimator(CHAR_ESTIMATOR)
			.build();
		CompactionRequest request = requestWith(turn("hello", "world"));

		assertThat(trigger.shouldCompact(request)).isTrue();
	}

	@Test
	void firesWhenTokenCountExceedsThreshold() {
		// "hello"(5) + "world!"(6) = 11 tokens, threshold = 10
		TokenCountTrigger trigger = TokenCountTrigger.builder()
			.threshold(10)
			.tokenCountEstimator(CHAR_ESTIMATOR)
			.build();
		CompactionRequest request = requestWith(turn("hello", "world!"));

		assertThat(trigger.shouldCompact(request)).isTrue();
	}

	@Test
	void doesNotFireWhenTokenCountBelowThreshold() {
		// "hi"(2) + "ok"(2) = 4 tokens, threshold = 10
		TokenCountTrigger trigger = TokenCountTrigger.builder()
			.threshold(10)
			.tokenCountEstimator(CHAR_ESTIMATOR)
			.build();
		CompactionRequest request = requestWith(turn("hi", "ok"));

		assertThat(trigger.shouldCompact(request)).isFalse();
	}

	@Test
	void doesNotFireOnEmptySession() {
		TokenCountTrigger trigger = TokenCountTrigger.builder()
			.threshold(10)
			.tokenCountEstimator(CHAR_ESTIMATOR)
			.build();
		CompactionRequest request = requestWith(List.of());

		assertThat(trigger.shouldCompact(request)).isFalse();
	}

	@Test
	void countsTokensAcrossAllEvents() {
		// Two turns: "ab"(2)+"cd"(2) + "ef"(2)+"gh"(2) = 8 tokens, threshold = 7
		TokenCountTrigger trigger = TokenCountTrigger.builder()
			.threshold(7)
			.tokenCountEstimator(CHAR_ESTIMATOR)
			.build();
		CompactionRequest request = requestWith(turn("ab", "cd"), turn("ef", "gh"));

		assertThat(trigger.shouldCompact(request)).isTrue();
	}

	@Test
	void zeroThresholdIsRejected() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> TokenCountTrigger.builder().threshold(0).tokenCountEstimator(CHAR_ESTIMATOR).build())
			.withMessageContaining("threshold must be greater than 0");
	}

	@Test
	void negativeThresholdIsRejected() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> TokenCountTrigger.builder().threshold(-1).tokenCountEstimator(CHAR_ESTIMATOR).build());
	}

	@Test
	void nullEstimatorIsRejected() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> TokenCountTrigger.builder().threshold(100).tokenCountEstimator(null).build())
			.withMessageContaining("tokenCountEstimator must not be null");
	}

	@Test
	void getThresholdReturnsConfiguredValue() {
		TokenCountTrigger trigger = TokenCountTrigger.builder()
			.threshold(500)
			.tokenCountEstimator(CHAR_ESTIMATOR)
			.build();
		assertThat(trigger.getThreshold()).isEqualTo(500);
	}

	// --- helpers ---

	private List<SessionEvent> turn(String userText, String assistantText) {
		return List.of(SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage(userText)).build(),
				SessionEvent.builder().sessionId(SESSION_ID).message(new AssistantMessage(assistantText)).build());
	}

	@SafeVarargs
	private CompactionRequest requestWith(List<SessionEvent>... turns) {
		List<SessionEvent> all = new ArrayList<>();
		for (List<SessionEvent> turn : turns) {
			all.addAll(turn);
		}
		return requestWith(all);
	}

	private CompactionRequest requestWith(List<SessionEvent> events) {
		Session session = Session.builder().id(SESSION_ID).userId("test-user").build();
		return CompactionRequest.of(session, new ArrayList<>(events));
	}

}
