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

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import org.springframework.util.Assert;

/**
 * Immutable metadata container for a single, continuous conversation between a user and
 * an agent. Holds only identity and lifecycle fields — the event log is stored separately
 * in {@link SessionRepository} and retrieved on demand via {@link SessionService}.
 *
 * <p>
 * Mutations return new instances; the original is never modified.
 *
 * @author Christian Tzolov
 * @since 2.0.0
 */
public final class Session {

	private final String id;

	private final String userId;

	private final Instant createdAt;

	private final Instant expiresAt;

	private final Map<String, Object> metadata;

	private Session(String id, String userId, Instant createdAt, Instant expiresAt, Map<String, Object> metadata) {
		this.id = id;
		this.userId = userId;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
		this.metadata = Map.copyOf(metadata);
	}

	/** Unique identifier for this session. */
	public String id() {
		return this.id;
	}

	/** The actor (user or agent) who owns this session. Critical for isolation. */
	public String userId() {
		return this.userId;
	}

	/** When this session was created. */
	public Instant createdAt() {
		return this.createdAt;
	}

	/**
	 * When this session expires (TTL-based lifecycle). {@code null} means no expiry.
	 */
	@Nullable public Instant expiresAt() {
		return this.expiresAt;
	}

	/** Arbitrary metadata: model info, tags, agent type, etc. */
	public Map<String, Object> metadata() {
		return this.metadata;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private String id = "";

		private String userId = "";

		private Instant createdAt = Instant.now();

		private Instant expiresAt = Instant.now().plus(Duration.ofDays(60));

		private Map<String, Object> metadata = new HashMap<>();

		public Builder id(String id) {
			this.id = id;
			return this;
		}

		public Builder userId(String userId) {
			this.userId = userId;
			return this;
		}

		public Builder createdAt(Instant createdAt) {
			this.createdAt = createdAt;
			return this;
		}

		public Builder expiresAt(Instant expiresAt) {
			this.expiresAt = expiresAt;
			return this;
		}

		public Builder metadata(Map<String, Object> metadata) {
			this.metadata = new HashMap<>(metadata);
			return this;
		}

		public Session build() {
			Assert.hasText(this.id, "id must not be null or empty");
			Assert.hasText(this.userId, "userId must not be null or empty");
			return new Session(this.id, this.userId, this.createdAt, this.expiresAt, this.metadata);
		}

	}

}
