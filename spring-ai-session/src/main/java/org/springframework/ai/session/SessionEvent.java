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

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.util.Assert;

/**
 * An atomic unit in the session's conversation history.
 *
 * <p>
 * {@code SessionEvent} is a <em>thin wrapper</em> around the existing Spring AI
 * {@link Message} types. There is no duplication of content structures — the existing
 * {@code UserMessage}, {@code AssistantMessage}, {@code SystemMessage}, and
 * {@code ToolResponseMessage} classes carry the actual content. {@code SessionEvent} adds
 * only what {@code Message} intentionally lacks: identity, ordering, session-level
 * provenance and compaction state.
 *
 * <p>
 * The wrapped {@code Message} already encodes the event type via its {@link MessageType}:
 * <ul>
 * <li>{@code UserMessage} → user input</li>
 * <li>{@code AssistantMessage} (no tool calls) → agent response</li>
 * <li>{@code AssistantMessage} (with tool calls) → tool invocation</li>
 * <li>{@code ToolResponseMessage} → tool output</li>
 * <li>{@code UserMessage} + {@link #isSynthetic()} → synthetic shadow prompt that opens a
 * summary turn</li>
 * <li>{@code AssistantMessage} + {@link #isSynthetic()} → compaction summary text that
 * closes a summary turn</li>
 * </ul>
 *
 * <p>
 * Events are immutable once created (append-only log semantics). Use {@link #builder()}
 * to construct instances.
 *
 * @author Christian Tzolov
 * @since 2.0.0
 */
public final class SessionEvent {

	/** Metadata key for the {@code synthetic} flag (value: {@link Boolean}). */
	public static final String METADATA_SYNTHETIC = "synthetic";

	/** Metadata key identifying which compaction strategy produced a synthetic event. */
	public static final String METADATA_COMPACTION_SOURCE = "compactionSource";

	private final String id;

	private final String sessionId;

	private final Instant timestamp;

	private final Message message;

	private final Map<String, Object> metadata;

	private final boolean archived;

	private SessionEvent(String id, String sessionId, Instant timestamp, Message message, Map<String, Object> metadata,
			boolean archived) {
		this.id = id;
		this.sessionId = sessionId;
		this.timestamp = timestamp;
		this.message = message;
		this.metadata = Map.copyOf(metadata);
		this.archived = archived;
	}

	/** Unique identity per event. {@code Message} has none. */
	public String getId() {
		return this.id;
	}

	/** Session ownership and isolation. */
	public String getSessionId() {
		return this.sessionId;
	}

	/** Chronological ordering within the session. */
	public Instant getTimestamp() {
		return this.timestamp;
	}

	/** The actual Spring AI message — no duplication of content. */
	public Message getMessage() {
		return this.message;
	}

	/**
	 * Session-level flags: {@link #METADATA_SYNTHETIC},
	 * {@link #METADATA_COMPACTION_SOURCE}, etc.
	 */
	public Map<String, Object> getMetadata() {
		return this.metadata;
	}

	/**
	 * Returns {@code true} if this event has been archived by compaction. Archived events
	 * are removed from the active context window injected into the prompt, but are
	 * retained in the event log and remain searchable via the Recall Storage tools (see
	 * {@code SessionEventTools}). They implement the MemGPT recall pattern: the full
	 * verbatim history is preserved even after older events have been summarized out of the
	 * active window.
	 * @return {@code true} if this event is archived, {@code false} otherwise
	 */
	public boolean isArchived() {
		return this.archived;
	}

	/**
	 * Returns a copy of this event marked as archived. Identity ({@link #getId()} and
	 * {@link #getSessionId()}) is preserved, so the copy is {@link #equals(Object) equal}
	 * to the original. Returns {@code this} when the event is already archived.
	 * @return an archived copy of this event
	 */
	public SessionEvent asArchived() {
		if (this.archived) {
			return this;
		}
		return new SessionEvent(this.id, this.sessionId, this.timestamp, this.message, this.metadata, true);
	}

	/**
	 * Convenience accessor — delegates to the wrapped message. No need to unwrap for
	 * common type-switch operations.
	 */
	public MessageType getMessageType() {
		return this.message.getMessageType();
	}

	/**
	 * Returns {@code true} for events generated by the framework (e.g. compaction
	 * summaries), not by a real user/agent turn.
	 */
	public boolean isSynthetic() {
		return (boolean) this.metadata.getOrDefault(METADATA_SYNTHETIC, false);
	}

	/**
	 * Returns {@code true} if this event starts a turn: a {@link MessageType#USER} event.
	 * A turn is a user message plus everything up to the next user message (assistant
	 * replies, tool calls and their results), so it is the unit no read or compaction
	 * cut may split. A synthetic shadow prompt is a USER event too, so a summary turn
	 * starts at its shadow prompt and is never separated from its summary.
	 */
	public boolean isTurnStart() {
		return getMessageType() == MessageType.USER;
	}

	/**
	 * Returns {@code true} for assistant messages that include tool invocations.
	 * Delegates to {@link AssistantMessage#hasToolCalls()} — no separate event type
	 * needed.
	 */
	public boolean hasToolCalls() {
		return this.message instanceof AssistantMessage am && am.hasToolCalls();
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null || getClass() != obj.getClass()) {
			return false;
		}
		SessionEvent other = (SessionEvent) obj;
		return this.id.equals(other.id) && this.sessionId.equals(other.sessionId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.id, this.sessionId);
	}

	/** Returns a new {@link Builder} for constructing a {@code SessionEvent}. */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for {@link SessionEvent}.
	 *
	 * <p>
	 * Defaults: {@code id} is auto-generated (random UUID), {@code timestamp} is
	 * {@link Instant#now()}, {@code metadata} is empty,
	 * {@code archived} is {@code false}. {@code sessionId} and {@code message} are required.
	 */
	public static final class Builder {

		private String id = UUID.randomUUID().toString();

		private String sessionId = "";

		private Instant timestamp = Instant.now();

		@Nullable private Message message;

		private Map<String, Object> metadata = new HashMap<>();

		private boolean archived = false;

		private Builder() {
		}

		/** Overrides the auto-generated event ID. */
		public Builder id(String id) {
			this.id = id;
			return this;
		}

		/** The session this event belongs to. Required. */
		public Builder sessionId(String sessionId) {
			this.sessionId = sessionId;
			return this;
		}

		/** Overrides the default timestamp ({@link Instant#now()}). */
		public Builder timestamp(Instant timestamp) {
			this.timestamp = timestamp;
			return this;
		}

		/** The Spring AI message wrapped by this event. Required. */
		public Builder message(Message message) {
			this.message = message;
			return this;
		}

		/** Replaces the entire metadata map. */
		public Builder metadata(Map<String, Object> metadata) {
			this.metadata = new HashMap<>(metadata);
			return this;
		}

		/** Adds a single metadata entry. */
		public Builder metadata(String key, Object value) {
			this.metadata.put(key, value);
			return this;
		}

		/**
		 * Marks the event as archived. Archived events are excluded from the active context
		 * window but retained for Recall Storage search. Defaults to {@code false}.
		 */
		public Builder archived(boolean archived) {
			this.archived = archived;
			return this;
		}

		/** Builds the {@link SessionEvent}, validating required fields. */
		public SessionEvent build() {
			Assert.hasText(this.id, "id must not be null or empty");
			Assert.hasText(this.sessionId, "sessionId must not be null or empty");
			Assert.notNull(this.timestamp, "timestamp must not be null");
			Assert.notNull(this.message, "message must not be null");
			return new SessionEvent(this.id, this.sessionId, this.timestamp,
					Objects.requireNonNull(this.message, "message must not be null"), this.metadata,
					this.archived);
		}

	}

}
