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

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.memory.ChatMemory;
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
 * {@link ChatMemory#CONVERSATION_ID} key — the same key
 * {@link org.springframework.ai.session.advisor.SessionMemoryAdvisor#SESSION_ID_CONTEXT_KEY}
 * reads from the advisor context. Advisor parameters are <em>not</em> propagated into the
 * {@code ToolContext}, so the caller must pass the session ID to both on every request:
 *
 * <pre>{@code
 * SessionEventTools tools = SessionEventTools.builder(sessionService)
 *     .pageSize(20)
 *     .build();
 * ChatClient client = ChatClient.builder(chatModel)
 *     .defaultTools(tools)
 *     .defaultAdvisors(advisor)
 *     .build();
 *
 * client.prompt()
 *     .user(question)
 *     .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
 *     .toolContext(Map.of(ChatMemory.CONVERSATION_ID, sessionId))
 *     .call()
 *     .content();
 * }</pre>
 *
 * <p>
 * If the key is missing or blank, the tool returns an error message to the model instead
 * of searching — it never falls back to a shared session.
 *
 * @author Christian Tzolov
 * @since 2.0.0
 */
public class SessionEventTools {

	private static final Logger logger = LoggerFactory.getLogger(SessionEventTools.class);

	/**
	 * Context key used to resolve the session ID from {@link ToolContext}. Equals
	 * {@link ChatMemory#CONVERSATION_ID} and
	 * {@link org.springframework.ai.session.advisor.SessionMemoryAdvisor#SESSION_ID_CONTEXT_KEY}.
	 */
	public static final String SESSION_ID_CONTEXT_KEY = ChatMemory.CONVERSATION_ID;

	static final String MISSING_SESSION_ID_RESULT = "Error: conversation_search is unavailable because no session ID "
			+ "was provided in the tool context.";

	private final SessionService sessionService;

	private final int pageSize;

	@Nullable private final String branch;

	private SessionEventTools(SessionService sessionService, int pageSize, @Nullable String branch) {
		this.sessionService = sessionService;
		this.pageSize = pageSize;
		this.branch = branch;
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

		@Nullable private String branch;

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
		 * Restricts {@code conversation_search} to events visible to the agent at this
		 * dot-separated branch path, applying the same isolation rule as
		 * {@link EventFilter#forBranch(String)}: root events, the agent's own events and
		 * its ancestors' events are searchable, peer sub-agents' events are not. Set this
		 * on the tool instance given to a sub-agent in a multi-agent session. Defaults to
		 * {@code null} (search every event in the session).
		 * @param branch the agent's branch path, e.g. {@code "orch.researcher"}
		 * @return this builder
		 */
		public Builder branch(@Nullable String branch) {
			this.branch = branch;
			return this;
		}

		/**
		 * Builds the {@link SessionEventTools} instance.
		 * @return a configured {@code SessionEventTools}
		 */
		public SessionEventTools build() {
			return new SessionEventTools(this.sessionService, this.pageSize, this.branch);
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
	 * @return JSON array of matching events, {@code "No results found."} if empty, or an
	 * error message if the tool context carries no session ID
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
		if (!(sessionIdValue instanceof String sessionId) || sessionId.isBlank()) {
			logger.warn("[conversation_search] '{}' not found in ToolContext — search skipped. Pass the session ID "
					+ "via ChatClient's .toolContext(Map.of(ChatMemory.CONVERSATION_ID, sessionId)).",
					SESSION_ID_CONTEXT_KEY);
			return MISSING_SESSION_ID_RESULT;
		}

		EventFilter filter = EventFilter.keywordSearch(query, pageNumber, this.pageSize);
		if (this.branch != null) {
			filter = filter.merge(EventFilter.forBranch(this.branch));
		}
		List<SessionEvent> events = this.sessionService.getEvents(sessionId, filter);

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
