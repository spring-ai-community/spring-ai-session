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
import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import org.springframework.util.Assert;

/**
 * Parameters for creating a new {@link Session}.
 *
 * @author Christian Tzolov
 * @since 2.0.0
 */
public final class CreateSessionRequest {

	@Nullable private final String id;

	private final String userId;

	@Nullable private final Duration timeToLive;

	private final Map<String, Object> metadata;

	private CreateSessionRequest(Builder builder) {
		Assert.hasText(builder.userId, "userId must not be null or empty");
		this.id = builder.id;
		this.userId = builder.userId;
		this.timeToLive = builder.timeToLive;
		this.metadata = Map.copyOf(builder.metadata);
	}

	/**
	 * Returns the requested session ID, or {@code null} if the service should generate
	 * one.
	 */
	@Nullable public String id() {
		return this.id;
	}

	public String userId() {
		return this.userId;
	}

	@Nullable public Duration timeToLive() {
		return this.timeToLive;
	}

	public Map<String, Object> metadata() {
		return this.metadata;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		@Nullable private String id;

		private String userId = "";

		@Nullable private Duration timeToLive;

		private Map<String, Object> metadata = new HashMap<>();

		/**
		 * Sets an explicit session ID. If omitted, the service generates a UUID.
		 */
		public Builder id(@Nullable String id) {
			this.id = id;
			return this;
		}

		public Builder userId(String userId) {
			this.userId = userId;
			return this;
		}

		public Builder timeToLive(@Nullable Duration timeToLive) {
			this.timeToLive = timeToLive;
			return this;
		}

		public Builder metadata(Map<String, Object> metadata) {
			this.metadata.putAll(metadata);
			return this;
		}

		public Builder metadata(String key, Object value) {
			this.metadata.put(key, value);
			return this;
		}

		public CreateSessionRequest build() {
			return new CreateSessionRequest(this);
		}

	}

}
