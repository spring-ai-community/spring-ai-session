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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Tests for the {@link Session} value and the tree links its builder enforces.
 *
 * @author Christian Tzolov
 */
class SessionTests {

	@Test
	void aRootIsItsOwnRoot() {
		Session root = Session.builder().id("root").build();

		assertThat(root.isRoot()).isTrue();
		assertThat(root.isChild()).isFalse();
		assertThat(root.parentSessionId()).isNull();
		assertThat(root.rootSessionId()).isEqualTo("root");
		// Naming itself as the root is allowed, anything else is not
		assertThat(Session.builder().id("root").rootSessionId("root").build().rootSessionId()).isEqualTo("root");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> Session.builder().id("root").rootSessionId("other").build());
	}

	@Test
	void aChildNamesItsParentAndItsRoot() {
		Session child = Session.builder().id("t").parentSessionId("p").rootSessionId("r").build();

		assertThat(child.isChild()).isTrue();
		assertThat(child.isRoot()).isFalse();
		assertThat(child.parentSessionId()).isEqualTo("p");
		assertThat(child.rootSessionId()).isEqualTo("r");
	}

	@Test
	void theBuilderRejectsATreeThatCannotExist() {
		assertThatIllegalArgumentException().as("a child without a root")
			.isThrownBy(() -> Session.builder().id("t").parentSessionId("p").build());
		assertThatIllegalArgumentException().as("its own parent")
			.isThrownBy(() -> Session.builder().id("t").parentSessionId("t").rootSessionId("r").build());
		assertThatIllegalArgumentException().as("its own root while having a parent")
			.isThrownBy(() -> Session.builder().id("t").parentSessionId("p").rootSessionId("t").build());
	}

}
