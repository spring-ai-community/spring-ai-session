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
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.EventFilter.MatchMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the grep-like {@code keywords}/{@code matchMode}/{@code pattern} matching
 * added to {@link EventFilter}.
 */
class EventFilterKeywordsAndPatternTests {

	private static final String SESSION_ID = "test-session";

	private SessionEvent eventWithText(String text) {
		return SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage(text)).build();
	}

	// --- keywords() / matchMode() ---

	@Test
	void anyModeMatchesWhenAtLeastOneTermPresent() {
		EventFilter filter = EventFilter.keywordsSearch(List.of("actually", "instead"), MatchMode.ANY);

		assertThat(filter.matches(eventWithText("I meant instead of that"))).isTrue();
		assertThat(filter.matches(eventWithText("no relation here"))).isFalse();
	}

	@Test
	void allModeRequiresEveryTerm() {
		EventFilter filter = EventFilter.keywordsSearch(List.of("actually", "instead"), MatchMode.ALL);

		assertThat(filter.matches(eventWithText("actually, use this instead"))).isTrue();
		assertThat(filter.matches(eventWithText("actually, that's fine"))).isFalse();
	}

	@Test
	void matchModeDefaultsToAnyWhenUnset() {
		EventFilter filter = EventFilter.builder().keywords(List.of("spring", "boot")).build();

		assertThat(filter.matchMode()).isEqualTo(MatchMode.ANY);
	}

	@Test
	void keywordsMatchingIsCaseInsensitive() {
		EventFilter filter = EventFilter.keywordsSearch(List.of("SPRING"), MatchMode.ANY);

		assertThat(filter.matches(eventWithText("spring ai is great"))).isTrue();
	}

	@Test
	void keywordsReturnsFalseWhenTextIsNull() {
		EventFilter filter = EventFilter.keywordsSearch(List.of("anything"), MatchMode.ANY);

		assertThat(filter.matches(SessionEvent.builder().sessionId(SESSION_ID).message(new AssistantMessage("")).build()))
			.isFalse();
	}

	@Test
	void emptyKeywordsListIsTreatedAsNoFilter() {
		EventFilter filter = EventFilter.builder().keywords(List.of()).build();

		assertThat(filter.keywords()).isNull();
		assertThat(filter.matchMode()).isNull();
		assertThat(filter.matches(eventWithText("anything at all"))).isTrue();
	}

	@Test
	void blankTermsAreFilteredOut() {
		EventFilter filter = EventFilter.builder().keywords(List.of("spring", "  ", "")).build();

		assertThat(filter.keywords()).containsExactly("spring");
	}

	// --- pattern() ---

	@Test
	void patternMatchesRegex() {
		EventFilter filter = EventFilter.patternSearch(Pattern.compile("\\bwe decided\\b"));

		assertThat(filter.matches(eventWithText("we decided to go with option B"))).isTrue();
		assertThat(filter.matches(eventWithText("we haven't decided yet"))).isFalse();
	}

	@Test
	void patternRespectsCaseInsensitiveFlagWhenSet() {
		EventFilter filter = EventFilter.patternSearch(Pattern.compile("remember that", Pattern.CASE_INSENSITIVE));

		assertThat(filter.matches(eventWithText("Remember That the API key rotates monthly"))).isTrue();
	}

	@Test
	void patternIsCaseSensitiveByDefault() {
		EventFilter filter = EventFilter.patternSearch(Pattern.compile("Remember"));

		assertThat(filter.matches(eventWithText("remember this"))).isFalse();
		assertThat(filter.matches(eventWithText("Remember this"))).isTrue();
	}

	@Test
	void patternReturnsFalseWhenTextIsEmpty() {
		EventFilter filter = EventFilter.patternSearch(Pattern.compile(".+"));

		assertThat(filter.matches(SessionEvent.builder().sessionId(SESSION_ID).message(new AssistantMessage("")).build()))
			.isFalse();
	}

	// --- composition: keywords/keyword/pattern all AND together ---

	@Test
	void keywordAndPatternBothMustMatchWhenBothSet() {
		EventFilter filter = EventFilter.builder().keyword("decided").pattern(Pattern.compile("option [A-Z]")).build();

		assertThat(filter.matches(eventWithText("we decided on option B"))).isTrue();
		assertThat(filter.matches(eventWithText("we decided nothing"))).isFalse();
		assertThat(filter.matches(eventWithText("option B is available"))).isFalse();
	}

}
