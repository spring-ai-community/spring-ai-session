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

import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.compaction.RecursiveSummarizationCompactionStrategy;
import org.springframework.ai.session.compaction.SlidingWindowCompactionStrategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Compaction never reorders the event log: archived events are flagged in place, a kept
 * system message stays where it was stored, and a summary turn is inserted right before
 * the kept conversation.
 */
class CompactionLogOrderTests {

	private final InMemorySessionRepository repository = InMemorySessionRepository.builder().build();

	private final SessionService service = DefaultSessionService.builder()
		.sessionRepository(this.repository)
		.allowSystemMessages(true)
		.build();

	@Test
	void repeatedRecursiveSummarizationNeverReordersTheLog() {
		ChatClient chatClient = mock(ChatClient.class, Answers.RETURNS_DEEP_STUBS);
		given(chatClient.prompt().system(anyString()).user(anyString()).call().content()).willReturn("summary 1",
				"summary 2", "summary 3");
		RecursiveSummarizationCompactionStrategy strategy = RecursiveSummarizationCompactionStrategy
			.builder(chatClient)
			.maxEventsToKeep(4)
			.build();
		String id = this.service.create(CreateSessionRequest.builder().userId("user-order").build()).id();

		// Pass 1: the system message stored inside turn 1 stays in place, active
		append(id, user("u1"), system("sys-1"), assistant("a1"), user("u2"), assistant("a2"), user("u3"),
				assistant("a3"), user("u4"), assistant("a4"), user("u5"), assistant("a5"));
		this.service.compact(id, request -> true, strategy);

		assertThat(labels(this.service.getEvents(id))).containsExactly("u1", "sys-1", "a1", "u2", "a2", "u3", "a3",
				"Σ?", "Σ:summary 1", "u4", "a4", "u5", "a5");
		assertThat(labels(this.service.getEvents(id, EventFilter.active()))).containsExactly("sys-1", "Σ?",
				"Σ:summary 1", "u4", "a4", "u5", "a5");

		// Pass 2: the previous summary is replaced, the new one precedes the kept window
		append(id, user("u6"), assistant("a6"), user("u7"), assistant("a7"));
		this.service.compact(id, request -> true, strategy);

		assertThat(labels(this.service.getEvents(id))).containsExactly("u1", "sys-1", "a1", "u2", "a2", "u3", "a3",
				"u4", "a4", "u5", "a5", "Σ?", "Σ:summary 2", "u6", "a6", "u7", "a7");
		assertThat(labels(this.service.getEvents(id, EventFilter.active()))).containsExactly("sys-1", "Σ?",
				"Σ:summary 2", "u6", "a6", "u7", "a7");

		// Pass 3: superseded system messages are archived in place, including one inside
		// the kept window, after the inserted summary
		append(id, user("u8"), system("sys-2"), assistant("a8"), user("u9"), system("sys-3"), assistant("a9"));
		this.service.compact(id, request -> true, strategy);

		List<SessionEvent> log = this.service.getEvents(id);
		assertThat(labels(log)).containsExactly("u1", "sys-1", "a1", "u2", "a2", "u3", "a3", "u4", "a4", "u5", "a5",
				"u6", "a6", "u7", "a7", "Σ?", "Σ:summary 3", "u8", "sys-2", "a8", "u9", "sys-3", "a9");
		assertThat(labels(log.stream().filter(SessionEvent::isArchived).toList())).containsExactly("u1", "sys-1",
				"a1", "u2", "a2", "u3", "a3", "u4", "a4", "u5", "a5", "u6", "a6", "u7", "a7", "sys-2");
		assertThat(labels(this.service.getEvents(id, EventFilter.active()))).containsExactly("Σ?", "Σ:summary 3",
				"u8", "a8", "u9", "sys-3", "a9");
	}

	@Test
	void slidingWindowCompactionKeepsAMidConversationSystemMessageInPlace() {
		String id = this.service.create(CreateSessionRequest.builder().userId("user-order").build()).id();
		append(id, user("u1"), assistant("a1"), user("u2"), system("sys-1"), assistant("a2"), user("u3"),
				assistant("a3"));

		this.service.compact(id, request -> true, SlidingWindowCompactionStrategy.builder().maxEvents(3).build());

		assertThat(labels(this.service.getEvents(id))).containsExactly("u1", "a1", "u2", "sys-1", "a2", "u3", "a3");
		assertThat(labels(this.service.getEvents(id, EventFilter.active()))).containsExactly("sys-1", "u3", "a3");
	}

	@Test
	void newEventsWithoutAFollowingRetainedEventAreAppended() {
		String id = this.service.create(CreateSessionRequest.builder().userId("user-order").build()).id();
		append(id, user("u1"), assistant("a1"));
		List<SessionEvent> log = this.repository.findEvents(id, EventFilter.all());
		SessionEvent added = SessionEvent.builder().sessionId(id).message(new UserMessage("added")).build();

		this.repository.compactEvents(id, List.of(log.get(0)), List.of(log.get(1), added),
				this.repository.getEventVersion(id));

		assertThat(labels(this.repository.findEvents(id, EventFilter.all()))).containsExactly("u1", "a1", "added");
	}

	@Test
	void retainedEventThatIsAlreadyArchivedStaysInPlace() {
		String id = this.service.create(CreateSessionRequest.builder().userId("user-order").build()).id();
		append(id, user("e1"), user("e2"), user("e3"));
		List<SessionEvent> log = this.repository.findEvents(id, EventFilter.all());
		this.repository.compactEvents(id, List.of(log.get(0)), List.of(log.get(1), log.get(2)),
				this.repository.getEventVersion(id));
		SessionEvent summary = SessionEvent.builder().sessionId(id).message(new UserMessage("summary")).build();

		this.repository.compactEvents(id, List.of(log.get(1)), List.of(log.get(0), summary, log.get(2)),
				this.repository.getEventVersion(id));

		List<SessionEvent> after = this.repository.findEvents(id, EventFilter.all());
		assertThat(labels(after)).containsExactly("e1", "e2", "summary", "e3");
		assertThat(after).extracting(SessionEvent::isArchived).containsExactly(true, true, false, false);
	}

	@Test
	void compactEventsRejectsANewEventOfAnotherSession() {
		String id = this.service.create(CreateSessionRequest.builder().userId("user-order").build()).id();
		append(id, user("u1"));
		List<SessionEvent> log = this.repository.findEvents(id, EventFilter.all());
		SessionEvent foreign = SessionEvent.builder()
			.sessionId("another-session")
			.message(new UserMessage("foreign"))
			.build();

		assertThatThrownBy(() -> this.repository.compactEvents(id, List.of(), List.of(foreign, log.get(0)),
				this.repository.getEventVersion(id)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(labels(this.repository.findEvents(id, EventFilter.all()))).containsExactly("u1");
	}

	@Test
	void compactEventsRejectsAnArchivedEventThatIsNotInTheLog() {
		String id = this.service.create(CreateSessionRequest.builder().userId("user-order").build()).id();
		append(id, user("u1"));
		SessionEvent unknown = SessionEvent.builder()
			.id(UUID.randomUUID().toString())
			.sessionId(id)
			.message(new UserMessage("unknown"))
			.build();

		assertThatThrownBy(() -> this.repository.compactEvents(id, List.of(unknown), List.of(),
				this.repository.getEventVersion(id)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(labels(this.repository.findEvents(id, EventFilter.all()))).containsExactly("u1");
	}

	private void append(String sessionId, Message... messages) {
		for (Message message : messages) {
			this.service.appendMessage(sessionId, message);
		}
	}

	/**
	 * Event labels: the text, "Σ?" for a synthetic shadow prompt and "Σ:text" for a
	 * synthetic summary.
	 */
	private static List<String> labels(List<SessionEvent> events) {
		return events.stream().map(e -> {
			if (!e.isSynthetic()) {
				return e.getMessage().getText();
			}
			return (e.getMessageType() == MessageType.USER) ? "Σ?" : "Σ:" + e.getMessage().getText();
		}).toList();
	}

	private static Message user(String text) {
		return new UserMessage(text);
	}

	private static Message assistant(String text) {
		return new AssistantMessage(text);
	}

	private static Message system(String text) {
		return new SystemMessage(text);
	}

}
