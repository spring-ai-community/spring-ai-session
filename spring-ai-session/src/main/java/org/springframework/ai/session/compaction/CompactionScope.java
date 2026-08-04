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

import org.jspecify.annotations.Nullable;

import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.SessionEvent;
import org.springframework.util.Assert;

/**
 * Defines what a compaction pass is allowed to summarize/archive and what counts as a
 * turn boundary for budgeting purposes.
 *
 * <p>
 * Deliberately a separate type from {@link EventFilter}, because the two express
 * <em>inverse</em> predicates:
 * <ul>
 * <li>{@link EventFilter#forBranch(String)} is <em>ancestor-inclusive</em> — it answers
 * "what is visible to an agent at this branch," which includes root events and every
 * ancestor branch.</li>
 * <li>{@link CompactionScope#branch(String)} is <em>owned-only</em> — it answers "what
 * may this compaction pass archive or rewrite," which must exclude root and ancestor
 * events, since a branch must never archive events it does not own.</li>
 * </ul>
 * {@link EventFilter} also carries retrieval-only concerns ({@code lastN},
 * {@code page}/{@code pageSize}, {@code keyword}, {@code from}/{@code to}) that have no
 * meaningful interpretation as a compaction policy. Reusing it as a scope would overload
 * a retrieval type as a lifecycle-policy type.
 *
 * <p>
 * {@link #session()} reproduces the original (pre-scope) behaviour exactly: it owns every
 * event, and only root-level ({@code branch == null}) events are turn boundaries. This is
 * the default used everywhere compaction is not explicitly scoped to a branch.
 *
 * @param branch the branch this scope is restricted to, or {@code null} for whole-session
 * scope
 * @author Christian Tzolov
 * @since 2.0.0
 */
public record CompactionScope(@Nullable String branch) {

	/**
	 * Whole-session scope: owns every event, and root-level ({@code branch == null})
	 * events are turn boundaries. Reproduces pre-scope compaction behaviour exactly.
	 */
	public static CompactionScope session() {
		return new CompactionScope(null);
	}

	/**
	 * Scope restricted to the given branch and its sub-branches (e.g. {@code "planner"}
	 * owns {@code "planner"} and {@code "planner.sub"}, but not root or sibling
	 * branches).
	 * @param branch the branch path this scope is restricted to; must not be
	 * {@code null}
	 */
	public static CompactionScope branch(String branch) {
		Assert.hasText(branch, "branch must not be null or empty");
		return new CompactionScope(branch);
	}

	/** Returns {@code true} if this is whole-session scope ({@code branch == null}). */
	public boolean isSession() {
		return this.branch == null;
	}

	/**
	 * Returns {@code true} if this scope may archive/rewrite the given event.
	 * <p>
	 * Session scope owns everything. Branch scope owns only events whose branch equals
	 * this scope's branch or is a dot-prefix descendant of it (e.g. scope {@code
	 * "planner"} owns branch {@code "planner.sub"}) — root and ancestor events are never
	 * owned, since a branch compaction must never archive events a sibling or the root
	 * conversation depends on.
	 */
	public boolean owns(SessionEvent event) {
		if (isSession()) {
			return true;
		}
		String eventBranch = event.getBranch();
		return eventBranch != null && (eventBranch.equals(this.branch) || eventBranch.startsWith(this.branch + "."));
	}

	/**
	 * Returns {@code true} if the given event counts as a turn boundary for this scope's
	 * budgeting purposes.
	 * <p>
	 * Session scope: root-level ({@link SessionEvent#isRootEvent()}) events are
	 * boundaries; branch events are turn-internal and ride along with the enclosing root
	 * turn. Branch scope: events on exactly this branch are boundaries; events on
	 * sub-branches (e.g. {@code "planner.sub"} under scope {@code "planner"}) are
	 * turn-internal and ride along with the enclosing branch turn — the same nesting
	 * rule applied one level down.
	 */
	public boolean isTurnBoundary(SessionEvent event) {
		return isSession() ? event.isRootEvent() : this.branch.equals(event.getBranch());
	}

	/**
	 * The branch a synthetic compaction summary produced under this scope should be
	 * stamped with. {@code null} for session scope (summaries land on root, as today);
	 * otherwise this scope's branch, so the summary is only visible within the branch it
	 * summarizes.
	 */
	@Nullable public String summaryBranch() {
		return this.branch;
	}

}
