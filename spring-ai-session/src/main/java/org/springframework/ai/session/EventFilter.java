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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.session.compaction.CompactionBoundary;
import org.springframework.util.Assert;

/**
 * Criteria for reading {@link SessionEvent}s: per-event criteria (a time range, message
 * and event types, synthetic events, keywords), the view (the whole log or the
 * {@linkplain Builder#activeView(boolean) active view}), a retrieval {@link Window} and
 * an {@link Order}.
 *
 * <p>
 * All per-event criteria set must match for an event to be included. The window is
 * applied after per-event matching, to the matching events in log order:
 * <ul>
 * <li>{@link Window.All}: every matching event.</li>
 * <li>{@link Window.Last}: the newest N matching events of one session, extended back to
 * the start of the turn the window lands in (see {@link SessionEvent#isTurnStart()}), so
 * a prompt never begins with an assistant reply or a tool result whose user message or
 * tool call was cut off. The extension is skipped for filters with text criteria (a
 * search result is not a prompt), when no turn start precedes the window, and across
 * sessions.</li>
 * <li>{@link Window.Page}: a page of the matching events, oldest first, so page 0 holds
 * the oldest.</li>
 * <li>{@link Window.After}: the matching events logged after a given event, oldest
 * first, at most {@code limit} of them: a cursor for reading what happened since an
 * event a client has seen. The cursor event itself need not match the criteria. A
 * cursor that names no event of the session is an error. Not available across
 * sessions.</li>
 * </ul>
 * {@link Order#DESC} reverses the windowed result, newest first.
 *
 * <p>
 * A {@link #pattern()} is not part of the {@link SessionRepository} read contract: a
 * store ignores it, and {@link SessionService} applies it after the read, before the
 * window. Use the static factory methods for common cases or {@link #builder()} for
 * custom combinations:
 *
 * <pre>{@code
 * EventFilter filter = EventFilter.builder()
 *     .from(Instant.parse("2025-01-01T00:00:00Z"))
 *     .messageTypes(Set.of(MessageType.USER, MessageType.ASSISTANT))
 *     .excludeSynthetic(true)
 *     .build();
 * EventFilter since = EventFilter.builder().after(lastSeenEventId).build();
 * }</pre>
 *
 * @author Christian Tzolov
 * @since 0.1.0
 */
public record EventFilter(@Nullable Instant from, @Nullable Instant to, @Nullable Set<MessageType> messageTypes,
		@Nullable Set<EventType> eventTypes, boolean excludeSynthetic, boolean activeView,
		@Nullable List<String> keywords, @Nullable MatchMode matchMode, @Nullable Pattern pattern, Window window,
		@Nullable Order order) {

	/** How multiple {@link #keywords()} combine when matching an event's text. */
	public enum MatchMode {

		/** Match if the text contains at least one of the keywords. */
		ANY,

		/** Match only if the text contains all of the keywords. */
		ALL

	}

	/** The order of the returned events. */
	public enum Order {

		/** Oldest first: log order. The default. */
		ASC,

		/** Newest first. */
		DESC

	}

	/** The retrieval window: which of the matching events are returned. */
	public sealed interface Window permits Window.All, Window.Last, Window.Page, Window.After {

		/** Every matching event. */
		Window ALL = new All();

		/** The newest {@code n} matching events, extended to the start of their turn. */
		static Window last(int n) {
			return new Last(n);
		}

		/** Page {@code page} (zero-based) of {@code size} matching events, oldest first. */
		static Window page(int page, int size) {
			return new Page(page, size);
		}

		/** Every matching event logged after the given one. */
		static Window after(String eventId) {
			return new After(eventId, null);
		}

		/** At most {@code limit} matching events logged after the given one. */
		static Window after(String eventId, int limit) {
			return new After(eventId, limit);
		}

		/** Every matching event. */
		record All() implements Window {
		}

		/** The newest {@code n} matching events. */
		record Last(int n) implements Window {
			public Last {
				if (n <= 0) {
					throw new IllegalArgumentException("lastN must be greater than 0");
				}
			}
		}

		/** A page of the matching events, oldest first. */
		record Page(int page, int size) implements Window {
			public Page {
				if (page < 0) {
					throw new IllegalArgumentException("page must be >= 0");
				}
				if (size <= 0) {
					throw new IllegalArgumentException("pageSize must be greater than 0");
				}
			}
		}

		/** The matching events after a given event: a cursor. */
		record After(String eventId, @Nullable Integer limit) implements Window {
			public After {
				Assert.hasText(eventId, "eventId must not be null or empty");
				if (limit != null && limit <= 0) {
					throw new IllegalArgumentException("limit must be greater than 0");
				}
			}
		}

	}

	public EventFilter {
		keywords = (keywords != null && !keywords.isEmpty()) ? keywords.stream()
			.filter(k -> k != null && !k.isBlank())
			.map(k -> k.toLowerCase(Locale.ROOT))
			.toList() : null;
		keywords = (keywords != null && keywords.isEmpty()) ? null : keywords;
		matchMode = (keywords != null) ? (matchMode != null ? matchMode : MatchMode.ANY) : null;
		messageTypes = (messageTypes != null && !messageTypes.isEmpty()) ? Set.copyOf(messageTypes) : null;
		eventTypes = (eventTypes != null && !eventTypes.isEmpty()) ? Set.copyOf(eventTypes) : null;
		Assert.notNull(window, "window must not be null");
	}

	/**
	 * Returns a filter combining this (base) filter with {@code other}, where every
	 * criterion set on {@code other} takes precedence. The {@code excludeSynthetic} and
	 * {@code activeView} flags are OR-ed (either side can only narrow the result). The
	 * window of {@code other} replaces this filter's window unless it is
	 * {@link Window#ALL}, so a per-request page can override a base {@code last} window.
	 */
	public EventFilter merge(EventFilter other) {
		Assert.notNull(other, "other must not be null");
		return toBuilder().from(other.from != null ? other.from : this.from)
			.to(other.to != null ? other.to : this.to)
			.messageTypes(other.messageTypes != null ? other.messageTypes : this.messageTypes)
			.eventTypes(other.eventTypes != null ? other.eventTypes : this.eventTypes)
			.excludeSynthetic(other.excludeSynthetic || this.excludeSynthetic)
			.activeView(other.activeView || this.activeView)
			.keywords(other.keywords != null ? other.keywords : this.keywords)
			.matchMode(other.matchMode != null ? other.matchMode : this.matchMode)
			.pattern(other.pattern != null ? other.pattern : this.pattern)
			.window(!(other.window instanceof Window.All) ? other.window : this.window)
			.order(other.order != null ? other.order : this.order)
			.build();
	}

	/** Default number of results per page used by {@link #keywordSearch(String)}. */
	public static final int DEFAULT_PAGE_SIZE = 10;

	/**
	 * Returns the whole log with no filtering, <em>including</em> the events compaction has
	 * covered and the compaction boundaries themselves. Used by Recall Storage so the full
	 * verbatim history remains searchable after compaction. For building the active context
	 * window use {@link #active()} instead.
	 */
	public static EventFilter all() {
		return builder().build();
	}

	/**
	 * Returns the active conversation: the {@link EventType#MESSAGE} events of the
	 * {@linkplain Builder#activeView(boolean) active view}, in prompt order. This is
	 * the view that should be injected into the prompt as the active context window, and
	 * the input of compaction. Events of any other type record runtime state, not
	 * conversation, and are left out.
	 */
	public static EventFilter active() {
		return builder().activeView(true).eventTypes(Set.of(EventType.MESSAGE)).build();
	}

	/** Returns the last {@code n} events. */
	public static EventFilter lastN(int n) {
		return builder().lastN(n).build();
	}

	/** Returns every event logged after the given one: what happened since a client saw it. */
	public static EventFilter after(String eventId) {
		return builder().after(eventId).build();
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
	 * caller via {@link Pattern#CASE_INSENSITIVE} on the compiled {@code pattern}. The
	 * pattern is applied by the {@link SessionService}, after the store's read.
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
	 * Returns {@code true} if the given event passes the per-event criteria a store
	 * applies: the time range, the event and message types, the synthetic flag and the
	 * keywords. The {@link #pattern()} is not among them (see {@link #matchesPattern});
	 * the view, the window and the order are applied at the collection level.
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
		if (this.eventTypes != null && !this.eventTypes.contains(event.getType())) {
			return false;
		}
		if (this.messageTypes != null
				&& (!event.hasMessage() || !this.messageTypes.contains(event.getMessageType()))) {
			return false;
		}
		// Keywords match message text; an event without a message has none.
		if (this.keywords != null) {
			String text = event.hasMessage() ? event.getMessage().getText() : null;
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
		return true;
	}

	/**
	 * Returns {@code true} if the event's message text matches the {@link #pattern()}, or
	 * the filter has none. Applied by the {@link SessionService} after the store's read.
	 */
	public boolean matchesPattern(SessionEvent event) {
		if (this.pattern == null) {
			return true;
		}
		String text = event.hasMessage() ? event.getMessage().getText() : null;
		return text != null && this.pattern.matcher(text).find();
	}

	/**
	 * Applies this filter to the events of one session held in memory: with
	 * {@link #activeView()} first reduces the log to its active view
	 * ({@link CompactionBoundary#project(List)}), then keeps the events that
	 * {@link #matches(SessionEvent)} (the {@link #pattern()} is left to the
	 * {@link SessionService}), then applies the window keeping turns whole
	 * ({@link #applyTurnAwareWindow(List)}) and the order. This is the reference
	 * implementation of the read contract for a log held as a list; a store that can push
	 * the criteria down to a query should do so and use this only for what it cannot
	 * express (see {@link SessionRepository#findEvents}).
	 * @param events the events of one session, oldest first: the whole log, or for an
	 * active view any superset of the view that includes the newest boundary
	 * @return the matching events, never the input list itself
	 * @throws IllegalArgumentException if an {@link Window.After} cursor names no event
	 */
	public List<SessionEvent> apply(List<SessionEvent> events) {
		Assert.notNull(events, "events must not be null");
		List<SessionEvent> base = this.activeView ? CompactionBoundary.project(events) : events;
		base = afterCursor(base);
		return applyTurnAwareWindow(base.stream().filter(this::matches).toList());
	}

	/**
	 * Cuts the events at the {@link Window.After} cursor, if this filter has one: the
	 * events after the cursor event, in the given order. The cursor is located in the
	 * unfiltered events, so it need not match the criteria.
	 * @param events the events of one session, oldest first
	 * @return the events after the cursor, or the input when the window is not a cursor
	 * @throws IllegalArgumentException if the cursor names no event
	 */
	public List<SessionEvent> afterCursor(List<SessionEvent> events) {
		if (!(this.window instanceof Window.After after)) {
			return events;
		}
		for (int i = 0; i < events.size(); i++) {
			if (after.eventId().equals(events.get(i).getId())) {
				return events.subList(i + 1, events.size());
			}
		}
		throw new IllegalArgumentException("Cursor event '" + after.eventId()
				+ "' is not among the events read: it is unknown, or no longer in the view (compacted)");
	}

	/**
	 * Applies the window and the order to an already filtered per-session log, oldest
	 * first, keeping turns whole: a {@link Window.Last} window whose first event is not a
	 * {@linkplain SessionEvent#isTurnStart() turn start} is extended back to the nearest
	 * turn start before it, so an assistant tool call is never returned without the user
	 * message and, by extension, the tool results of its turn. The extension is skipped
	 * when the filter {@linkplain #hasTextCriteria() searches text} (the result is not a
	 * prompt) and when no turn start precedes the window (a preamble of stored system
	 * messages, or a {@link #messageTypes()} filter that excludes {@code USER}); the plain
	 * window is returned then. Pages and cursors are always plain. This is the reference
	 * implementation of the {@link SessionRepository#findEvents} window contract.
	 * @param matched the filtered events of one session, oldest first, already cut at
	 * the cursor for a {@link Window.After} window
	 * @return the windowed events, in this filter's order
	 */
	public List<SessionEvent> applyTurnAwareWindow(List<SessionEvent> matched) {
		Assert.notNull(matched, "matched must not be null");
		if (!(this.window instanceof Window.Last last) || hasTextCriteria() || matched.size() <= last.n()) {
			return applyWindow(matched);
		}
		int from = matched.size() - last.n();
		if (!matched.get(from).isTurnStart()) {
			for (int i = from - 1; i >= 0; i--) {
				if (matched.get(i).isTurnStart()) {
					from = i;
					break;
				}
			}
		}
		return ordered(matched.subList(from, matched.size()));
	}

	/**
	 * Returns {@code true} when this filter searches message text: {@link #keywords()} or
	 * a {@link #pattern()} is set. Such a read is a search, not a prompt, so its window is
	 * not extended to turn boundaries.
	 */
	public boolean hasTextCriteria() {
		return this.keywords != null || this.pattern != null;
	}

	/**
	 * Applies only the plain window and the order to an already filtered list, oldest
	 * first, without regard to turns. Used for reads across sessions, where turns do not
	 * exist; a per-session read uses {@link #applyTurnAwareWindow(List)}. A page beyond
	 * the end yields an empty list; a page number large enough to overflow an {@code int}
	 * offset does too.
	 * @param matched the filtered events, oldest first, already cut at the cursor for a
	 * {@link Window.After} window
	 * @return the windowed events, in this filter's order
	 */
	public List<SessionEvent> applyWindow(List<SessionEvent> matched) {
		Assert.notNull(matched, "matched must not be null");
		List<SessionEvent> windowed = matched;
		if (this.window instanceof Window.Last last && matched.size() > last.n()) {
			windowed = matched.subList(matched.size() - last.n(), matched.size());
		}
		else if (this.window instanceof Window.Page page) {
			// long arithmetic: a large page number must not overflow into a negative index
			long fromIdx = (long) page.page() * page.size();
			windowed = (fromIdx >= matched.size()) ? List.of()
					: matched.subList((int) fromIdx, (int) Math.min(fromIdx + page.size(), matched.size()));
		}
		else if (this.window instanceof Window.After after && after.limit() != null
				&& matched.size() > after.limit()) {
			windowed = matched.subList(0, after.limit());
		}
		return ordered(windowed);
	}

	/** The events in this filter's order: a copy, reversed for {@link Order#DESC}. */
	public List<SessionEvent> ordered(List<SessionEvent> events) {
		if (this.order == Order.DESC) {
			List<SessionEvent> reversed = new ArrayList<>(events);
			Collections.reverse(reversed);
			return Collections.unmodifiableList(reversed);
		}
		return List.copyOf(events);
	}

	/**
	 * Returns this filter without its window ({@link Window#ALL}), keeping every other
	 * criterion. Used to run the same criteria over several sessions before windowing the
	 * combined result.
	 */
	public EventFilter withoutWindow() {
		return toBuilder().window(Window.ALL).build();
	}

	/** Returns this filter without its {@link #pattern()}: what a store is asked for. */
	public EventFilter withoutPattern() {
		return toBuilder().pattern(null).build();
	}

	/** Returns a builder initialised with every criterion of this filter. */
	public Builder toBuilder() {
		return new Builder(this);
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

		private @Nullable Set<EventType> eventTypes;

		private boolean excludeSynthetic = false;

		private boolean activeView = false;

		private @Nullable List<String> keywords;

		private @Nullable MatchMode matchMode;

		private @Nullable Pattern pattern;

		private Window window = Window.ALL;

		private @Nullable Integer page;

		private @Nullable Integer pageSize;

		private @Nullable Order order;

		private Builder() {
		}

		private Builder(EventFilter source) {
			this.from = source.from;
			this.to = source.to;
			this.messageTypes = source.messageTypes;
			this.eventTypes = source.eventTypes;
			this.excludeSynthetic = source.excludeSynthetic;
			this.activeView = source.activeView;
			this.keywords = source.keywords;
			this.matchMode = source.matchMode;
			this.pattern = source.pattern;
			this.order = source.order;
			if (source.window instanceof Window.Page paged) {
				this.page = paged.page();
				this.pageSize = paged.size();
			}
			else {
				this.window = source.window;
			}
		}

		/**
		 * Only include events whose {@link SessionEvent#getType()} is in this set. Unset
		 * (the default) includes every type; {@link EventFilter#active()} selects
		 * {@link EventType#MESSAGE} only.
		 */
		public Builder eventTypes(@Nullable Set<EventType> eventTypes) {
			this.eventTypes = eventTypes;
			return this;
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
		 * Only include events carrying a message whose {@link SessionEvent#getMessageType()}
		 * is in this set: {@link EventType#MESSAGE} events and the tree markers that carry
		 * one (see {@link EventType#canCarryMessage()}). An event without a message never
		 * matches; add {@link #eventTypes} to narrow to {@code MESSAGE}.
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

		/** The retrieval window; defaults to {@link Window#ALL}. */
		public Builder window(Window window) {
			Assert.notNull(window, "window must not be null");
			this.window = window;
			this.page = null;
			this.pageSize = null;
			return this;
		}

		/**
		 * Return at most the last {@code n} matching events (applied after all per-event
		 * filters): {@link Window#last(int)}. {@code null} clears the window.
		 */
		public Builder lastN(@Nullable Integer lastN) {
			return window(lastN != null ? Window.last(lastN) : Window.ALL);
		}

		/**
		 * Return the matching events logged after the given event, oldest first:
		 * {@link Window#after(String)}, a cursor.
		 */
		public Builder after(String eventId) {
			return window(Window.after(eventId));
		}

		/** Return at most {@code limit} matching events logged after the given event. */
		public Builder after(String eventId, int limit) {
			return window(Window.after(eventId, limit));
		}

		/** Page {@code page} (zero-based) of {@code size} matching events: {@link Window#page(int, int)}. */
		public Builder page(int page, int size) {
			return window(Window.page(page, size));
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

		/** The order of the result; {@link Order#ASC} (oldest first) when unset. */
		public Builder order(@Nullable Order order) {
			this.order = order;
			return this;
		}

		/**
		 * Case-insensitive substring to match against {@code message.getText()}: a
		 * one-term {@link #keywords(List)}. {@code null} clears the keywords.
		 */
		public Builder keyword(@Nullable String keyword) {
			this.keywords = (keyword != null) ? List.of(keyword) : null;
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
		 * {@code null} or does not match are excluded. Applied by the
		 * {@link SessionService} after the store's read, before the window.
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
		 * When {@code true}, the filter applies to the active view of the log as defined by
		 * its newest compaction boundary (see {@link CompactionBoundary#project(List)}):
		 * the pinned events, the summary, the preserved events and everything appended
		 * since, in that order. The events compaction covered are left out. Used to build
		 * the active context window; leave {@code false} (the default) for Recall Storage
		 * searches that must see the full history.
		 * <p>
		 * Compaction reads the conversation only, so a boundary names message events. An
		 * event of another type (status, usage and the like) logged before the boundary
		 * is therefore not in the view, while one logged after it is. Read runtime state
		 * without this flag.
		 */
		public Builder activeView(boolean activeView) {
			this.activeView = activeView;
			return this;
		}

		/**
		 * Constructs the {@link EventFilter}.
		 * @throws IllegalArgumentException if {@link #page(Integer)} is set without
		 * {@link #pageSize(Integer)}, or a page is set together with another window
		 */
		public EventFilter build() {
			Window window = this.window;
			if (this.pageSize != null) {
				if (!(window instanceof Window.All)) {
					throw new IllegalArgumentException("page/pageSize and another window are mutually exclusive");
				}
				window = Window.page(this.page != null ? this.page : 0, this.pageSize);
			}
			else if (this.page != null) {
				throw new IllegalArgumentException("pageSize must be set when page is set");
			}
			return new EventFilter(this.from, this.to, this.messageTypes, this.eventTypes, this.excludeSynthetic,
					this.activeView, this.keywords, this.matchMode, this.pattern, window, this.order);
		}

	}

}
