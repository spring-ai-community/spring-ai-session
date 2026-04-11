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

import javax.sql.DataSource;

import org.springframework.ai.session.jdbc.JdbcSessionRepository;
import org.springframework.ai.session.jdbc.JdbcSessionRepositoryDialect;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.autoconfigure.init.OnDatabaseInitializationCondition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Auto-configuration for {@link JdbcSessionRepository}.
 *
 * @author Christian Tzolov
 * @since 2.0.0
 */
@AutoConfiguration
@ConditionalOnClass({ JdbcSessionRepository.class, DataSource.class, JdbcTemplate.class })
@EnableConfigurationProperties(JdbcSessionRepositoryProperties.class)
public class JdbcSessionRepositoryAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	JdbcSessionRepository jdbcSessionRepository(DataSource dataSource) {
		JdbcSessionRepositoryDialect dialect = JdbcSessionRepositoryDialect.from(dataSource);
		return JdbcSessionRepository.builder().dataSource(dataSource).dialect(dialect).build();
	}

	@Bean
	@ConditionalOnMissingBean
	@Conditional(OnJdbcSessionRepositoryDatasourceInitializationCondition.class)
	JdbcSessionRepositorySchemaInitializer jdbcSessionRepositorySchemaInitializer(DataSource dataSource,
			JdbcSessionRepositoryProperties properties) {
		return new JdbcSessionRepositorySchemaInitializer(dataSource, properties);
	}

	static class OnJdbcSessionRepositoryDatasourceInitializationCondition extends OnDatabaseInitializationCondition {

		OnJdbcSessionRepositoryDatasourceInitializationCondition() {
			super("Jdbc Session Repository", JdbcSessionRepositoryProperties.CONFIG_PREFIX + ".initialize-schema");
		}

	}

}
