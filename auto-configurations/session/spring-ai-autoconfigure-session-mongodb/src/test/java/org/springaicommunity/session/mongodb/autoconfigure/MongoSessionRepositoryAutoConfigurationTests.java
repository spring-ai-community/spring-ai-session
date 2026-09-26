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

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.mongodb.MongoSessionRepository;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springaicommunity.session.autoconfigure.SessionServiceAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link MongoSessionRepositoryAutoConfiguration}.
 *
 * @author Christian Tzolov
 */
@Testcontainers
class MongoSessionRepositoryAutoConfigurationTests {

	@Container
	static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withBean(MongoTemplate.class,
				() -> new MongoTemplate(new SimpleMongoClientDatabaseFactory(mongo.getConnectionString())))
		.withConfiguration(AutoConfigurations.of(MongoSessionRepositoryAutoConfiguration.class,
				SessionServiceAutoConfiguration.class));

	@Test
	void mongoSessionRepositoryBeanIsCreated() {
		this.contextRunner.run(context -> assertThat(context).hasSingleBean(MongoSessionRepository.class));
	}

	@Test
	void sessionServiceBeanIsCreated() {
		this.contextRunner.run(context -> assertThat(context).hasSingleBean(SessionService.class));
	}

	@Test
	void schemaInitializerBeanIsCreated() {
		this.contextRunner
			.run(context -> assertThat(context).hasSingleBean(MongoSessionRepositorySchemaInitializer.class));
	}

	@Test
	void schemaInitializerNotCreatedWhenDisabled() {
		this.contextRunner
			.withPropertyValues(MongoSessionRepositoryProperties.CONFIG_PREFIX + ".initialize-schema=false")
			.run(context -> assertThat(context).doesNotHaveBean(MongoSessionRepositorySchemaInitializer.class));
	}

	@Test
	void customRepositoryBeanIsRespected() {
		this.contextRunner
			.withBean(MongoSessionRepository.class,
					() -> MongoSessionRepository.builder()
						.mongoTemplate(new MongoTemplate(
								new SimpleMongoClientDatabaseFactory(mongo.getConnectionString())))
						.build())
			.run(context -> assertThat(context).hasSingleBean(MongoSessionRepository.class));
	}

	@Test
	void defaultProperties() {
		var props = new MongoSessionRepositoryProperties();
		assertThat(props.isInitializeSchema()).isTrue();
	}

}
