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
 * an agent. Holds only identity, lifecycle and the links of its session tree
 * ({@link #parentSessionId()}, {@link #rootSessionId()}) — the event log is stored
 * separately in {@link SessionRepository} and retrieved on demand via
 * {@link SessionService}.
 *
 * <p>
 * A session has no owner field. Who it belongs to is an attribute in its
 * {@link #metadata()}, under a well-known key such as
 * {@link SessionAttributes#PRINCIPAL_NAME}, put there by the application or a
 * {@link SessionScopeResolver} and looked up through {@link SessionRepository#findByIndex}.
 *
 * <p>
 * Mutations return new instances; the original is never modified.
 *
 * @author Christian Tzolov
 * @since 2.0.0
 */
public final class Session {

	private final String id;

	private final Instant createdAt;

	@Nullable private final Instant expiresAt;

	@Nullable private final String parentSessionId;

	private final String rootSessionId;

	private final Map<String, Object> metadata;

	private Session(String id, Instant createdAt, @Nullable Instant expiresAt, @Nullable String parentSessionId,
			String rootSessionId, Map<String, Object> metadata) {
		this.id = id;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
		this.parentSessionId = parentSessionId;
		this.rootSessionId = rootSessionId;
		this.metadata = Map.copyOf(metadata);
	}

	/** Unique identifier for this session. */
	public String id() {
		return this.id;
	}

	/** When this session was created. */
	public Instant createdAt() {
		return this.createdAt;
	}

	/**
	 * When this session expires (TTL-based lifecycle). {@code null} means no expiry.
	 * Sessions created through {@link SessionService#create(CreateSessionRequest)} always
	 * have an expiry (the request's or the service's default time-to-live); {@code null}
	 * only occurs for sessions built directly with {@code expiresAt(null)}.
	 */
	@Nullable public Instant expiresAt() {
		return this.expiresAt;
	}

	/**
	 * The id of the session this child was created from, or {@code null} for a root
	 * session. Set by {@link SessionService#createChild}; see
	 * {@link SessionService#findChildren}.
	 */
	public @Nullable String parentSessionId() {
		return this.parentSessionId;
	}

	/**
	 * The root of this session's tree: the session's own id for a root, the tree's root
	 * for a child. Never {@code null}, so every member of a tree shares it; see
	 * {@link SessionService#findTree}.
	 */
	public String rootSessionId() {
		return this.rootSessionId;
	}

	/** Whether this session is a root: it has no parent. */
	public boolean isRoot() {
		return this.parentSessionId == null;
	}

	/** Whether this session is a child: it was created from a parent session. */
	public boolean isChild() {
		return this.parentSessionId != null;
	}

	/**
	 * Arbitrary metadata: the scope attributes ({@link SessionAttributes}), model info,
	 * tags, agent type, etc.
	 */
	public Map<String, Object> metadata() {
		return this.metadata;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private String id = "";

		private Instant createdAt = Instant.now();

		@Nullable private Instant expiresAt = Instant.now().plus(Duration.ofDays(60));

		@Nullable private String parentSessionId;

		@Nullable private String rootSessionId;

		private Map<String, Object> metadata = new HashMap<>();

		public Builder id(String id) {
			this.id = id;
			return this;
		}

		public Builder createdAt(Instant createdAt) {
			this.createdAt = createdAt;
			return this;
		}

		/**
		 * Sets the expiry. Defaults to 60 days from now; pass {@code null} for a session
		 * that never expires.
		 */
		public Builder expiresAt(@Nullable Instant expiresAt) {
			this.expiresAt = expiresAt;
			return this;
		}

		/**
		 * Makes the session a child of the given parent; {@code null} (the default) for a
		 * root. A child must also name its {@link #rootSessionId(String) root}. Normally
		 * set by {@link SessionService#createChild}, which also enforces the tree's rules.
		 */
		public Builder parentSessionId(@Nullable String parentSessionId) {
			this.parentSessionId = parentSessionId;
			return this;
		}

		/**
		 * The root of the session's tree. Required for a child; for a root it defaults to
		 * the session's own id and may not be anything else.
		 */
		public Builder rootSessionId(@Nullable String rootSessionId) {
			this.rootSessionId = rootSessionId;
			return this;
		}

		public Builder metadata(Map<String, Object> metadata) {
			this.metadata = new HashMap<>(metadata);
			return this;
		}

		/** Adds one metadata entry. */
		public Builder metadata(String key, Object value) {
			this.metadata.put(key, value);
			return this;
		}

		public Session build() {
			Assert.hasText(this.id, "id must not be null or empty");
			if (this.parentSessionId == null) {
				Assert.isTrue(this.rootSessionId == null || this.rootSessionId.equals(this.id),
						"a root session's rootSessionId must be its own id");
				return new Session(this.id, this.createdAt, this.expiresAt, null, this.id, this.metadata);
			}
			Assert.isTrue(!this.parentSessionId.equals(this.id), "a session cannot be its own parent");
			Assert.hasText(this.rootSessionId, "a child must name the root of its tree");
			Assert.isTrue(!this.rootSessionId.equals(this.id), "a child cannot be its own root");
			return new Session(this.id, this.createdAt, this.expiresAt, this.parentSessionId, this.rootSessionId,
					this.metadata);
		}

	}

}
