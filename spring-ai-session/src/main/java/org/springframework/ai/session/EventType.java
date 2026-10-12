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

/**
 * The kind of a {@link SessionEvent}: what the event records about the session.
 * <p>
 * A {@link #MESSAGE} event wraps a Spring AI {@link org.springframework.ai.chat.messages.Message}
 * and is the only kind that takes part in prompt assembly and compaction. Every other kind
 * records runtime state in the same log — a status change, an interrupt, a tool-call
 * confirmation, token usage, a compaction, or the lifecycle of a delegated thread — and
 * carries its data in {@link SessionEvent#getPayload()}.
 * <p>
 * The set mirrors the event families of an agent runtime's event stream, so that a session
 * log can serve as the durable record of a whole run, not only of its chat history.
 *
 * @author Christian Tzolov
 * @since 0.11.0
 */
public enum EventType {

	/** A chat message; the event carries a {@link org.springframework.ai.chat.messages.Message}. */
	MESSAGE,

	/** The session changed status (for example running, idle with a stop reason, terminated). */
	STATUS,

	/** A caller interrupted the running turn. */
	INTERRUPT,

	/** A caller allowed or denied a tool call that was waiting for confirmation. */
	TOOL_CONFIRMATION,

	/** Token usage of a model call or a cumulative usage snapshot. */
	USAGE,

	/** The log was compacted; the payload describes what was archived and inserted. */
	COMPACTION,

	/** A child session (a delegated thread) was created from this session. */
	THREAD_CREATED,

	/** This session sent a message to a child session. */
	MESSAGE_SENT,

	/** This session received a message from a child session. */
	MESSAGE_RECEIVED

}
