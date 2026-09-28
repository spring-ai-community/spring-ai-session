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
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.chat.messages.MessageType;
import org.springframework.util.Assert;

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
 * <li>{@link #lastN} is a lower bound on a per-session read: the newest N matching events
 * are returned, extended back to the start of the turn the window lands in (see
 * {@link SessionEvent#isTurnStart()}), so a prompt never begins with an assistant reply
 * or a tool result whose user message or tool call was cut off. The extension is skipped
 * for filters with text criteria (a search result is not a prompt) and when no turn start
 * precedes the window. Pages and cross-session reads are plain. See
 * {@link #applyTurnAwareWindow(List)}.</li>
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
 * Use the static factory methods for common cases or {@link #builder()} for custom
 * combinations:
 *
 * <pre>{@code
 * EventFilter filter = EventFilter.builder()
 *     .from(Instant.parse("2025-01-01T00:00:00Z"))
 *     .messageTypes(Set.of(MessageType.USER, MessageType.ASSISTANT))
 *     .excludeSynthetic(true)
 *     .build();
 * }</pre>
 *
 * @author Christian Tzolov
 * @since 2.0.0
 */
public record EventFilter(@Nullable Instant from, @Nullable Instant to, @Nullable Set<MessageType> messageTypes,
		boolean excludeSynthetic, @Nullable Integer lastN, @Nullable String keyword,
		@Nullable List<String> keywords, @Nullable MatchMode matchMode, @Nullable Pattern pattern,
		@Nullable Integer page, @Nullable Integer pageSize, boolean excludeArchived) {

	/**
	 * How multiple {@link #keywords()} combine when matching an event's text.
	 */
	public enum MatchMode {

		/** Match if the text contains at least one of the keywords. */
		ANY,

		/** Match only if the text contains all of the keywords. */
		ALL

	}

	public EventFilter {
		keyword = (keyword != null && !keyword.isBlank()) ? keyword.toLowerCase(Locale.ROOT) : null;
		keywords = (keywords != null && !keywords.isEmpty()) ? keywords.stream()
			.filter(k -> k != null && !k.isBlank())
			.map(k -> k.toLowerCase(Locale.ROOT))
			.toList() : null;
		keywords = (keywords != null && keywords.isEmpty()) ? null : keywords;
		matchMode = (keywords != null) ? (matchMode != null ? matchMode : MatchMode.ANY) : null;
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

	/**
	 * Returns a filter combining this (base) filter with {@code other}, where every
	 * criterion set on {@code other} takes precedence. The {@code excludeSynthetic} and
	 * {@code excludeArchived} flags are OR-ed (either side can only narrow the result).
	 *
	 * <p>
	 * The retrieval modifier — {@link #lastN} or {@link #page}/{@link #pageSize} — is
	 * treated as a single unit: if {@code other} sets either form, it replaces this
	 * filter's modifier entirely. This lets a per-request paginated search override a base
	 * {@code lastN} window (and vice versa) instead of failing on the mutually exclusive
	 * combination.
	 */
	public EventFilter merge(EventFilter other) {
		boolean otherHasRetrievalModifier = other.lastN != null || other.pageSize != null;
		EventFilter retrieval = otherHasRetrievalModifier ? other : this;
		return new EventFilter(other.from != null ? other.from : this.from, other.to != null ? other.to : this.to,
				other.messageTypes != null ? other.messageTypes : this.messageTypes,
				other.excludeSynthetic || this.excludeSynthetic, retrieval.lastN,
				other.keyword != null ? other.keyword : this.keyword,
				other.keywords != null ? other.keywords : this.keywords,
				other.matchMode != null ? other.matchMode : this.matchMode,
				other.pattern != null ? other.pattern : this.pattern, retrieval.page, retrieval.pageSize,
				other.excludeArchived || this.excludeArchived);
	}

	/** Default number of results per page used by {@link #keywordSearch(String)}. */
	public static final int DEFAULT_PAGE_SIZE = 10;

	/**
	 * Returns all events with no filtering, <em>including</em> archived events. Used by
	 * Recall Storage so the full verbatim history remains searchable after compaction. For
	 * building the active context window use {@link #active()} instead.
	 */
	public static EventFilter all() {
		return builder().build();
	}

	/**
	 * Returns only the active events — i.e. excludes events archived by compaction. This is
	 * the view that should be injected into the prompt as the active context window.
	 */
	public static EventFilter active() {
		return builder().excludeArchived(true).build();
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
	 * Returns the first page of events whose message text contains, depending on
	 * {@code matchMode}, any or all of {@code terms} (case-insensitive substring match
	 * per term). Uses {@link #DEFAULT_PAGE_SIZE}.
	 */
	public static EventFilter keywordsSearch(List<String> terms, MatchMode matchMode) {
		return builder().keywords(terms).matchMode(matchMode).page(0).pageSize(DEFAULT_PAGE_SIZE).build();
	}

	/**
	 * Returns the first page of events whose message text matches the given regular
	 * expression. Uses {@link #DEFAULT_PAGE_SIZE}. Case sensitivity is controlled by the
	 * caller via {@link Pattern#CASE_INSENSITIVE} on the compiled {@code pattern}.
	 *
	 * <p>
	 * <strong>Security:</strong> {@code pattern} must be a {@link Pattern} the calling
	 * <em>code</em> compiled from a fixed or developer-authored expression. Never call
	 * {@link Pattern#compile(String)} on a string sourced from a user, an LLM tool-call
	 * argument, or any other untrusted input and pass the result here (or to
	 * {@link Builder#pattern(Pattern)}) — an attacker-chosen regular expression can exhibit
	 * catastrophic backtracking (ReDoS) when evaluated against attacker-influenced message
	 * text such as {@link SessionEvent} content, causing denial of service. This type
	 * intentionally has no {@code @Tool}-annotated entry point that accepts a raw regex
	 * string for exactly this reason — keep it that way.
	 */
	public static EventFilter patternSearch(Pattern pattern) {
		return builder().pattern(pattern).page(0).pageSize(DEFAULT_PAGE_SIZE).build();
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
		if (this.excludeArchived && event.isArchived()) {
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
			if (text == null || !text.toLowerCase(Locale.ROOT).contains(this.keyword)) {
				return false;
			}
		}
		if (this.keywords != null) {
			String text = event.getMessage().getText();
			if (text == null) {
				return false;
			}
			String lowerText = text.toLowerCase(Locale.ROOT);
			boolean matched = (this.matchMode == MatchMode.ALL) ? this.keywords.stream().allMatch(lowerText::contains)
					: this.keywords.stream().anyMatch(lowerText::contains);
			if (!matched) {
				return false;
			}
		}
		if (this.pattern != null) {
			String text = event.getMessage().getText();
			if (text == null || !this.pattern.matcher(text).find()) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Applies this filter to a list of events held in memory: keeps the events that
	 * {@link #matches(SessionEvent)}, then applies the retrieval window keeping turns
	 * whole ({@link #applyTurnAwareWindow(List)}). This is the reference implementation of the read
	 * contract for a log held as a list; a store that can push the criteria down to a
	 * query should do so and use this only for what it cannot express (see
	 * {@link SessionRepository#findEvents}).
	 * @param events the events, oldest first
	 * @return the matching events, oldest first, never the input list itself
	 */
	public List<SessionEvent> apply(List<SessionEvent> events) {
		Assert.notNull(events, "events must not be null");
		return applyTurnAwareWindow(events.stream().filter(this::matches).toList());
	}

	/**
	 * Applies the retrieval window of this filter to an already filtered per-session log,
	 * oldest first, keeping turns whole: a {@link #lastN()} window whose first event is not
	 * a {@linkplain SessionEvent#isTurnStart() turn start} is extended back to the nearest
	 * turn start before it, so an assistant tool call is never returned without the user
	 * message and, by extension, the tool results of its turn. The extension is skipped
	 * when the filter {@linkplain #hasTextCriteria() searches text} (the result is not a
	 * prompt) and when no turn start precedes the window (a preamble of stored system
	 * messages, or a {@link #messageTypes()} filter that excludes {@code USER}); the plain
	 * window is returned then. Pages are always plain. This is the reference
	 * implementation of the {@link SessionRepository#findEvents} window contract.
	 * @param matched the filtered events of one session, oldest first
	 * @return the windowed events
	 */
	public List<SessionEvent> applyTurnAwareWindow(List<SessionEvent> matched) {
		Assert.notNull(matched, "matched must not be null");
		if (this.lastN == null || hasTextCriteria() || matched.size() <= this.lastN) {
			return applyWindow(matched);
		}
		int from = matched.size() - this.lastN;
		if (!matched.get(from).isTurnStart()) {
			for (int i = from - 1; i >= 0; i--) {
				if (matched.get(i).isTurnStart()) {
					from = i;
					break;
				}
			}
		}
		return List.copyOf(matched.subList(from, matched.size()));
	}

	/**
	 * Returns {@code true} when this filter searches message text: a {@link #keyword()},
	 * {@link #keywords()} or {@link #pattern()} is set. Such a read is a search, not a
	 * prompt, so its window is not extended to turn boundaries.
	 */
	public boolean hasTextCriteria() {
		return this.keyword != null || this.keywords != null || this.pattern != null;
	}

	/**
	 * Applies only the plain retrieval window of this filter ({@link #lastN()}, or
	 * {@link #page()} / {@link #pageSize()}) to an already filtered list, oldest first,
	 * without regard to turns. Used for reads across sessions, where turns do not exist;
	 * a per-session read uses {@link #applyTurnAwareWindow(List)}. A page beyond the end
	 * yields an empty list; a page number large enough to overflow an {@code int} offset
	 * does too.
	 * @param matched the filtered events, oldest first
	 * @return the windowed events
	 */
	public List<SessionEvent> applyWindow(List<SessionEvent> matched) {
		Assert.notNull(matched, "matched must not be null");
		if (this.lastN != null) {
			if (matched.size() > this.lastN) {
				return List.copyOf(matched.subList(matched.size() - this.lastN, matched.size()));
			}
			return List.copyOf(matched);
		}
		if (this.pageSize != null) {
			int pageNum = (this.page != null) ? this.page : 0;
			// long arithmetic: a large page number must not overflow into a negative index
			long fromIdx = (long) pageNum * this.pageSize;
			if (fromIdx >= matched.size()) {
				return List.of();
			}
			return List.copyOf(matched.subList((int) fromIdx, (int) Math.min(fromIdx + this.pageSize, matched.size())));
		}
		return List.copyOf(matched);
	}

	/**
	 * Returns this filter without its retrieval window ({@link #lastN()}, {@link #page()}
	 * and {@link #pageSize()} cleared), keeping every per-event criterion. Used to run the
	 * same criteria over several sessions before windowing the combined result.
	 * @return the filter without a window
	 */
	public EventFilter withoutWindow() {
		return new EventFilter(this.from, this.to, this.messageTypes, this.excludeSynthetic, null, this.keyword,
				this.keywords, this.matchMode, this.pattern, null, null, this.excludeArchived);
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

		private @Nullable List<String> keywords;

		private @Nullable MatchMode matchMode;

		private @Nullable Pattern pattern;

		private @Nullable Integer page;

		private @Nullable Integer pageSize;

		private boolean excludeArchived = false;

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
		 * Case-insensitive terms to match against {@code message.getText()}, combined
		 * according to {@link #matchMode(MatchMode)} (default {@link MatchMode#ANY} if
		 * left unset while {@code keywords} is set).
		 */
		public Builder keywords(@Nullable List<String> keywords) {
			this.keywords = keywords;
			return this;
		}

		/**
		 * How {@link #keywords(List)} combine — {@link MatchMode#ANY} (at least one term
		 * present) or {@link MatchMode#ALL} (every term present). Ignored unless
		 * {@code keywords} is also set.
		 */
		public Builder matchMode(@Nullable MatchMode matchMode) {
			this.matchMode = matchMode;
			return this;
		}

		/**
		 * A compiled regular expression evaluated against {@code message.getText()} via
		 * {@link Pattern#matcher(CharSequence)}{@code .find()}. Events whose text is
		 * {@code null} or does not match are excluded.
		 *
		 * <p>
		 * <strong>Security:</strong> see the warning on {@link EventFilter#patternSearch(Pattern)}
		 * — only pass a {@link Pattern} compiled from a fixed or developer-authored
		 * expression, never one compiled from untrusted (e.g. LLM tool-call) input.
		 */
		public Builder pattern(@Nullable Pattern pattern) {
			this.pattern = pattern;
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
		 * Number of results per page. Enables pagination; {@link #page(Integer)} then
		 * defaults to {@code 0}. Unset by default (no pagination) — the
		 * {@link EventFilter#keywordSearch(String)}-style factories use
		 * {@link EventFilter#DEFAULT_PAGE_SIZE}.
		 */
		public Builder pageSize(@Nullable Integer pageSize) {
			this.pageSize = pageSize;
			return this;
		}

		/**
		 * When {@code true}, events archived by compaction are excluded. Used to build the
		 * active context window; leave {@code false} (the default) for Recall Storage
		 * searches that must see the full history.
		 */
		public Builder excludeArchived(boolean excludeArchived) {
			this.excludeArchived = excludeArchived;
			return this;
		}

		/** Constructs the {@link EventFilter}. */
		public EventFilter build() {
			return new EventFilter(this.from, this.to, this.messageTypes, this.excludeSynthetic, this.lastN,
					this.keyword, this.keywords, this.matchMode, this.pattern, this.page, this.pageSize,
					this.excludeArchived);
		}

	}

}
