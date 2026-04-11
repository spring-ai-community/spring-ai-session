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

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.MessageType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link EventFilter#merge(EventFilter)}.
 *
 * <p>
 * Contract: fields from {@code other} win when non-null/non-default;
 * {@code excludeSynthetic} is OR-ed so either side can opt in.
 */
class EventFilterMergeTests {

	// --- other fields win when set ---

	@Test
	void otherLastNOverridesBase() {
		EventFilter base = EventFilter.lastN(10);
		EventFilter other = EventFilter.lastN(3);
		assertThat(base.merge(other).lastN()).isEqualTo(3);
	}

	@Test
	void otherBranchOverridesBase() {
		EventFilter base = EventFilter.forBranch("orch");
		EventFilter other = EventFilter.forBranch("orch.researcher");
		assertThat(base.merge(other).branch()).isEqualTo("orch.researcher");
	}

	@Test
	void otherFromOverridesBase() {
		Instant t1 = Instant.parse("2025-01-01T00:00:00Z");
		Instant t2 = Instant.parse("2025-06-01T00:00:00Z");
		EventFilter base = EventFilter.builder().from(t1).build();
		EventFilter other = EventFilter.builder().from(t2).build();
		assertThat(base.merge(other).from()).isEqualTo(t2);
	}

	@Test
	void otherToOverridesBase() {
		Instant t1 = Instant.parse("2025-12-31T00:00:00Z");
		Instant t2 = Instant.parse("2025-06-30T00:00:00Z");
		EventFilter base = EventFilter.builder().to(t1).build();
		EventFilter other = EventFilter.builder().to(t2).build();
		assertThat(base.merge(other).to()).isEqualTo(t2);
	}

	@Test
	void otherMessageTypesOverrideBase() {
		EventFilter base = EventFilter.builder().messageTypes(Set.of(MessageType.USER)).build();
		EventFilter other = EventFilter.builder().messageTypes(Set.of(MessageType.ASSISTANT)).build();
		assertThat(base.merge(other).messageTypes()).containsExactly(MessageType.ASSISTANT);
	}

	@Test
	void otherKeywordOverridesBase() {
		EventFilter base = EventFilter.builder().keyword("spring").pageSize(5).build();
		EventFilter other = EventFilter.builder().keyword("ai").pageSize(5).build();
		assertThat(base.merge(other).keyword()).isEqualTo("ai");
	}

	@Test
	void otherPageAndPageSizeOverrideBase() {
		EventFilter base = EventFilter.builder().page(0).pageSize(10).build();
		EventFilter other = EventFilter.builder().page(2).pageSize(5).build();
		EventFilter merged = base.merge(other);
		assertThat(merged.page()).isEqualTo(2);
		assertThat(merged.pageSize()).isEqualTo(5);
	}

	// --- base fields are kept when other has no value ---

	@Test
	void baseLastNKeptWhenOtherIsAll() {
		EventFilter base = EventFilter.lastN(5);
		EventFilter other = EventFilter.all();
		assertThat(base.merge(other).lastN()).isEqualTo(5);
	}

	@Test
	void baseBranchKeptWhenOtherHasNoBranch() {
		EventFilter base = EventFilter.forBranch("orch.writer");
		EventFilter other = EventFilter.all();
		assertThat(base.merge(other).branch()).isEqualTo("orch.writer");
	}

	// --- excludeSynthetic is OR-ed ---

	@Test
	void excludeSyntheticIsTrueWhenEitherSideIsTrue() {
		assertThat(EventFilter.all().merge(EventFilter.realOnly()).excludeSynthetic()).isTrue();
		assertThat(EventFilter.realOnly().merge(EventFilter.all()).excludeSynthetic()).isTrue();
	}

	@Test
	void excludeSyntheticIsFalseWhenBothAreFalse() {
		assertThat(EventFilter.all().merge(EventFilter.all()).excludeSynthetic()).isFalse();
	}

	// --- merging two EventFilter.all() produces all() ---

	@Test
	void mergingTwoAllFiltersProducesAll() {
		EventFilter merged = EventFilter.all().merge(EventFilter.all());
		assertThat(merged).isEqualTo(EventFilter.all());
	}

}
