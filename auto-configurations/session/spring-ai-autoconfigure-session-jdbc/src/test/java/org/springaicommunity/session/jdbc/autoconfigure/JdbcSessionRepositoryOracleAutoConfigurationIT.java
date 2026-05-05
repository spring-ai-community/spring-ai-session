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

package org.springaicommunity.session.jdbc.autoconfigure;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.jdbc.JdbcSessionRepository;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import org.testcontainers.oracle.OracleContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link JdbcSessionRepositoryAutoConfiguration} against Oracle via
 * Testcontainers Oracle container.
 *
 * @author Christian Tzolov
 */
class JdbcSessionRepositoryOracleAutoConfigurationIT {

	private static final OracleContainer ORACLE_CONTAINER = new OracleContainer("gvenzl/oracle-free:23-slim-faststart");

	static {
		ORACLE_CONTAINER.start();
	}

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(JdbcSessionRepositoryAutoConfiguration.class,
				JdbcTemplateAutoConfiguration.class, DataSourceAutoConfiguration.class))
		.withPropertyValues("spring.datasource.url=" + ORACLE_CONTAINER.getJdbcUrl(),
				"spring.datasource.username=" + ORACLE_CONTAINER.getUsername(),
				"spring.datasource.password=" + ORACLE_CONTAINER.getPassword(),
				JdbcSessionRepositoryProperties.CONFIG_PREFIX + ".initialize-schema=always");

	/**
	 * Stops the shared Oracle container when all tests are complete.
	 */
	@AfterAll
	static void stopContainer() {
		ORACLE_CONTAINER.stop();
	}

	/**
	 * Verifies that schema initialization is auto-configured by default.
	 */
	@Test
	void schemaInitializerIsCreated() {
		this.contextRunner
			.run(context -> assertThat(context).hasSingleBean(JdbcSessionRepositorySchemaInitializer.class));
	}

	/**
	 * Verifies that schema initialization can be disabled through properties.
	 */
	@Test
	void schemaInitializerNotCreatedWhenDisabled() {
		this.contextRunner
			.withPropertyValues(JdbcSessionRepositoryProperties.CONFIG_PREFIX + ".initialize-schema=never")
			.run(context -> assertThat(context).doesNotHaveBean(JdbcSessionRepositorySchemaInitializer.class));
	}

	/**
	 * Verifies that Oracle auto-configuration provides a
	 * {@link JdbcSessionRepository}.
	 */
	@Test
	void repositoryBeanIsCreated() {
		this.contextRunner.run(context -> {

			assertThat(context).hasSingleBean(JdbcSessionRepository.class);
		});
	}

	/**
	 * Verifies that sessions can be saved and loaded in Oracle.
	 */
	@Test
	void saveAndFindSession() {
		this.contextRunner.run(context -> {
			var repo = context.getBean(JdbcSessionRepository.class);
			String sessionId = UUID.randomUUID().toString();
			Session session = Session.builder().id(sessionId).userId("user-oracle").build();

			repo.save(session);

			assertThat(repo.findById(sessionId)).isPresent()
				.hasValueSatisfying(s -> assertThat(s.userId()).isEqualTo("user-oracle"));
		});
	}

	/**
	 * Verifies that ordered chat events can be appended and retrieved from Oracle.
	 */
	@Test
	void appendAndFindEvents() {
		this.contextRunner.run(context -> {
			var repo = context.getBean(JdbcSessionRepository.class);
			String sessionId = UUID.randomUUID().toString();
			repo.save(Session.builder().id(sessionId).userId("user-oracle-events").build());

			SessionEvent event1 = SessionEvent.builder()
				.id(UUID.randomUUID().toString())
				.sessionId(sessionId)
				.timestamp(Instant.ofEpochSecond(1_700_000_000L))
				.message(new UserMessage("Hello from Oracle"))
				.build();
			SessionEvent event2 = SessionEvent.builder()
				.id(UUID.randomUUID().toString())
				.sessionId(sessionId)
				.timestamp(Instant.ofEpochSecond(1_700_000_001L))
				.message(new UserMessage("Second message"))
				.build();

			repo.appendEvent(event1);
			repo.appendEvent(event2);

			List<SessionEvent> events = repo.findEvents(sessionId, EventFilter.builder().build());
			assertThat(events).hasSize(2);
			assertThat(events.get(0).getMessage().getText()).isEqualTo("Hello from Oracle");
			assertThat(events.get(1).getMessage().getText()).isEqualTo("Second message");
		});
	}

	/**
	 * Verifies that deleting a session removes associated state from Oracle.
	 */
	@Test
	void deleteSessionCascadesToEvents() {
		this.contextRunner.run(context -> {
			var repo = context.getBean(JdbcSessionRepository.class);
			String sessionId = UUID.randomUUID().toString();
			repo.save(Session.builder().id(sessionId).userId("user-oracle-delete").build());

			repo.appendEvent(SessionEvent.builder()
				.id(UUID.randomUUID().toString())
				.sessionId(sessionId)
				.timestamp(Instant.now())
				.message(new UserMessage("to be deleted"))
				.build());

			repo.delete(sessionId);

			assertThat(repo.findById(sessionId)).isEmpty();
		});
	}

	/**
	 * Verifies that sessions can be queried by user identifier in Oracle.
	 */
	@Test
	void findByUserId() {
		this.contextRunner.run(context -> {
			var repo = context.getBean(JdbcSessionRepository.class);
			String userId = "user-oracle-multi-" + UUID.randomUUID();
			repo.save(Session.builder().id(UUID.randomUUID().toString()).userId(userId).build());
			repo.save(Session.builder().id(UUID.randomUUID().toString()).userId(userId).build());

			assertThat(repo.findByUserId(userId)).hasSize(2);
		});
	}

}
