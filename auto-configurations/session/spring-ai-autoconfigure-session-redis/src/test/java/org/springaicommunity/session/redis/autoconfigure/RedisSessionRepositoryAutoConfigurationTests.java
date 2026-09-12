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

import org.junit.jupiter.api.Test;
import org.springaicommunity.session.autoconfigure.SessionServiceAutoConfiguration;
import redis.clients.jedis.RedisClient;

import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.redis.RedisSessionRepository;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link RedisSessionRepositoryAutoConfiguration}.
 *
 * <p>
 * No Redis server is needed: Jedis connects lazily, so the beans can be created and
 * inspected without one.
 *
 * @author David J. M. Karlsen
 */
class RedisSessionRepositoryAutoConfigurationTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(RedisSessionRepositoryAutoConfiguration.class,
				SessionServiceAutoConfiguration.class));

	@Test
	void redisSessionRepositoryBeanIsCreated() {
		this.contextRunner.run(context -> assertThat(context).hasSingleBean(RedisSessionRepository.class));
	}

	@Test
	void sessionServiceBeanIsCreated() {
		this.contextRunner.run(context -> assertThat(context).hasSingleBean(SessionService.class));
	}

	@Test
	void redisClientBeanIsCreated() {
		this.contextRunner.run(context -> assertThat(context).hasSingleBean(RedisClient.class));
	}

	@Test
	void existingRedisClientBeanIsReused() {
		RedisClient existing = mock(RedisClient.class);
		this.contextRunner.withBean(RedisClient.class, () -> existing)
			.run(context -> assertThat(context.getBean(RedisClient.class)).isSameAs(existing));
	}

	@Test
	void customRepositoryBeanIsRespected() {
		RedisSessionRepository custom = RedisSessionRepository.builder().jedisClient(mock(RedisClient.class)).build();
		this.contextRunner.withBean(RedisSessionRepository.class, () -> custom)
			.run(context -> assertThat(context.getBean(RedisSessionRepository.class)).isSameAs(custom));
	}

	@Test
	void defaultProperties() {
		RedisSessionRepositoryProperties properties = new RedisSessionRepositoryProperties();
		assertThat(properties.getHost()).isEqualTo("localhost");
		assertThat(properties.getPort()).isEqualTo(6379);
		assertThat(properties.getKeyPrefix()).isEqualTo(RedisSessionRepository.DEFAULT_KEY_PREFIX);
		assertThat(properties.getKeyTtlGrace()).isEqualTo(RedisSessionRepository.DEFAULT_KEY_TTL_GRACE);
	}

	@Test
	void propertiesAreBound() {
		this.contextRunner
			.withPropertyValues(RedisSessionRepositoryProperties.CONFIG_PREFIX + ".host=redis.example.com",
					RedisSessionRepositoryProperties.CONFIG_PREFIX + ".port=6380",
					RedisSessionRepositoryProperties.CONFIG_PREFIX + ".key-prefix=my-app:sessions:",
					RedisSessionRepositoryProperties.CONFIG_PREFIX + ".key-ttl-grace=7d")
			.run(context -> {
				RedisSessionRepositoryProperties properties = context.getBean(RedisSessionRepositoryProperties.class);
				assertThat(properties.getHost()).isEqualTo("redis.example.com");
				assertThat(properties.getPort()).isEqualTo(6380);
				assertThat(properties.getKeyPrefix()).isEqualTo("my-app:sessions:");
				assertThat(properties.getKeyTtlGrace()).isEqualTo(Duration.ofDays(7));
			});
	}

}
