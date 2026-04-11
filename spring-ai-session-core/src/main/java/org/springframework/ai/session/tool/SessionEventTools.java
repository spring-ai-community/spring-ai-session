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

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.util.json.JsonParser;
import org.springframework.util.StringUtils;

/**
 * Agent-facing tools for searching the session's conversation history (Recall Storage).
 *
 * <p>
 * Mirrors the MemGPT {@code conversation_search} tool: the full verbatim history is
 * retained in the session event log and is always searchable by keyword, even after
 * context compaction has pruned older events from the active context window.
 *
 * <p>
 * The session to search is resolved from {@link ToolContext} using the
 * {@code chat_memory_session_id} key, which is the same key used by
 * {@code SessionMemoryAdvisor}. Register an instance of this class as a tool on the
 * {@code ChatClient} alongside the advisor:
 *
 * <pre>{@code
 * SessionEventTools tools = SessionEventTools.builder(sessionService)
 *     .pageSize(20)
 *     .build();
 * ChatClient client = ChatClient.builder(chatModel)
 *     .defaultTools(tools)
 *     .defaultAdvisors(advisor)
 *     .build();
 * }</pre>
 *
 * @author Christian Tzolov
 * @since 2.0
 */
public class SessionEventTools {

	private static final Logger logger = LoggerFactory.getLogger(SessionEventTools.class);

	/** Context key used to resolve the session ID from {@link ToolContext}. */
	public static final String SESSION_ID_CONTEXT_KEY = "chat_memory_session_id";

	private final SessionService sessionService;

	private final int pageSize;

	private SessionEventTools(SessionService sessionService, int pageSize) {
		this.sessionService = sessionService;
		this.pageSize = pageSize;
	}

	/**
	 * Returns a new {@link Builder} for {@code SessionEventTools}.
	 * @param sessionService the session service to search
	 * @return a new builder
	 */
	public static Builder builder(SessionService sessionService) {
		return new Builder(sessionService);
	}

	/**
	 * Builder for {@link SessionEventTools}.
	 */
	public static final class Builder {

		private final SessionService sessionService;

		private int pageSize = EventFilter.DEFAULT_PAGE_SIZE;

		private Builder(SessionService sessionService) {
			if (sessionService == null) {
				throw new IllegalArgumentException("sessionService must not be null");
			}
			this.sessionService = sessionService;
		}

		/**
		 * Number of results returned per page by {@code conversation_search}.
		 * Defaults to {@link EventFilter#DEFAULT_PAGE_SIZE}.
		 * @param pageSize results per page; must be positive
		 * @return this builder
		 */
		public Builder pageSize(int pageSize) {
			if (pageSize <= 0) {
				throw new IllegalArgumentException("pageSize must be positive");
			}
			this.pageSize = pageSize;
			return this;
		}

		/**
		 * Builds the {@link SessionEventTools} instance.
		 * @return a configured {@code SessionEventTools}
		 */
		public SessionEventTools build() {
			return new SessionEventTools(this.sessionService, this.pageSize);
		}

	}

	/**
	 * Searches the current session's conversation history for events whose message text
	 * contains the given keyword (case-insensitive). Supports pagination for large
	 * histories.
	 *
	 * <p>
	 * Results are returned in chronological order as a JSON array. Each entry contains:
	 * <ul>
	 * <li>{@code timestamp} — ISO-8601 instant the event was recorded</li>
	 * <li>{@code type} — message role ({@code USER}, {@code ASSISTANT},
	 * {@code TOOL})</li>
	 * <li>{@code text} — verbatim message text</li>
	 * </ul>
	 * @param innerThought agent's private reasoning (not returned to the user)
	 * @param query case-insensitive keyword to search for
	 * @param page zero-indexed page of results; omit or pass {@code 0} for the first page
	 * @param toolContext Spring AI tool context carrying the session ID
	 * @return JSON array of matching events, or {@code "No results found."} if empty
	 */
	@Tool(name = "conversation_search",
			description = "Search the full prior conversation history using case-insensitive keyword matching. "
					+ "Returns paginated results ordered chronologically.")
	public String conversationSearch(
			@ToolParam(description = "Deep inner monologue private to you only.") String innerThought,
			@ToolParam(description = "Keyword to search for in the conversation history.") String query,
			@ToolParam(description = "Page of results to retrieve (0-indexed). Omit or use 0 for the first page.",
					required = false) Integer page,
			ToolContext toolContext) {

		int pageNumber = (page != null) ? Math.max(0, page) : 0;

		logger.debug("[conversation_search] innerThought: {}, query: {}, page: {}", innerThought, query, pageNumber);

		Object sessionIdValue = toolContext.getContext().get(SESSION_ID_CONTEXT_KEY);
		String sessionId;
		if (sessionIdValue instanceof String s && !s.isBlank()) {
			sessionId = s;
		}
		else {
			sessionId = "default";
			logger.warn("[conversation_search] '{}' not found in ToolContext — falling back to session ID 'default'. "
					+ "Register SessionMemoryAdvisor alongside this tool so the correct session ID is propagated.",
					SESSION_ID_CONTEXT_KEY);
		}

		List<SessionEvent> events = this.sessionService.getEvents(sessionId,
				EventFilter.keywordSearch(query, pageNumber, this.pageSize));

		List<Map<String, String>> results = events.stream()
			.filter(e -> StringUtils.hasText(e.getMessage().getText()))
			.map(e -> Map.of("timestamp", e.getTimestamp().toString(), "type", e.getMessageType().getValue(), "text",
					e.getMessage().getText()))
			.toList();

		if (results.isEmpty()) {
			return "No results found.";
		}

		return JsonParser.toJson(results);
	}

}
