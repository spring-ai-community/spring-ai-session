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

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.SessionEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link CompactionScope}.
 */
class CompactionScopeTests {

	private static final String SESSION_ID = "test-session";

	// --- session() ---

	@Test
	void sessionScopeIsSession() {
		assertThat(CompactionScope.session().isSession()).isTrue();
		assertThat(CompactionScope.session().branch()).isNull();
	}

	@Test
	void sessionScopeOwnsEveryEvent() {
		CompactionScope scope = CompactionScope.session();
		assertThat(scope.owns(rootEvent())).isTrue();
		assertThat(scope.owns(branchEvent("planner"))).isTrue();
		assertThat(scope.owns(branchEvent("planner.sub"))).isTrue();
	}

	@Test
	void sessionScopeTurnBoundaryIsRootEventOnly() {
		CompactionScope scope = CompactionScope.session();
		assertThat(scope.isTurnBoundary(rootEvent())).isTrue();
		assertThat(scope.isTurnBoundary(branchEvent("planner"))).isFalse();
	}

	@Test
	void sessionScopeSummaryBranchIsNull() {
		assertThat(CompactionScope.session().summaryBranch()).isNull();
	}

	// --- branch(...) ---

	@Test
	void branchScopeIsNotSession() {
		assertThat(CompactionScope.branch("planner").isSession()).isFalse();
		assertThat(CompactionScope.branch("planner").branch()).isEqualTo("planner");
	}

	@Test
	void branchScopeOwnsExactBranchAndSubBranches() {
		CompactionScope scope = CompactionScope.branch("planner");
		assertThat(scope.owns(branchEvent("planner"))).isTrue();
		assertThat(scope.owns(branchEvent("planner.sub"))).isTrue();
		assertThat(scope.owns(branchEvent("planner.sub.deep"))).isTrue();
	}

	@Test
	void branchScopeDoesNotOwnRootOrAncestorOrSiblingEvents() {
		CompactionScope scope = CompactionScope.branch("planner");
		assertThat(scope.owns(rootEvent())).isFalse(); // root — must not be archived by a branch compaction
		assertThat(scope.owns(branchEvent("researcher"))).isFalse(); // sibling
		assertThat(scope.owns(branchEvent("plan"))).isFalse(); // NOT a dot-prefix ancestor match — "plan" != "planner"
	}

	@Test
	void branchScopeTurnBoundaryIsExactBranchOnly() {
		CompactionScope scope = CompactionScope.branch("planner");
		assertThat(scope.isTurnBoundary(branchEvent("planner"))).isTrue();
		// sub-branch events are turn-internal, not boundaries — mirrors root/branch nesting
		assertThat(scope.isTurnBoundary(branchEvent("planner.sub"))).isFalse();
		assertThat(scope.isTurnBoundary(rootEvent())).isFalse();
	}

	@Test
	void branchScopeSummaryBranchIsTheScopeBranch() {
		assertThat(CompactionScope.branch("planner").summaryBranch()).isEqualTo("planner");
	}

	@Test
	void branchRejectsNullOrBlank() {
		assertThatThrownBy(() -> CompactionScope.branch(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> CompactionScope.branch("")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> CompactionScope.branch("   ")).isInstanceOf(IllegalArgumentException.class);
	}

	// --- helpers ---

	private static SessionEvent rootEvent() {
		return SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage("root")).build();
	}

	private static SessionEvent branchEvent(String branch) {
		return SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage("msg")).branch(branch).build();
	}

}
