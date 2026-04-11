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

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.internal.DefaultSessionService;
import org.springframework.ai.session.internal.InMemorySessionRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SessionEventTools#conversationSearch}.
 */
class SessionEventToolsTests {

	private SessionService sessionService;

	private SessionEventTools tools;

	private String sessionId;

	@BeforeEach
	void setUp() {
		this.sessionService = new DefaultSessionService(InMemorySessionRepository.builder().build());
		this.tools = SessionEventTools.builder(this.sessionService).build();

		Session session = this.sessionService.create(CreateSessionRequest.builder().userId("test-user").build());
		this.sessionId = session.id();
	}

	@Test
	void returnsNoResultsWhenHistoryIsEmpty() {
		String result = search("anything", 0);
		assertThat(result).isEqualTo("No results found.");
	}

	@Test
	void returnsMatchingMessagesAsJson() {
		this.sessionService.appendMessage(this.sessionId, new UserMessage("Tell me about Spring AI"));
		this.sessionService.appendMessage(this.sessionId, new AssistantMessage("Spring AI is a framework..."));
		this.sessionService.appendMessage(this.sessionId, new UserMessage("What about LangChain?"));
		this.sessionService.appendMessage(this.sessionId, new AssistantMessage("LangChain is a Python library."));

		String result = search("spring", 0);

		assertThat(result).contains("Spring AI");
		assertThat(result).doesNotContain("LangChain");
	}

	@Test
	void searchIsCaseInsensitive() {
		this.sessionService.appendMessage(this.sessionId, new UserMessage("Spring AI rocks"));
		this.sessionService.appendMessage(this.sessionId, new UserMessage("spring boot too"));

		String result = search("SPRING", 0);

		assertThat(result).contains("Spring AI rocks");
		assertThat(result).contains("spring boot too");
	}

	@Test
	void returnsNoResultsWhenKeywordNotFound() {
		this.sessionService.appendMessage(this.sessionId, new UserMessage("Hello world"));
		this.sessionService.appendMessage(this.sessionId, new AssistantMessage("Hi there!"));

		String result = search("kubernetes", 0);

		assertThat(result).isEqualTo("No results found.");
	}

	@Test
	void paginationReturnsCorrectPage() {
		for (int i = 1; i <= 15; i++) {
			this.sessionService.appendMessage(this.sessionId, new UserMessage("recall item " + i));
		}

		String page0 = search("recall item", 0);
		String page1 = search("recall item", 1);

		// page 0 should have 10 results (DEFAULT_PAGE_SIZE), page 1 should have 5
		assertThat(page0).contains("recall item 1");
		assertThat(page0).contains("recall item 10");
		assertThat(page0).doesNotContain("recall item 11");

		assertThat(page1).contains("recall item 11");
		assertThat(page1).contains("recall item 15");
		assertThat(page1).doesNotContain("recall item 1\""); // not on page 1
	}

	@Test
	void paginationBeyondLastPageReturnsNoResults() {
		this.sessionService.appendMessage(this.sessionId, new UserMessage("only one match"));

		String result = search("only one", 5);

		assertThat(result).isEqualTo("No results found.");
	}

	@Test
	void negativePageDefaultsToFirstPage() {
		this.sessionService.appendMessage(this.sessionId, new UserMessage("Spring AI memory"));

		// A model might supply page=-1; the tool must not throw and must return page 0
		String withNegative = this.tools.conversationSearch("thinking...", "spring", -1, toolContext());
		String withZero = search("spring", 0);

		assertThat(withNegative).isEqualTo(withZero);
	}

	@Test
	void nullPageDefaultsToFirstPage() {
		this.sessionService.appendMessage(this.sessionId, new UserMessage("Spring AI memory"));

		// page=null should behave identically to page=0
		String withNull = searchWithNullPage("spring");
		String withZero = search("spring", 0);

		assertThat(withNull).isEqualTo(withZero);
	}

	@Test
	void resultContainsTimestampTypeAndText() {
		this.sessionService.appendMessage(this.sessionId, new UserMessage("Spring AI is interesting"));

		String result = search("interesting", 0);

		assertThat(result).contains("timestamp");
		assertThat(result).contains("type");
		assertThat(result).contains("text");
		assertThat(result).contains("user"); // MessageType.getValue() returns lowercase
												// role name
		assertThat(result).contains("Spring AI is interesting");
	}

	@Test
	void syntheticEventsAreIncludedInSearch() {
		this.sessionService.appendEvent(SessionEvent.builder()
			.sessionId(this.sessionId)
			.message(new UserMessage("Summarize the conversation we had so far."))
			.metadata(SessionEvent.METADATA_SYNTHETIC, true)
			.metadata(SessionEvent.METADATA_COMPACTION_SOURCE, "test")
			.build());
		this.sessionService.appendEvent(SessionEvent.builder()
			.sessionId(this.sessionId)
			.message(new AssistantMessage("The user discussed Spring AI memory management."))
			.metadata(SessionEvent.METADATA_SYNTHETIC, true)
			.metadata(SessionEvent.METADATA_COMPACTION_SOURCE, "test")
			.build());

		// Synthetic summary text is searchable too
		String result = search("memory management", 0);

		assertThat(result).contains("memory management");
	}

	// --- helpers ---

	private String search(String query, int page) {
		return this.tools.conversationSearch("thinking...", query, page, toolContext());
	}

	private String searchWithNullPage(String query) {
		return this.tools.conversationSearch("thinking...", query, null, toolContext());
	}

	private ToolContext toolContext() {
		return new ToolContext(Map.of(SessionEventTools.SESSION_ID_CONTEXT_KEY, this.sessionId));
	}

}
