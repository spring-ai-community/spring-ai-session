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

package org.springaicommunity.session.mongodb.autoconfigure;

import com.mongodb.client.MongoClient;

import org.springframework.ai.session.mongodb.MongoSessionRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Auto-configuration for {@link MongoSessionRepository}.
 *
 * @author Christian Tzolov
 * @since 2.0.0
 */
@AutoConfiguration
@ConditionalOnClass({ MongoSessionRepository.class, MongoClient.class, MongoTemplate.class })
@EnableConfigurationProperties(MongoSessionRepositoryProperties.class)
public class MongoSessionRepositoryAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	MongoSessionRepository mongoSessionRepository(MongoTemplate mongoTemplate) {
		return MongoSessionRepository.builder().mongoTemplate(mongoTemplate).build();
	}

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = MongoSessionRepositoryProperties.CONFIG_PREFIX, name = "initialize-schema",
			havingValue = "true", matchIfMissing = true)
	MongoSessionRepositorySchemaInitializer mongoSessionRepositorySchemaInitializer(MongoTemplate mongoTemplate,
			MongoSessionRepositoryProperties properties) {
		return new MongoSessionRepositorySchemaInitializer(mongoTemplate);
	}

}
