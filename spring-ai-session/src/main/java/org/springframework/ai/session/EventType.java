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

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import org.springframework.util.Assert;

/**
 * What a {@link SessionEvent} records. An event type is its name: the well-known types are
 * constants here, and an application or an agent runtime defines its own with
 * {@link #of(String)}; every store keeps the name, and a filter by type works for any of
 * them. Only a {@link #MESSAGE} event takes part in prompt assembly and compaction, and
 * only the types that {@link #canCarryMessage() carry a message} wrap a Spring AI message.
 *
 * @author Christian Tzolov
 * @since 0.11.0
 */
public final class EventType {

	private static final Pattern NAME = Pattern.compile("[A-Z][A-Z0-9_.]*");

	private static final Map<String, EventType> TYPES = new ConcurrentHashMap<>();

	/** A chat message; the event carries a {@link org.springframework.ai.chat.messages.Message}. */
	public static final EventType MESSAGE = of("MESSAGE");

	/** The session changed status (for example running, idle with a stop reason, terminated). */
	public static final EventType STATUS = of("STATUS");

	/** A caller interrupted the running turn. */
	public static final EventType INTERRUPT = of("INTERRUPT");

	/** A caller allowed or denied a tool call that was waiting for confirmation. */
	public static final EventType TOOL_CONFIRMATION = of("TOOL_CONFIRMATION");

	/** Token usage of a model call. */
	public static final EventType USAGE = of("USAGE");

	/**
	 * A compaction boundary: the payload names, by id, the events that make up the
	 * active view from here on (see
	 * {@link org.springframework.ai.session.compaction.CompactionBoundary}).
	 */
	public static final EventType COMPACTION = of("COMPACTION");

	/** A child session (a delegated sub-agent's session) was created from this session. */
	public static final EventType CHILD_CREATED = of("CHILD_CREATED");

	/**
	 * This session sent a message to a child: the payload names the child, and the event
	 * carries the message as the child receives it (a user message).
	 */
	public static final EventType MESSAGE_SENT = of("MESSAGE_SENT");

	/**
	 * This session received a message from a child: the payload names the child, and
	 * the event carries the message as the child produced it (an assistant message).
	 */
	public static final EventType MESSAGE_RECEIVED = of("MESSAGE_RECEIVED");

	/**
	 * A question the turn parked on, or its answer: a human-in-the-loop interaction (see
	 * {@link InteractionEvents}).
	 */
	public static final EventType INTERACTION = of("INTERACTION");

	/** A model call of an agent runtime started, completed or failed. */
	public static final EventType MODEL_CALL = of("MODEL_CALL");

	/** A tool call of an agent runtime started, completed or failed. */
	public static final EventType TOOL_CALL = of("TOOL_CALL");

	/** A step (node) of an agent runtime's graph started, completed or failed. */
	public static final EventType NODE = of("NODE");

	/** A point an agent runtime can resume a run from. */
	public static final EventType CHECKPOINT = of("CHECKPOINT");

	private static final Set<EventType> MESSAGE_BEARING = Set.of(MESSAGE, MESSAGE_SENT, MESSAGE_RECEIVED);

	private final String name;

	private EventType(String name) {
		this.name = name;
	}

	/**
	 * The event type with the given name: a well-known one, or an application's own. A
	 * name is upper case, digits, underscores and dots, like {@code MY_APP.PROGRESS}.
	 * @throws IllegalArgumentException if the name is not of that form
	 */
	@JsonCreator
	public static EventType of(String name) {
		Assert.hasText(name, "name must not be null or empty");
		Assert.isTrue(NAME.matcher(name).matches(),
				() -> "Event type name '" + name + "' must match " + NAME.pattern());
		return TYPES.computeIfAbsent(name, EventType::new);
	}

	/** The name, as stores keep it and as JSON carries it. */
	@JsonValue
	public String name() {
		return this.name;
	}

	/**
	 * Returns {@code true} when an event of this kind carries a Spring AI message: a
	 * {@link #MESSAGE} event always does, and a {@link #MESSAGE_SENT} or
	 * {@link #MESSAGE_RECEIVED} marker carries the message exchanged with the child next
	 * to its payload. Only a {@code MESSAGE} event takes part in prompt assembly and
	 * compaction.
	 */
	public boolean canCarryMessage() {
		return MESSAGE_BEARING.contains(this);
	}

	@Override
	public boolean equals(Object other) {
		return this == other || (other instanceof EventType type && this.name.equals(type.name));
	}

	@Override
	public int hashCode() {
		return this.name.hashCode();
	}

	@Override
	public String toString() {
		return this.name;
	}

}
