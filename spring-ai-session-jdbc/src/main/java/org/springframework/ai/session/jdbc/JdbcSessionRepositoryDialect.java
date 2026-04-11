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

package org.springframework.ai.session.jdbc;

import java.sql.DatabaseMetaData;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jdbc.support.JdbcUtils;

/**
 * Abstraction for database-specific SQL used by {@link JdbcSessionRepository}.
 *
 * <p>
 * Each method returns a parameterised SQL string compatible with
 * {@link org.springframework.jdbc.core.JdbcTemplate}. Implementations cover only the SQL
 * surface that differs across databases (upsert syntax, boolean literals, keyword
 * search). Generic queries shared by all dialects live directly in
 * {@link JdbcSessionRepository}.
 *
 * @author Christian Tzolov
 * @since 2.0.0
 * @see PostgresJdbcSessionRepositoryDialect
 * @see H2JdbcSessionRepositoryDialect
 * @see MysqlJdbcSessionRepositoryDialect
 */
public interface JdbcSessionRepositoryDialect {

	Logger logger = LoggerFactory.getLogger(JdbcSessionRepositoryDialect.class);

	/**
	 * Upsert a row in {@code AI_SESSION}. The statement must insert a new row or update
	 * an existing one. The {@code event_version} column must <em>not</em> be modified on
	 * update — only the session-metadata columns ({@code user_id}, {@code created_at},
	 * {@code expires_at}, {@code metadata}) are refreshed.
	 *
	 * <p>
	 * Parameters (in order): {@code id}, {@code user_id}, {@code created_at},
	 * {@code expires_at}, {@code metadata}, then the same four update-only columns
	 * ({@code user_id}, {@code created_at}, {@code expires_at}, {@code metadata}).
	 */
	String getUpsertSessionSql();

	/**
	 * Case-insensitive substring filter fragment appended to the dynamic
	 * {@code findEvents} query when
	 * {@link org.springframework.ai.session.EventFilter#keyword()} is set.
	 *
	 * <p>
	 * The fragment must be a complete {@code AND ...} clause with a single {@code ?}
	 * placeholder that will be bound to {@code '%' + keyword.toLowerCase() + '%'}.
	 * Example (PostgreSQL / H2):
	 * {@code AND LOWER(COALESCE(e.message_content, '')) LIKE ?}
	 */
	String getKeywordFilterFragment();

	/**
	 * Detects the best-matching dialect for the given {@link DataSource}.
	 */
	static JdbcSessionRepositoryDialect from(DataSource dataSource) {
		String productName = null;
		try {
			productName = JdbcUtils.extractDatabaseMetaData(dataSource, DatabaseMetaData::getDatabaseProductName);
		}
		catch (Exception ex) {
			logger.warn("Could not determine database product name from DataSource; defaulting to PostgreSQL dialect",
					ex);
		}
		if (productName == null || productName.isBlank()) {
			logger.warn("Database product name is null or blank; defaulting to PostgreSQL dialect.");
			return new PostgresJdbcSessionRepositoryDialect();
		}
		return switch (productName) {
			case "PostgreSQL" -> new PostgresJdbcSessionRepositoryDialect();
			case "H2" -> new H2JdbcSessionRepositoryDialect();
			case "MySQL", "MariaDB" -> new MysqlJdbcSessionRepositoryDialect();
			default -> {
				logger.warn("No specific dialect for '{}'; defaulting to PostgreSQL dialect.", productName);
				yield new PostgresJdbcSessionRepositoryDialect();
			}
		};
	}

}
