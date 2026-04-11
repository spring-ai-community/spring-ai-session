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

import java.util.Set;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.MessageType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Validates the constructor contracts of {@link EventFilter}.
 */
class EventFilterValidationTests {

	// --- keyword normalization ---

	@Test
	void blankKeywordIsNormalizedToNull() {
		assertThat(EventFilter.builder().keyword("   ").build().keyword()).isNull();
		assertThat(EventFilter.builder().keyword("").build().keyword()).isNull();
	}

	@Test
	void nullKeywordRemainsNull() {
		assertThat(EventFilter.builder().keyword(null).build().keyword()).isNull();
	}

	@Test
	void keywordIsLowercased() {
		assertThat(EventFilter.builder().keyword("Spring AI").build().keyword()).isEqualTo("spring ai");
	}

	// --- lastN ---

	@Test
	void lastNZeroIsRejected() {
		assertThatIllegalArgumentException().isThrownBy(() -> EventFilter.builder().lastN(0).build())
			.withMessageContaining("lastN must be greater than 0");
	}

	@Test
	void lastNNegativeIsRejected() {
		assertThatIllegalArgumentException().isThrownBy(() -> EventFilter.builder().lastN(-1).build())
			.withMessageContaining("lastN must be greater than 0");
	}

	@Test
	void lastNPositiveIsAccepted() {
		EventFilter filter = EventFilter.builder().lastN(5).build();
		assertThat(filter.lastN()).isEqualTo(5);
	}

	// --- pageSize ---

	@Test
	void pageSizeZeroIsRejected() {
		assertThatIllegalArgumentException().isThrownBy(() -> EventFilter.builder().pageSize(0).build())
			.withMessageContaining("pageSize must be greater than 0");
	}

	@Test
	void pageSizeNegativeIsRejected() {
		assertThatIllegalArgumentException().isThrownBy(() -> EventFilter.builder().pageSize(-1).build())
			.withMessageContaining("pageSize must be greater than 0");
	}

	// --- page ---

	@Test
	void pageNegativeIsRejected() {
		assertThatIllegalArgumentException().isThrownBy(() -> EventFilter.builder().page(-1).pageSize(10).build())
			.withMessageContaining("page must be >= 0");
	}

	@Test
	void pageWithoutPageSizeIsRejected() {
		assertThatIllegalArgumentException().isThrownBy(() -> EventFilter.builder().page(1).build())
			.withMessageContaining("pageSize must be set when page is set");
	}

	// --- page defaults ---

	@Test
	void pageSizeWithoutPageDefaultsToZero() {
		EventFilter filter = EventFilter.builder().pageSize(10).build();
		assertThat(filter.page()).isEqualTo(0);
		assertThat(filter.pageSize()).isEqualTo(10);
	}

	@Test
	void pageSizeWithExplicitPageIsPreserved() {
		EventFilter filter = EventFilter.builder().page(2).pageSize(10).build();
		assertThat(filter.page()).isEqualTo(2);
	}

	// --- messageTypes normalization ---

	@Test
	void emptyMessageTypesSetIsNormalizedToNull() {
		EventFilter filter = EventFilter.builder().messageTypes(Set.of()).build();
		assertThat(filter.messageTypes()).isNull();
	}

	@Test
	void nonEmptyMessageTypesSetIsPreserved() {
		EventFilter filter = EventFilter.builder().messageTypes(Set.of(MessageType.USER)).build();
		assertThat(filter.messageTypes()).containsExactly(MessageType.USER);
	}

	// --- mutual exclusion ---

	@Test
	void lastNAndPageSizeAreMutuallyExclusive() {
		assertThatIllegalArgumentException().isThrownBy(() -> EventFilter.builder().lastN(5).pageSize(10).build())
			.withMessageContaining("mutually exclusive");
	}

	@Test
	void lastNAndPageWithPageSizeAreMutuallyExclusive() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> EventFilter.builder().lastN(5).page(0).pageSize(10).build())
			.withMessageContaining("mutually exclusive");
	}

}
