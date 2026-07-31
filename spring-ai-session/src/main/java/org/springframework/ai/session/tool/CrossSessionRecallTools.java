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

package org.springframework.ai.session.tool;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.EventFilter.MatchMode;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.util.json.JsonParser;
import org.springframework.util.StringUtils;

/**
 * Cross-session Recall Storage for maintenance/analysis agents.
 *
 * <p>
 * {@code SessionEventTools#conversationSearch} resolves exactly one session from the
 * live request's {@code ToolContext} — the right scope for a conversational agent
 * answering "what did we discuss earlier in <em>this</em> conversation." This class
 * instead searches <strong>every session belonging to one user</strong>, which is what a
 * background maintenance agent (e.g. Auto-Dream's Dreamer) needs to mine historical
 * signal spanning many past conversations.
 *
 * <p>
 * Unlike {@code conversation_search}, the target user is bound at construction time via
 * {@link #builder(SessionService, String)} rather than accepted as a tool-call parameter
 * — a misbehaving prompt cannot talk this tool into scanning a different user's sessions,
 * because the model never gets to choose which user it searches.
 *
 * <p>
 * Read-only: this class has no append/compact/delete capability.
 *
 * @author Christian Tzolov
 * @since 2.0
 */
public class CrossSessionRecallTools {

	private static final Logger logger = LoggerFactory.getLogger(CrossSessionRecallTools.class);

	/**
	 * Upper bound on the number of comma-separated terms accepted in {@code query}. Each
	 * term becomes its own {@code LIKE} predicate (and, on a JDBC-backed
	 * {@code SessionService}, its own bound SQL parameter) — without a cap, a very long
	 * comma-separated {@code query} from a misbehaving or adversarial caller would grow
	 * the generated query and parameter list without bound. Not a security boundary in
	 * itself (every term is still safely bound, never concatenated into SQL text), just a
	 * sanity limit on how large a single search request is allowed to be.
	 */
	static final int MAX_QUERY_TERMS = 20;

	private final SessionService sessionService;

	private final String userId;

	private final int pageSize;

	private CrossSessionRecallTools(SessionService sessionService, String userId, int pageSize) {
		this.sessionService = sessionService;
		this.userId = userId;
		this.pageSize = pageSize;
	}

	/**
	 * @param sessionService the session store to search
	 * @param userId the user whose sessions this instance is scoped to — fixed for the
	 * lifetime of the instance, never supplied by the model
	 */
	public static Builder builder(SessionService sessionService, String userId) {
		return new Builder(sessionService, userId);
	}

	/**
	 * Searches every session belonging to the configured user for events matching the
	 * given criteria, aggregated and returned in chronological order across sessions.
	 * @param innerThought agent's private reasoning (not returned to the caller)
	 * @param query case-insensitive keyword, or comma-separated keywords
	 * @param matchMode {@code "any"} (default) or {@code "all"} — how multiple
	 * comma-separated keywords in {@code query} combine
	 * @param since ISO-8601 instant; only events at or after this time are considered.
	 * Omit to search the full history
	 * @param page zero-indexed page of results; omit or pass {@code 0} for the first page
	 * @return JSON array of matching events, or {@code "No results found."} if empty
	 */
	@Tool(name = "cross_session_search",
			description = "Search across ALL of a user's sessions (not just the current one) for events matching "
					+ "the given criteria. Intended for maintenance/analysis agents that need historical signal "
					+ "spanning many past conversations, not for use by a live conversational agent (which should "
					+ "prefer conversation_search). Returns paginated results ordered chronologically.")
	public String crossSessionSearch(
			@ToolParam(description = "Deep inner monologue private to you only.") String innerThought,
			@ToolParam(
					description = "Case-insensitive keyword to search for. Supply multiple, comma-separated keywords "
							+ "to search for more than one term at once (up to 20 terms per call).") String query,
			@ToolParam(description = "'any' (default) or 'all' — how multiple comma-separated keywords in 'query' "
					+ "combine.", required = false) String matchMode,
			@ToolParam(description = "ISO-8601 instant (e.g. '2026-07-01T00:00:00Z'); only events at or after this "
					+ "time are considered. Omit to search the full history.", required = false) String since,
			@ToolParam(description = "Page of results to retrieve (0-indexed). Omit or use 0 for the first page.",
					required = false) Integer page) {

		int pageNumber = (page != null) ? Math.max(0, page) : 0;

		logger.debug("[cross_session_search] userId: {}, innerThought: {}, query: {}, matchMode: {}, since: {}, page: {}",
				this.userId, innerThought, query, matchMode, since, pageNumber);

		EventFilter filter = buildFilter(query, matchMode, since);

		List<Session> sessions = this.sessionService.findByUserId(this.userId);

		List<SessionEvent> allMatches = new ArrayList<>();
		for (Session session : sessions) {
			for (SessionEvent event : this.sessionService.getEvents(session.id(), filter)) {
				if (StringUtils.hasText(event.getMessage().getText())) {
					allMatches.add(event);
				}
			}
		}

		// Sort by the actual Instant, not its String rendering. Instant.toString()
		// omits the fractional-seconds component when it is exactly zero but includes it
		// otherwise, so two rendered timestamps of different lengths do not reliably
		// compare in chronological order (e.g. "...:00.001Z" < "...:00Z" lexicographically,
		// even though the former instant is later).
		allMatches.sort(Comparator.comparing(SessionEvent::getTimestamp));

		// Cast to long before multiplying so a large `page` cannot silently overflow int
		// arithmetic and produce a negative index — mirrors the same guard already used
		// for the SQL OFFSET parameter in JdbcSessionRepository.findEvents.
		long fromIndexLong = (long) pageNumber * this.pageSize;
		int fromIndex = (int) Math.min(fromIndexLong, allMatches.size());
		int toIndex = (int) Math.min(fromIndexLong + this.pageSize, allMatches.size());
		List<SessionEvent> pageResults = allMatches.subList(fromIndex, toIndex);

		if (pageResults.isEmpty()) {
			return "No results found.";
		}

		List<Map<String, String>> jsonResults = pageResults.stream()
			.map(event -> Map.of("sessionId", event.getSessionId(), "timestamp", event.getTimestamp().toString(),
					"type", event.getMessageType().getValue(), "text", event.getMessage().getText()))
			.toList();

		return JsonParser.toJson(jsonResults);
	}

	private EventFilter buildFilter(String query, String matchMode, String since) {
		EventFilter.Builder builder = EventFilter.builder();

		if (StringUtils.hasText(query)) {
			List<String> terms = List.of(query.split(","))
				.stream()
				.map(String::trim)
				.filter(StringUtils::hasText)
				.toList();
			if (terms.isEmpty()) {
				// e.g. query = "," or ", " — has visible characters so it isn't blank,
				// but splits into zero usable terms. Silently falling through to "no
				// keyword filter" would turn a narrow search into "return everything for
				// this user", so reject it explicitly instead.
				throw new IllegalArgumentException(
						"query '" + query + "' contained no usable search terms after splitting on ','");
			}
			if (terms.size() > MAX_QUERY_TERMS) {
				throw new IllegalArgumentException("query supplied " + terms.size() + " comma-separated terms, "
						+ "exceeding the maximum of " + MAX_QUERY_TERMS + " — narrow the search instead of "
						+ "combining many terms into a single call");
			}
			if (terms.size() > 1) {
				MatchMode mode = "all".equalsIgnoreCase(matchMode) ? MatchMode.ALL : MatchMode.ANY;
				builder.keywords(terms).matchMode(mode);
			}
			else {
				builder.keyword(terms.get(0));
			}
		}

		if (StringUtils.hasText(since)) {
			try {
				builder.from(Instant.parse(since));
			}
			catch (DateTimeParseException ex) {
				throw new IllegalArgumentException(
						"since '" + since + "' is not a valid ISO-8601 instant (e.g. '2026-07-01T00:00:00Z')", ex);
			}
		}

		return builder.build();
	}

	public static final class Builder {

		private final SessionService sessionService;

		private final String userId;

		private int pageSize = EventFilter.DEFAULT_PAGE_SIZE;

		private Builder(SessionService sessionService, String userId) {
			if (sessionService == null) {
				throw new IllegalArgumentException("sessionService must not be null");
			}
			if (!StringUtils.hasText(userId)) {
				throw new IllegalArgumentException("userId must not be empty");
			}
			this.sessionService = sessionService;
			this.userId = userId;
		}

		/**
		 * Number of results returned per page by {@code cross_session_search}. Defaults
		 * to {@link EventFilter#DEFAULT_PAGE_SIZE}.
		 */
		public Builder pageSize(int pageSize) {
			if (pageSize <= 0) {
				throw new IllegalArgumentException("pageSize must be positive");
			}
			this.pageSize = pageSize;
			return this;
		}

		public CrossSessionRecallTools build() {
			return new CrossSessionRecallTools(this.sessionService, this.userId, this.pageSize);
		}

	}

}
