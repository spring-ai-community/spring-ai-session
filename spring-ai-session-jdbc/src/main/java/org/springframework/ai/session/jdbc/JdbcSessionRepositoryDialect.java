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

	/**
	 * Upsert a row in {@code AI_SESSION}. The statement must insert a new row or update
	 * an existing one. On update only {@code user_id}, {@code expires_at} and
	 * {@code metadata} are refreshed — {@code created_at} keeps its original value and
	 * {@code event_version} must <em>not</em> be modified.
	 *
	 * <p>
	 * Parameters (in order): {@code id}, {@code user_id}, {@code created_at},
	 * {@code expires_at}, {@code metadata}.
	 */
	String getUpsertSessionSql();

	/**
	 * Inserts a row into {@code AI_SESSION} only if its {@code id} does not exist yet.
	 * Same five parameters as {@link #getUpsertSessionSql()}. Must update one row on
	 * insert and either update zero rows or fail with a duplicate-key error when the id
	 * exists. The default is a plain {@code INSERT} (duplicate key error); dialects whose
	 * failed statements abort the enclosing transaction (PostgreSQL) should use a
	 * conflict-ignoring form instead.
	 */
	default String getInsertSessionIfAbsentSql() {
		return "INSERT INTO AI_SESSION (id, user_id, created_at, expires_at, metadata) VALUES (?, ?, ?, ?, ?)";
	}

	/**
	 * Case-insensitive substring filter fragment appended to the dynamic
	 * {@code findEvents} query when
	 * {@link org.springframework.ai.session.EventFilter#keyword()} is set.
	 *
	 * <p>
	 * The fragment must be a complete {@code AND ...} clause with a single {@code ?}
	 * placeholder that will be bound to {@code '%' + escaped(keyword.toLowerCase()) + '%'},
	 * where {@code !}, {@code %} and {@code _} in the keyword are escaped with {@code !}
	 * so they match literally. The fragment must therefore declare {@code ESCAPE '!'}.
	 * Example (PostgreSQL / H2):
	 * {@code AND LOWER(COALESCE(e.message_content, '')) LIKE ? ESCAPE '!'}
	 */
	String getKeywordFilterFragment();

	/**
	 * Case-insensitive substring <em>predicate</em> — no leading {@code AND}, exactly one
	 * {@code ?} placeholder — against the event's message content. Used by
	 * {@code JdbcSessionRepository} to build the multi-term
	 * {@link org.springframework.ai.session.EventFilter#keywords()} /
	 * {@link org.springframework.ai.session.EventFilter#matchMode()} filter, repeating
	 * this predicate once per term and joining with {@code AND}/{@code OR} depending on
	 * match mode (e.g. {@code AND (pred OR pred OR pred)}).
	 *
	 * <p>
	 * Defaults to the same expression as {@link #getKeywordFilterFragment()} minus the
	 * {@code AND } prefix, which is correct for every dialect shipped today (they all use
	 * identical {@code LOWER(COALESCE(...)) LIKE ?} SQL — only branch-visibility
	 * concatenation actually differs across databases). Override only if a future dialect
	 * needs different substring-match SQL.
	 */
	default String getKeywordPredicateFragment() {
		return "LOWER(COALESCE(e.message_content, '')) LIKE ? ESCAPE '!'";
	}

	/**
	 * Branch visibility filter fragment for multi-agent event isolation. The clause
	 * matches events that are visible to the given branch: root events (null branch),
	 * exact branch match, or ancestor branches (the caller is a descendant).
	 *
	 * <p>
	 * The fragment must be a complete {@code AND (...)} clause with two {@code ?}
	 * placeholders, both bound to the filter branch value. The stored branch is used as a
	 * {@code LIKE} prefix pattern, so {@code !}, {@code %} and {@code _} in it are escaped
	 * with {@code !} to match literally. The default implementation uses {@code ||} for
	 * string concatenation (PostgreSQL / H2). MySQL/MariaDB must override this with
	 * {@code CONCAT()} because {@code ||} is logical OR in those databases.
	 */
	default String getBranchFilterFragment() {
		return "AND (e.branch IS NULL OR e.branch = ? OR ? LIKE "
				+ "REPLACE(REPLACE(REPLACE(e.branch, '!', '!!'), '%', '!%'), '_', '!_') || '.%' ESCAPE '!') ";
	}

	/**
	 * Detects the dialect from the database product name reported by the
	 * {@link DataSource}'s metadata. Supports PostgreSQL, H2, MySQL and MariaDB.
	 * @throws IllegalStateException if the product name cannot be determined or the
	 * database is not supported; configure the dialect explicitly with
	 * {@link JdbcSessionRepository.Builder#dialect(JdbcSessionRepositoryDialect)} instead
	 * of relying on detection
	 */
	static JdbcSessionRepositoryDialect from(DataSource dataSource) {
		String productName;
		try {
			productName = JdbcUtils.extractDatabaseMetaData(dataSource, DatabaseMetaData::getDatabaseProductName);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Could not determine the database product name to select a "
					+ "JdbcSessionRepositoryDialect; set one explicitly via JdbcSessionRepository.builder().dialect(...)",
					ex);
		}
		if (productName == null || productName.isBlank()) {
			throw new IllegalStateException("Database product name is null or blank; set a JdbcSessionRepositoryDialect "
					+ "explicitly via JdbcSessionRepository.builder().dialect(...)");
		}
		return switch (productName) {
			case "PostgreSQL" -> new PostgresJdbcSessionRepositoryDialect();
			case "H2" -> new H2JdbcSessionRepositoryDialect();
			case "MySQL", "MariaDB" -> new MysqlJdbcSessionRepositoryDialect();
			default -> throw new IllegalStateException("No JdbcSessionRepositoryDialect for database '" + productName
					+ "'; supported: PostgreSQL, H2, MySQL, MariaDB. Implement JdbcSessionRepositoryDialect and set it "
					+ "via JdbcSessionRepository.builder().dialect(...)");
		};
	}

}
