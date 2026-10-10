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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SessionEventToolsBlankQueryTests {

	private SessionService service;

	private SessionEventTools tools;

	private String sessionId;

	@BeforeEach
	void setUp() {
		this.service = DefaultSessionService.builder()
			.sessionRepository(InMemorySessionRepository.builder().build())
			.build();
		this.sessionId = this.service.create(CreateSessionRequest.builder().userId("test-user").build()).id();
		this.tools = SessionEventTools.builder(this.service).build();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { " ", "\t\n" })
	void blankQueryIsRejectedInsteadOfReturningUnfilteredHistory(String query) {
		this.service.appendMessage(this.sessionId, new UserMessage("history that should not become a search result"));

		assertThatIllegalArgumentException().isThrownBy(() -> search(query)).withMessageContaining("query");
	}

	@Test
	void blankQueryIsRejectedWhenHistoryIsEmpty() {
		assertThatIllegalArgumentException().isThrownBy(() -> search(" ")).withMessageContaining("query");
	}

	@Test
	void missingSessionIdRetainsItsErrorForABlankQuery() {
		String result = this.tools.conversationSearch("thinking", " ", 0, new ToolContext(Map.of()));

		assertThat(result).isEqualTo(SessionEventTools.MISSING_SESSION_ID_RESULT);
	}

	@Test
	void validQueryStillReturnsOnlyMatchingHistory() {
		this.service.appendMessage(this.sessionId, new UserMessage("Spring AI recall"));
		this.service.appendMessage(this.sessionId, new UserMessage("unrelated history"));

		assertThat(search("spring")).contains("Spring AI recall").doesNotContain("unrelated history");
	}

	private String search(String query) {
		return this.tools.conversationSearch("thinking", query, 0,
				new ToolContext(Map.of(SessionEventTools.SESSION_ID_CONTEXT_KEY, this.sessionId)));
	}

}
