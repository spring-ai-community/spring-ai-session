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

import org.jspecify.annotations.Nullable;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.Assert;

/**
 * The events a parent session records about its children: markers on the parent's own log
 * that reproduce the condensed view of a multi-agent run without copying any child
 * history.
 *
 * <p>
 * {@link SessionService#createChild} appends {@link EventType#CHILD_CREATED}. An agent
 * runtime that delegates work appends {@link EventType#MESSAGE_SENT} when it hands a task
 * to a child and {@link EventType#MESSAGE_RECEIVED} when the child answers. The payload
 * names the child and its agent; the event carries the message that crossed the
 * boundary, in the role it has on the child's own log: the task as a user message, the
 * answer as an assistant message, with whatever parts they hold. The markers are not
 * conversation — the active view and the prompt skip them — but a text search on the
 * parent finds what was exchanged.
 *
 * @author Christian Tzolov
 * @since 0.11.0
 */
public final class SessionTreeEvents {

	/** Payload key: the id of the child the event is about. */
	public static final String PAYLOAD_SESSION_ID = "sessionId";

	/** Payload key: the agent name of the child, when it has one. */
	public static final String PAYLOAD_AGENT_NAME = "agentName";

	/** Payload key: the version of the child's agent definition, when the child has one. */
	public static final String PAYLOAD_AGENT_VERSION = "agentVersion";

	/** Payload key: the model the child's agent runs, when the child names one. */
	public static final String PAYLOAD_AGENT_MODEL = "agentModel";

	private SessionTreeEvents() {
	}

	/** The marker a parent records when a child is created from it. */
	public static SessionEvent childCreated(Session child) {
		return marker(EventType.CHILD_CREATED, child, null);
	}

	/**
	 * The marker a parent records when it sends a message to a child.
	 * @param child the child the message goes to; the marker is logged on its parent
	 * @param message the message as the child receives it, normally a {@link UserMessage}
	 */
	public static SessionEvent messageSent(Session child, Message message) {
		Assert.notNull(message, "message must not be null");
		return marker(EventType.MESSAGE_SENT, child, message);
	}

	/** The marker a parent records when it sends a text task to a child. */
	public static SessionEvent messageSent(Session child, String text) {
		Assert.notNull(text, "text must not be null");
		return messageSent(child, new UserMessage(text));
	}

	/**
	 * The marker a parent records when it receives a message from a child.
	 * @param child the child the message comes from; the marker is logged on its parent
	 * @param message the message as the child produced it, normally an
	 * {@link AssistantMessage}
	 */
	public static SessionEvent messageReceived(Session child, Message message) {
		Assert.notNull(message, "message must not be null");
		return marker(EventType.MESSAGE_RECEIVED, child, message);
	}

	/** The marker a parent records when it receives a text answer from a child. */
	public static SessionEvent messageReceived(Session child, String text) {
		Assert.notNull(text, "text must not be null");
		return messageReceived(child, new AssistantMessage(text));
	}

	private static SessionEvent marker(EventType type, Session child, @Nullable Message message) {
		Assert.notNull(child, "child must not be null");
		String parentSessionId = child.parentSessionId();
		Assert.notNull(parentSessionId, () -> "Session '" + child.id() + "' is a root; a tree marker needs a child");
		SessionEvent.Builder event = SessionEvent.builder()
			.sessionId(parentSessionId)
			.type(type)
			.payload(PAYLOAD_SESSION_ID, child.id());
		String agentName = SessionAttributes.agentName(child);
		if (agentName != null) {
			event.payload(PAYLOAD_AGENT_NAME, agentName);
			String version = SessionAttributes.agentVersion(child);
			if (version != null) {
				event.payload(PAYLOAD_AGENT_VERSION, version);
			}
			String model = SessionAttributes.agentModel(child);
			if (model != null) {
				event.payload(PAYLOAD_AGENT_MODEL, model);
			}
		}
		if (message != null) {
			event.message(message);
		}
		return event.build();
	}

}
