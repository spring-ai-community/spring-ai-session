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

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.RedisClient;

import org.springframework.ai.session.redis.RedisSessionRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

/**
 * Auto-configuration for {@link RedisSessionRepository}.
 * <p>
 * The {@link RedisClient} is only created when the application does not already define
 * one — sharing an existing client (for instance the one auto-configured for Redis chat
 * memory) is the expected setup, and the {@code host} / {@code port} / {@code username} /
 * {@code password} properties are then ignored.
 *
 * @author David J. M. Karlsen
 * @since 0.9.0
 */
@AutoConfiguration
@ConditionalOnClass({ RedisSessionRepository.class, RedisClient.class })
@EnableConfigurationProperties(RedisSessionRepositoryProperties.class)
public class RedisSessionRepositoryAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	RedisClient sessionRedisClient(RedisSessionRepositoryProperties properties) {
		if (!StringUtils.hasText(properties.getUsername()) && !StringUtils.hasText(properties.getPassword())) {
			return RedisClient.builder().hostAndPort(properties.getHost(), properties.getPort()).build();
		}
		DefaultJedisClientConfig.Builder configBuilder = DefaultJedisClientConfig.builder();
		if (StringUtils.hasText(properties.getUsername())) {
			configBuilder.user(properties.getUsername());
		}
		if (StringUtils.hasText(properties.getPassword())) {
			configBuilder.password(properties.getPassword());
		}
		JedisClientConfig clientConfig = configBuilder.build();
		return RedisClient.builder()
			.hostAndPort(properties.getHost(), properties.getPort())
			.clientConfig(clientConfig)
			.build();
	}

	@Bean
	@ConditionalOnMissingBean
	RedisSessionRepository redisSessionRepository(RedisClient redisClient,
			RedisSessionRepositoryProperties properties) {
		return RedisSessionRepository.builder()
			.jedisClient(redisClient)
			.keyPrefix(properties.getKeyPrefix())
			.keyTtlGrace(properties.getKeyTtlGrace())
			.build();
	}

}
