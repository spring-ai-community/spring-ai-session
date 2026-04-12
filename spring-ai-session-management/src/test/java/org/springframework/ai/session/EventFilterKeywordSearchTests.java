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

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for keyword search and pagination in {@link EventFilter} and
 * {@link InMemorySessionRepository}.
 */
class EventFilterKeywordSearchTests {

	private static final String SESSION_ID = "test-session";

	private InMemorySessionRepository repository;

	@BeforeEach
	void setUp() {
		this.repository = InMemorySessionRepository.builder().build();
		Session session = Session.builder().id(SESSION_ID).userId("test-user").build();
		this.repository.save(session);
	}

	// --- EventFilter.matches() ---

	@Test
	void matchesReturnsTrueWhenKeywordFoundCaseInsensitive() {
		SessionEvent event = SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(new UserMessage("Spring AI is awesome"))
			.build();
		assertThat(EventFilter.keywordSearch("spring ai").matches(event)).isTrue();
		assertThat(EventFilter.keywordSearch("AWESOME").matches(event)).isTrue();
		assertThat(EventFilter.keywordSearch("is").matches(event)).isTrue();
	}

	@Test
	void matchesReturnsFalseWhenKeywordNotFound() {
		SessionEvent event = SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(new UserMessage("Spring AI is awesome"))
			.build();
		assertThat(EventFilter.keywordSearch("memory").matches(event)).isFalse();
	}

	@Test
	void matchesReturnsFalseWhenMessageTextIsNull() {
		// AssistantMessage with no text (e.g. tool-call only) has null/empty getText()
		SessionEvent event = SessionEvent.builder().sessionId(SESSION_ID).message(new AssistantMessage("")).build();
		assertThat(EventFilter.keywordSearch("anything").matches(event)).isFalse();
	}

	@Test
	void matchesReturnsTrueWhenKeywordIsNullOrBlank() {
		// keyword=null means no keyword filter — all events should pass
		SessionEvent event = SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage("hello")).build();
		assertThat(EventFilter.all().matches(event)).isTrue();
	}

	// --- Repository keyword search ---

	@Test
	void findEventsReturnsOnlyMatchingEvents() {
		appendText("Spring AI is a great framework");
		appendText("I like machine learning");
		appendText("Spring Boot is also useful");
		appendText("Deep learning is fascinating");

		List<SessionEvent> results = this.repository.findEvents(SESSION_ID, EventFilter.keywordSearch("spring"));

		assertThat(results).hasSize(2);
		assertThat(results.get(0).getMessage().getText()).isEqualTo("Spring AI is a great framework");
		assertThat(results.get(1).getMessage().getText()).isEqualTo("Spring Boot is also useful");
	}

	@Test
	void findEventsKeywordIsCaseInsensitive() {
		appendText("Spring AI rocks");
		appendText("spring boot too");
		appendText("nothing here");

		List<SessionEvent> results = this.repository.findEvents(SESSION_ID, EventFilter.keywordSearch("SPRING"));

		assertThat(results).hasSize(2);
	}

	@Test
	void findEventsReturnsEmptyWhenNoMatch() {
		appendText("Spring AI is great");
		appendText("Machine learning too");

		List<SessionEvent> results = this.repository.findEvents(SESSION_ID, EventFilter.keywordSearch("nosuchthing"));

		assertThat(results).isEmpty();
	}

	// --- Pagination ---

	@Test
	void paginationFirstPageReturnsCorrectSlice() {
		for (int i = 1; i <= 7; i++) {
			appendText("spring message " + i);
		}

		List<SessionEvent> page0 = this.repository.findEvents(SESSION_ID,
				EventFilter.keywordSearch("spring message", 0, 3));
		List<SessionEvent> page1 = this.repository.findEvents(SESSION_ID,
				EventFilter.keywordSearch("spring message", 1, 3));
		List<SessionEvent> page2 = this.repository.findEvents(SESSION_ID,
				EventFilter.keywordSearch("spring message", 2, 3));

		assertThat(page0).hasSize(3);
		assertThat(page0.get(0).getMessage().getText()).isEqualTo("spring message 1");
		assertThat(page0.get(2).getMessage().getText()).isEqualTo("spring message 3");

		assertThat(page1).hasSize(3);
		assertThat(page1.get(0).getMessage().getText()).isEqualTo("spring message 4");
		assertThat(page1.get(2).getMessage().getText()).isEqualTo("spring message 6");

		assertThat(page2).hasSize(1);
		assertThat(page2.get(0).getMessage().getText()).isEqualTo("spring message 7");
	}

	@Test
	void paginationBeyondLastPageReturnsEmpty() {
		appendText("spring message 1");
		appendText("spring message 2");

		List<SessionEvent> result = this.repository.findEvents(SESSION_ID,
				EventFilter.keywordSearch("spring message", 5, 10));

		assertThat(result).isEmpty();
	}

	@Test
	void paginationFirstPageDefaultPageSize() {
		for (int i = 1; i <= 15; i++) {
			appendText("entry " + i);
		}

		// Default page size is 10; first page should return events 1–10
		List<SessionEvent> page0 = this.repository.findEvents(SESSION_ID, EventFilter.keywordSearch("entry"));

		assertThat(page0).hasSize(EventFilter.DEFAULT_PAGE_SIZE);
		assertThat(page0.get(0).getMessage().getText()).isEqualTo("entry 1");
		assertThat(page0.get(9).getMessage().getText()).isEqualTo("entry 10");
	}

	@Test
	void paginationOnlyMatchingEventsAreIncluded() {
		// 5 matching + 5 non-matching interleaved
		for (int i = 1; i <= 5; i++) {
			appendText("keyword hit " + i);
			appendText("no match " + i);
		}

		List<SessionEvent> results = this.repository.findEvents(SESSION_ID,
				EventFilter.keywordSearch("keyword hit", 0, 10));

		assertThat(results).hasSize(5);
		results.forEach(e -> assertThat(e.getMessage().getText()).startsWith("keyword hit"));
	}

	// --- helpers ---

	private void appendText(String text) {
		this.repository
			.appendEvent(SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage(text)).build());
	}

	static String newSessionId() {
		return UUID.randomUUID().toString();
	}

}
