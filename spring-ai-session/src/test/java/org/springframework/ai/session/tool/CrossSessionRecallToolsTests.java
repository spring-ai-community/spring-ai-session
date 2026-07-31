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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Tests for {@link CrossSessionRecallTools#crossSessionSearch}.
 */
class CrossSessionRecallToolsTests {

	private SessionService sessionService;

	@BeforeEach
	void setUp() {
		this.sessionService = DefaultSessionService.builder()
			.sessionRepository(InMemorySessionRepository.builder().build())
			.build();
	}

	private CrossSessionRecallTools toolsFor(String userId) {
		return CrossSessionRecallTools.builder(this.sessionService, userId).build();
	}

	@Test
	void returnsNoResultsWhenUserHasNoSessions() {
		String result = toolsFor("alice").crossSessionSearch("thinking...", "anything", null, null, 0);
		assertThat(result).isEqualTo("No results found.");
	}

	@Test
	void findsMatchesAcrossMultipleSessions() {
		Session session1 = this.sessionService.create(CreateSessionRequest.builder().userId("alice").build());
		Session session2 = this.sessionService.create(CreateSessionRequest.builder().userId("alice").build());

		this.sessionService.appendMessage(session1.id(), new UserMessage("Tell me about Spring AI"));
		this.sessionService.appendMessage(session2.id(), new UserMessage("What about LangChain?"));
		this.sessionService.appendMessage(session2.id(), new AssistantMessage("Spring AI is a Java framework."));

		String result = toolsFor("alice").crossSessionSearch("thinking...", "spring", null, null, 0);

		assertThat(result).contains("Tell me about Spring AI").contains("Spring AI is a Java framework.");
		assertThat(result).doesNotContain("LangChain");
	}

	@Test
	void resultsIncludeSessionIdAcrossSessions() {
		Session session1 = this.sessionService.create(CreateSessionRequest.builder().userId("alice").build());
		this.sessionService.appendMessage(session1.id(), new UserMessage("Spring AI memory management"));

		String result = toolsFor("alice").crossSessionSearch("thinking...", "memory", null, null, 0);

		assertThat(result).contains("sessionId").contains(session1.id());
	}

	@Test
	void onlySearchesSessionsForTheConfiguredUser() {
		Session aliceSession = this.sessionService.create(CreateSessionRequest.builder().userId("alice").build());
		Session bobSession = this.sessionService.create(CreateSessionRequest.builder().userId("bob").build());

		this.sessionService.appendMessage(aliceSession.id(), new UserMessage("alice secret note"));
		this.sessionService.appendMessage(bobSession.id(), new UserMessage("bob secret note"));

		String result = toolsFor("alice").crossSessionSearch("thinking...", "secret", null, null, 0);

		assertThat(result).contains("alice secret note").doesNotContain("bob secret note");
	}

	@Test
	void multipleCommaSeparatedKeywordsDefaultToAnyMatch() {
		Session session = this.sessionService.create(CreateSessionRequest.builder().userId("alice").build());
		this.sessionService.appendMessage(session.id(), new UserMessage("we decided to ship it"));
		this.sessionService.appendMessage(session.id(), new UserMessage("let's go with option B"));
		this.sessionService.appendMessage(session.id(), new UserMessage("unrelated message"));

		String result = toolsFor("alice").crossSessionSearch("thinking...", "we decided, let's go with", null, null,
				0);

		assertThat(result).contains("we decided to ship it").contains("let's go with option B");
		assertThat(result).doesNotContain("unrelated message");
	}

	@Test
	void allMatchModeRequiresEveryKeyword() {
		Session session = this.sessionService.create(CreateSessionRequest.builder().userId("alice").build());
		this.sessionService.appendMessage(session.id(), new UserMessage("actually, let's use this instead"));
		this.sessionService.appendMessage(session.id(), new UserMessage("actually that's fine as-is"));

		String result = toolsFor("alice").crossSessionSearch("thinking...", "actually, instead", "all", null, 0);

		assertThat(result).contains("actually, let's use this instead");
		assertThat(result).doesNotContain("actually that's fine as-is");
	}

	@Test
	void sinceExcludesEventsBeforeTheGivenInstant() {
		Session session = this.sessionService.create(CreateSessionRequest.builder().userId("alice").build());
		this.sessionService.appendEvent(SessionEvent.builder()
			.sessionId(session.id())
			.timestamp(Instant.parse("2026-01-01T00:00:00Z"))
			.message(new UserMessage("old spring note"))
			.build());
		this.sessionService.appendEvent(SessionEvent.builder()
			.sessionId(session.id())
			.timestamp(Instant.parse("2026-07-01T00:00:00Z"))
			.message(new UserMessage("recent spring note"))
			.build());

		String result = toolsFor("alice").crossSessionSearch("thinking...", "spring", null, "2026-06-01T00:00:00Z", 0);

		assertThat(result).contains("recent spring note").doesNotContain("old spring note");
	}

	@Test
	void malformedSinceThrowsIllegalArgumentExceptionInsteadOfRawParseException() {
		assertThatIllegalArgumentException()
			.isThrownBy(
					() -> toolsFor("alice").crossSessionSearch("thinking...", "spring", null, "2026-07-01", 0))
			.withMessageContaining("2026-07-01");
	}

	@Test
	void commaOnlyQueryThrowsInsteadOfSilentlySearchingEverything() {
		Session session = this.sessionService.create(CreateSessionRequest.builder().userId("alice").build());
		this.sessionService.appendMessage(session.id(), new UserMessage("this should never be returned"));

		assertThatIllegalArgumentException()
			.isThrownBy(() -> toolsFor("alice").crossSessionSearch("thinking...", ",", null, null, 0));
	}

	@Test
	void queryWithinTermLimitIsAccepted() {
		Session session = this.sessionService.create(CreateSessionRequest.builder().userId("alice").build());
		this.sessionService.appendMessage(session.id(), new UserMessage("term9 present"));

		String query = String.join(",", java.util.stream.IntStream.rangeClosed(1, CrossSessionRecallTools.MAX_QUERY_TERMS)
			.mapToObj(i -> "term" + i)
			.toList());

		String result = toolsFor("alice").crossSessionSearch("thinking...", query, null, null, 0);

		assertThat(result).contains("term9 present");
	}

	@Test
	void queryExceedingTermLimitThrows() {
		String query = String.join(",",
				java.util.stream.IntStream.rangeClosed(1, CrossSessionRecallTools.MAX_QUERY_TERMS + 1)
					.mapToObj(i -> "term" + i)
					.toList());

		assertThatIllegalArgumentException()
			.isThrownBy(() -> toolsFor("alice").crossSessionSearch("thinking...", query, null, null, 0))
			.withMessageContaining(String.valueOf(CrossSessionRecallTools.MAX_QUERY_TERMS));
	}

	@Test
	void resultsAreOrderedByActualInstantNotStringRendering() {
		// Instant.toString() omits fractional seconds when exactly zero, so a naive
		// String-lexicographic sort would place "afterExactSecond" before
		// "beforeExactSecond" even though it is chronologically later.
		Session session = this.sessionService.create(CreateSessionRequest.builder().userId("alice").build());
		Instant exactSecond = Instant.parse("2026-01-01T00:00:00Z");

		this.sessionService.appendEvent(SessionEvent.builder()
			.sessionId(session.id())
			.timestamp(exactSecond.minusMillis(1))
			.message(new UserMessage("marker beforeExactSecond"))
			.build());
		this.sessionService.appendEvent(SessionEvent.builder()
			.sessionId(session.id())
			.timestamp(exactSecond)
			.message(new UserMessage("marker onExactSecond"))
			.build());
		this.sessionService.appendEvent(SessionEvent.builder()
			.sessionId(session.id())
			.timestamp(exactSecond.plusMillis(1))
			.message(new UserMessage("marker afterExactSecond"))
			.build());

		String result = toolsFor("alice").crossSessionSearch("thinking...", "marker", null, null, 0);

		int before = result.indexOf("beforeExactSecond");
		int on = result.indexOf("onExactSecond");
		int after = result.indexOf("afterExactSecond");
		assertThat(before).isPositive();
		assertThat(before).isLessThan(on);
		assertThat(on).isLessThan(after);
	}

	@Test
	void veryLargePageNumberReturnsNoResultsInsteadOfOverflowing() {
		Session session = this.sessionService.create(CreateSessionRequest.builder().userId("alice").build());
		this.sessionService.appendMessage(session.id(), new UserMessage("entry 1"));

		String result = toolsFor("alice").crossSessionSearch("thinking...", "entry", null, null, Integer.MAX_VALUE);

		assertThat(result).isEqualTo("No results found.");
	}

	@Test
	void paginationSplitsAggregatedResultsAcrossSessions() {
		Session session1 = this.sessionService.create(CreateSessionRequest.builder().userId("alice").build());
		Session session2 = this.sessionService.create(CreateSessionRequest.builder().userId("alice").build());

		for (int i = 1; i <= 6; i++) {
			this.sessionService.appendMessage(session1.id(), new UserMessage("entry " + i));
		}
		for (int i = 7; i <= 12; i++) {
			this.sessionService.appendMessage(session2.id(), new UserMessage("entry " + i));
		}

		CrossSessionRecallTools tools = CrossSessionRecallTools.builder(this.sessionService, "alice").pageSize(10).build();

		String page0 = tools.crossSessionSearch("thinking...", "entry", null, null, 0);
		String page1 = tools.crossSessionSearch("thinking...", "entry", null, null, 1);

		assertThat(page0).contains("entry 1").doesNotContain("entry 11");
		assertThat(page1).contains("entry 11");
	}

	@Test
	void builderRejectsNullSessionService() {
		assertThatIllegalArgumentException().isThrownBy(() -> CrossSessionRecallTools.builder(null, "alice"));
	}

	@Test
	void builderRejectsEmptyUserId() {
		assertThatIllegalArgumentException().isThrownBy(() -> CrossSessionRecallTools.builder(this.sessionService, ""));
	}

}
