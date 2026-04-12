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

import org.jspecify.annotations.Nullable;

import org.springframework.ai.chat.messages.MessageType;

/**
 * Criteria for filtering {@link SessionEvent}s when retrieving session history.
 *
 * <p>
 * Filters are composable: all non-null criteria must match for an event to be included.
 * {@link #lastN} and {@link #page}/{@link #pageSize} are post-match retrieval modifiers
 * applied after per-event matching.
 *
 * <p>
 * <strong>Retrieval modifier contract:</strong>
 * <ul>
 * <li>{@link #lastN} and {@link #pageSize} are <em>mutually exclusive</em>; setting both
 * throws {@link IllegalArgumentException}.</li>
 * <li>If {@link #pageSize} is set and {@link #page} is {@code null}, {@link #page}
 * defaults to {@code 0} (first page).</li>
 * <li>Setting {@link #page} without {@link #pageSize} throws
 * {@link IllegalArgumentException}.</li>
 * <li>{@link #lastN} must be greater than zero if set.</li>
 * <li>{@link #pageSize} must be greater than zero if set.</li>
 * <li>{@link #page} must be non-negative if set.</li>
 * <li>Paginated results are sliced from the per-event-filtered list in <em>chronological
 * order</em> (oldest first). Page 0 therefore contains the oldest matching events, and
 * the highest-numbered page contains the most recent ones.</li>
 * </ul>
 *
 * <p>
 * Branch filtering implements the MemGPT / Google ADK isolation rule for multi-agent
 * sessions: an event at branch {@code X} is visible to an agent at branch {@code Y} if
 * {@code X} is {@code null} (a root event), equals {@code Y}, or is a dot-prefix ancestor
 * of {@code Y} (e.g. {@code "orch"} is an ancestor of {@code "orch.researcher"}).
 *
 * <p>
 * Use the static factory methods for common cases or {@link #builder()} for custom
 * combinations:
 *
 * <pre>{@code
 * EventFilter filter = EventFilter.builder()
 *     .from(Instant.parse("2025-01-01T00:00:00Z"))
 *     .messageTypes(Set.of(MessageType.USER, MessageType.ASSISTANT))
 *     .excludeSynthetic(true)
 *     .branch("orch.researcher")
 *     .build();
 * }</pre>
 *
 * @author Christian Tzolov
 * @since 2.0.0
 */
public record EventFilter(@Nullable Instant from, @Nullable Instant to, @Nullable Set<MessageType> messageTypes,
		boolean excludeSynthetic, @Nullable Integer lastN, @Nullable String keyword, @Nullable Integer page,
		@Nullable Integer pageSize, @Nullable String branch) {

	public EventFilter {
		keyword = (keyword != null && !keyword.isBlank()) ? keyword.toLowerCase() : null;
		messageTypes = (messageTypes != null && !messageTypes.isEmpty()) ? messageTypes : null;
		if (lastN != null && lastN <= 0) {
			throw new IllegalArgumentException("lastN must be greater than 0");
		}
		if (lastN != null && pageSize != null) {
			throw new IllegalArgumentException("lastN and page/pageSize are mutually exclusive");
		}
		if (pageSize != null && pageSize <= 0) {
			throw new IllegalArgumentException("pageSize must be greater than 0");
		}
		if (page != null && page < 0) {
			throw new IllegalArgumentException("page must be >= 0");
		}
		if (page != null && pageSize == null) {
			throw new IllegalArgumentException("pageSize must be set when page is set");
		}
		if (pageSize != null && page == null) {
			page = 0;
		}
	}

	public EventFilter merge(EventFilter other) {
		return new EventFilter(other.from != null ? other.from : this.from, other.to != null ? other.to : this.to,
				other.messageTypes != null ? other.messageTypes : this.messageTypes,
				other.excludeSynthetic || this.excludeSynthetic, other.lastN != null ? other.lastN : this.lastN,
				other.keyword != null ? other.keyword : this.keyword, other.page != null ? other.page : this.page,
				other.pageSize != null ? other.pageSize : this.pageSize,
				other.branch != null ? other.branch : this.branch);
	}

	/** Default number of results per page used by {@link #keywordSearch(String)}. */
	public static final int DEFAULT_PAGE_SIZE = 10;

	/** Returns all events with no filtering. */
	public static EventFilter all() {
		return builder().build();
	}

	/** Returns the last {@code n} events. */
	public static EventFilter lastN(int n) {
		return builder().lastN(n).build();
	}

	/** Excludes synthetic (framework-generated) events such as compaction summaries. */
	public static EventFilter realOnly() {
		return builder().excludeSynthetic(true).build();
	}

	/**
	 * Returns the first page of events whose message text contains {@code keyword}
	 * (case-insensitive substring match). Uses {@link #DEFAULT_PAGE_SIZE}.
	 */
	public static EventFilter keywordSearch(String keyword) {
		return builder().keyword(keyword).page(0).pageSize(DEFAULT_PAGE_SIZE).build();
	}

	/**
	 * Returns a specific page of events whose message text contains {@code keyword}
	 * (case-insensitive substring match).
	 * @param keyword the search term
	 * @param page zero-indexed page number
	 * @param pageSize number of results per page
	 */
	public static EventFilter keywordSearch(String keyword, int page, int pageSize) {
		return builder().keyword(keyword).page(page).pageSize(pageSize).build();
	}

	/**
	 * Returns events that are visible to an agent at the given {@code agentBranch}.
	 *
	 * <p>
	 * An event is included if its branch is:
	 * <ul>
	 * <li>{@code null} — a root event produced before any delegation, visible to all
	 * agents</li>
	 * <li>equal to {@code agentBranch} — the agent's own events</li>
	 * <li>a dot-prefix ancestor of {@code agentBranch} — events from a parent agent (e.g.
	 * event branch {@code "orch"} is visible to {@code "orch.researcher"})</li>
	 * </ul>
	 *
	 * Peer sub-agents (e.g. {@code "orch.writer"} vs {@code "orch.researcher"}) never see
	 * each other's events.
	 * @param agentBranch the dot-separated branch path of the querying agent (e.g.
	 * {@code "orchestrator.researcher"})
	 */
	public static EventFilter forBranch(String agentBranch) {
		return builder().branch(agentBranch).build();
	}

	/** Returns a new {@link Builder} for constructing a custom {@link EventFilter}. */
	public static Builder builder() {
		return new Builder();
	}

	// Per-event predicate

	/**
	 * Returns {@code true} if the given event passes all per-event criteria in this
	 * filter. Note: {@link #lastN}, {@link #page}, and {@link #pageSize} are applied at
	 * the collection level by the repository, not here.
	 */
	public boolean matches(SessionEvent event) {
		if (this.excludeSynthetic && event.isSynthetic()) {
			return false;
		}
		if (this.from != null && event.getTimestamp().isBefore(this.from)) {
			return false;
		}
		if (this.to != null && event.getTimestamp().isAfter(this.to)) {
			return false;
		}
		if (this.messageTypes != null && !this.messageTypes.contains(event.getMessageType())) {
			return false;
		}
		if (this.keyword != null) {
			String text = event.getMessage().getText();
			if (text == null || !text.toLowerCase().contains(this.keyword)) {
				return false;
			}
		}
		if (this.branch != null) {
			String eventBranch = event.getBranch();
			if (eventBranch != null) {
				// eventBranch is visible to filterBranch if it is the same branch or an
				// ancestor (i.e. filterBranch starts with eventBranch + ".")
				// TODO: what convention to use for branching trees (. or / or something
				// else)? Should we support wildcards (e.g. "orch.*")?
				// TODO: Should we support rootEventId for branch?
				boolean visible = this.branch.equals(eventBranch) || this.branch.startsWith(eventBranch + ".");
				if (!visible) {
					return false;
				}
			}
			// eventBranch == null: root event, visible to all agents
		}
		return true;
	}

	/**
	 * Builder for {@link EventFilter}. All fields default to {@code null} /
	 * {@code false}, producing a filter equivalent to {@link EventFilter#all()} when no
	 * setters are called.
	 */
	public static final class Builder {

		private @Nullable Instant from;

		private @Nullable Instant to;

		private @Nullable Set<MessageType> messageTypes;

		private boolean excludeSynthetic = false;

		private @Nullable Integer lastN;

		private @Nullable String keyword;

		private @Nullable Integer page;

		private @Nullable Integer pageSize;

		private @Nullable String branch;

		private Builder() {
		}

		/** Only include events at or after this instant. */
		public Builder from(@Nullable Instant from) {
			this.from = from;
			return this;
		}

		/** Only include events at or before this instant. */
		public Builder to(@Nullable Instant to) {
			this.to = to;
			return this;
		}

		/**
		 * Only include events whose {@link SessionEvent#getMessageType()} is in this set.
		 */
		public Builder messageTypes(@Nullable Set<MessageType> messageTypes) {
			this.messageTypes = messageTypes;
			return this;
		}

		/**
		 * When {@code true}, synthetic framework events (compaction summaries) are
		 * excluded.
		 */
		public Builder excludeSynthetic(boolean excludeSynthetic) {
			this.excludeSynthetic = excludeSynthetic;
			return this;
		}

		/**
		 * Return at most the last {@code n} matching events (applied after all per-event
		 * filters).
		 */
		public Builder lastN(@Nullable Integer lastN) {
			this.lastN = lastN;
			return this;
		}

		/**
		 * Case-insensitive substring to match against {@code message.getText()}. Events
		 * whose text is {@code null} or does not contain the keyword are excluded.
		 */
		public Builder keyword(@Nullable String keyword) {
			this.keyword = keyword;
			return this;
		}

		/**
		 * Zero-indexed page number for paginated results. Applied after per-event
		 * filtering in chronological order (oldest first), so page 0 contains the oldest
		 * matching events. Requires {@link #pageSize(Integer)} to be set.
		 */
		public Builder page(@Nullable Integer page) {
			this.page = page;
			return this;
		}

		/**
		 * Number of results per page. Defaults to {@link EventFilter#DEFAULT_PAGE_SIZE}.
		 */
		public Builder pageSize(@Nullable Integer pageSize) {
			this.pageSize = pageSize;
			return this;
		}

		/**
		 * Restricts results to events visible to the agent at this dot-separated branch
		 * path. See {@link EventFilter#forBranch(String)} for the full visibility rule.
		 */
		public Builder branch(@Nullable String branch) {
			this.branch = branch;
			return this;
		}

		/** Constructs the {@link EventFilter}. */
		public EventFilter build() {
			return new EventFilter(this.from, this.to, this.messageTypes, this.excludeSynthetic, this.lastN,
					this.keyword, this.page, this.pageSize, this.branch);
		}

	}

}
