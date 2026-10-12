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
import java.util.Set;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Tests for the {@link SessionTreeEvents} markers.
 *
 * @author Christian Tzolov
 */
class SessionTreeEventsTests {

	private final SessionService service = DefaultSessionService.builder()
		.sessionRepository(InMemorySessionRepository.builder().build())
		.build();

	@Test
	void createdNamesTheChildAndItsAgentWhenItHasOne() {
		Session root = this.service.create(CreateSessionRequest.builder().build());
		Session named = this.service.createChild(root.id(), "researcher");
		Session unnamed = this.service.createChild(root.id(), CreateSessionRequest.builder().build());

		SessionEvent withAgent = SessionTreeEvents.childCreated(named);
		SessionEvent withoutAgent = SessionTreeEvents.childCreated(unnamed);

		assertThat(withAgent.getType()).isEqualTo(EventType.CHILD_CREATED);
		assertThat(withAgent.hasMessage()).isFalse();
		assertThat(withAgent.getPayload()).containsEntry(SessionTreeEvents.PAYLOAD_SESSION_ID, named.id())
			.containsEntry(SessionTreeEvents.PAYLOAD_AGENT_NAME, "researcher");
		assertThat(withoutAgent.getPayload()).containsEntry(SessionTreeEvents.PAYLOAD_SESSION_ID, unnamed.id())
			.doesNotContainKey(SessionTreeEvents.PAYLOAD_AGENT_NAME);
	}

	@Test
	void messageMarkersCarryTheMessageInTheRoleTheChildSeesAndNameTheAgent() {
		Session root = this.service.create(CreateSessionRequest.builder().build());
		Session child = this.service.createChild(root.id(), "researcher");

		SessionEvent sent = SessionTreeEvents.messageSent(child, "do the research");
		SessionEvent received = SessionTreeEvents.messageReceived(child, "the findings");

		assertThat(sent.getType()).isEqualTo(EventType.MESSAGE_SENT);
		assertThat(sent.getSessionId()).isEqualTo(root.id());
		assertThat(sent.getPayload()).containsEntry(SessionTreeEvents.PAYLOAD_SESSION_ID, child.id())
			.containsEntry(SessionTreeEvents.PAYLOAD_AGENT_NAME, "researcher");
		assertThat(sent.getMessageType()).isEqualTo(MessageType.USER);
		assertThat(sent.getMessage().getText()).isEqualTo("do the research");
		assertThat(sent.isTurnStart()).as("a marker never starts a turn").isFalse();

		assertThat(received.getType()).isEqualTo(EventType.MESSAGE_RECEIVED);
		assertThat(received.getMessageType()).isEqualTo(MessageType.ASSISTANT);
		assertThat(received.getMessage().getText()).isEqualTo("the findings");
		assertThat(received.getPayload()).containsEntry(SessionTreeEvents.PAYLOAD_SESSION_ID, child.id());
	}

	@Test
	void messageMarkersKeepThePartsOfTheMessageThatCrossedTheBoundary() {
		Session root = this.service.create(CreateSessionRequest.builder().build());
		Session child = this.service.createChild(root.id(), "researcher");
		Media image = Media.builder()
			.mimeType(MimeTypeUtils.IMAGE_PNG)
			.data(java.net.URI.create("https://example.com/chart.png"))
			.build();
		UserMessage task = UserMessage.builder().text("describe this").media(image).build();

		this.service.appendEvent(SessionTreeEvents.messageSent(child, task));

		List<SessionEvent> markers = this.service.getEvents(root.id(),
				EventFilter.builder().eventTypes(Set.of(EventType.MESSAGE_SENT)).build());
		assertThat(markers).hasSize(1);
		UserMessage stored = (UserMessage) markers.get(0).getMessage();
		assertThat(stored.getText()).isEqualTo("describe this");
		assertThat(stored.getMedia()).hasSize(1);
	}

	@Test
	void messageMarkersRejectAMissingChildOrMessage() {
		Session root = this.service.create(CreateSessionRequest.builder().build());
		Session child = this.service.createChild(root.id(), "researcher");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> SessionTreeEvents.messageSent(null, new UserMessage("x")));
		assertThatIllegalArgumentException().as("a root is not a child")
			.isThrownBy(() -> SessionTreeEvents.messageSent(root, new UserMessage("x")));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> SessionTreeEvents.messageSent(child, (UserMessage) null));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> SessionTreeEvents.messageReceived(child, (String) null));
	}

	@Test
	void markersAreNotConversationButTheirTextIsSearchable() {
		Session root = this.service.create(CreateSessionRequest.builder().build());
		Session child = this.service.createChild(root.id(), "researcher");
		this.service.appendMessage(root.id(), new UserMessage("question"));
		this.service.appendEvent(SessionTreeEvents.messageSent(child, "task"));
		this.service.appendEvent(SessionTreeEvents.messageReceived(child, "answer"));
		this.service.appendMessage(root.id(), new AssistantMessage("the answer is: answer"));

		// The prompt and the active view hold the conversation only
		assertThat(this.service.getActiveMessages(root.id())).extracting(m -> m.getText())
			.containsExactly("question", "the answer is: answer");
		assertThat(this.service.getEvents(root.id())).extracting(SessionEvent::getType)
			.containsExactly(EventType.CHILD_CREATED, EventType.MESSAGE, EventType.MESSAGE_SENT,
					EventType.MESSAGE_RECEIVED, EventType.MESSAGE);
		// A text search on the parent finds what was exchanged with the child
		assertThat(this.service.getEvents(root.id(), EventFilter.keywordSearch("answer")))
			.extracting(SessionEvent::getType)
			.containsExactly(EventType.MESSAGE_RECEIVED, EventType.MESSAGE);
		// A window keeps turns whole: the marker carrying a user message is not a turn start
		assertThat(this.service.getEvents(root.id(), EventFilter.lastN(1))).extracting(SessionEvent::getType)
			.containsExactly(EventType.MESSAGE, EventType.MESSAGE_SENT, EventType.MESSAGE_RECEIVED,
					EventType.MESSAGE);
	}

}
