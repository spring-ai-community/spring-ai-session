/*
 * Copyright 2025-2026 the original author or authors.
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
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for how {@link EventFilter} treats events that are not messages.
 *
 * @author Christian Tzolov
 */
class EventFilterEventTypesTests {

	private final SessionEvent user = SessionEvent.builder().sessionId("s").message(new UserMessage("hello")).build();

	private final SessionEvent status = SessionEvent.builder()
		.sessionId("s")
		.type(EventType.STATUS)
		.payload("status", "hello")
		.build();

	private final SessionEvent usage = SessionEvent.builder()
		.sessionId("s")
		.type(EventType.USAGE)
		.payload("promptTokens", 12)
		.build();

	private final SessionEvent assistant = SessionEvent.builder()
		.sessionId("s")
		.message(new AssistantMessage("hi"))
		.build();

	private final List<SessionEvent> log = List.of(this.user, this.status, this.usage, this.assistant);

	@Test
	void allIncludesEveryType() {
		assertThat(EventFilter.all().apply(this.log)).containsExactlyElementsOf(this.log);
	}

	@Test
	void activeKeepsOnlyUnarchivedMessageEvents() {
		List<SessionEvent> events = List.of(this.user, this.status, this.usage, this.assistant.asArchived());

		assertThat(EventFilter.active().apply(events)).containsExactly(this.user);
		assertThat(EventFilter.active().eventTypes()).containsExactly(EventType.MESSAGE);
	}

	@Test
	void eventTypesSelectsTheGivenTypes() {
		EventFilter filter = EventFilter.builder().eventTypes(Set.of(EventType.STATUS, EventType.USAGE)).build();

		assertThat(filter.apply(this.log)).containsExactly(this.status, this.usage);
	}

	@Test
	void anEmptyEventTypesSetMeansNoRestriction() {
		assertThat(EventFilter.builder().eventTypes(Set.of()).build().eventTypes()).isNull();
	}

	@Test
	void messageTypesNeverMatchesANonMessageEvent() {
		EventFilter filter = EventFilter.builder().messageTypes(Set.of(MessageType.USER)).build();

		assertThat(filter.apply(this.log)).containsExactly(this.user);
	}

	@Test
	void textCriteriaNeverMatchANonMessageEvent() {
		// "hello" appears in the STATUS payload but a payload is not message text
		assertThat(EventFilter.builder().keyword("hello").build().apply(this.log)).containsExactly(this.user);
		assertThat(EventFilter.builder().keywords(List.of("hello")).build().apply(this.log))
			.containsExactly(this.user);
		assertThat(EventFilter.builder().pattern(Pattern.compile("hel+o")).build().apply(this.log))
			.containsExactly(this.user);
	}

	@Test
	void mergeLetsTheOtherFilterOverrideEventTypes() {
		EventFilter base = EventFilter.active();
		EventFilter other = EventFilter.builder().eventTypes(Set.of(EventType.USAGE)).build();

		assertThat(base.merge(other).eventTypes()).containsExactly(EventType.USAGE);
		assertThat(base.merge(EventFilter.all()).eventTypes()).containsExactly(EventType.MESSAGE);
		assertThat(base.merge(other).excludeArchived()).isTrue();
	}

	@Test
	void withoutWindowKeepsEventTypes() {
		EventFilter filter = EventFilter.builder().eventTypes(Set.of(EventType.STATUS)).lastN(3).build();

		assertThat(filter.withoutWindow().eventTypes()).containsExactly(EventType.STATUS);
		assertThat(filter.withoutWindow().lastN()).isNull();
	}

	@Test
	void aTurnAwareWindowCountsNonMessageEventsButExtendsToAUserMessage() {
		SessionEvent user2 = SessionEvent.builder().sessionId("s").message(new UserMessage("second")).build();
		List<SessionEvent> events = List.of(this.user, this.assistant, this.status, user2, this.usage,
				this.assistant);

		assertThat(EventFilter.lastN(1).apply(events)).containsExactly(user2, this.usage, this.assistant);
	}

}
