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

package org.springaicommunity.session.redis.autoconfigure;

import java.time.Duration;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.session.redis.RedisSessionRepository;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Redis-backed {@link RedisSessionRepository}.
 *
 * @author David J. M. Karlsen
 * @since 0.9.0
 */
@ConfigurationProperties(RedisSessionRepositoryProperties.CONFIG_PREFIX)
public class RedisSessionRepositoryProperties {

	public static final String CONFIG_PREFIX = "spring.ai.session.repository.redis";

	/**
	 * Redis server host. Ignored when a RedisClient bean is already present.
	 */
	private String host = "localhost";

	/**
	 * Redis server port. Ignored when a RedisClient bean is already present.
	 */
	private int port = 6379;

	/**
	 * Redis ACL username. Ignored when a RedisClient bean is already present.
	 */
	private @Nullable String username;

	/**
	 * Redis server password. Ignored when a RedisClient bean is already present.
	 */
	private @Nullable String password;

	/**
	 * Prefix every session key is built under.
	 */
	private String keyPrefix = RedisSessionRepository.DEFAULT_KEY_PREFIX;

	/**
	 * Grace added to a session's expiresAt to form the crash-backstop deadline at which
	 * Redis expires the session's keys by itself. Must dwarf the interval at which the
	 * application sweeps expired sessions, so the backstop can never pre-empt the sweep.
	 */
	private Duration keyTtlGrace = RedisSessionRepository.DEFAULT_KEY_TTL_GRACE;

	public String getHost() {
		return this.host;
	}

	public void setHost(String host) {
		this.host = host;
	}

	public int getPort() {
		return this.port;
	}

	public void setPort(int port) {
		this.port = port;
	}

	public @Nullable String getUsername() {
		return this.username;
	}

	public void setUsername(@Nullable String username) {
		this.username = username;
	}

	public @Nullable String getPassword() {
		return this.password;
	}

	public void setPassword(@Nullable String password) {
		this.password = password;
	}

	public String getKeyPrefix() {
		return this.keyPrefix;
	}

	public void setKeyPrefix(String keyPrefix) {
		this.keyPrefix = keyPrefix;
	}

	public Duration getKeyTtlGrace() {
		return this.keyTtlGrace;
	}

	public void setKeyTtlGrace(Duration keyTtlGrace) {
		this.keyTtlGrace = keyTtlGrace;
	}

}
