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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link JdbcSessionRepositoryDialect} implementations, focusing on
 * SQL correctness for dialect-specific fragments.
 */
class JdbcSessionRepositoryDialectTests {

	// -------------------------------------------------------------------------
	// getBranchFilterFragment — PostgreSQL / H2 (default)
	// -------------------------------------------------------------------------

	@Test
	void postgresBranchFilterUsesDoublePipeConcat() {
		String fragment = new PostgresJdbcSessionRepositoryDialect().getBranchFilterFragment();
		assertThat(fragment).contains("||");
		assertThat(fragment).contains("e.branch IS NULL");
		assertThat(fragment).contains("e.branch = ?");
	}

	@Test
	void h2BranchFilterUsesDoublePipeConcat() {
		String fragment = new H2JdbcSessionRepositoryDialect().getBranchFilterFragment();
		assertThat(fragment).contains("||");
		assertThat(fragment).contains("e.branch IS NULL");
		assertThat(fragment).contains("e.branch = ?");
	}

	// -------------------------------------------------------------------------
	// getBranchFilterFragment — MySQL / MariaDB
	// -------------------------------------------------------------------------

	@Test
	void mysqlBranchFilterUsesConcatFunction() {
		String fragment = new MysqlJdbcSessionRepositoryDialect().getBranchFilterFragment();
		assertThat(fragment).containsIgnoringCase("CONCAT(");
		assertThat(fragment).contains("e.branch IS NULL");
		assertThat(fragment).contains("e.branch = ?");
	}

	@Test
	void mysqlBranchFilterDoesNotUseDoublePipe() {
		// || is logical OR in MySQL — using it would silently return wrong results
		String fragment = new MysqlJdbcSessionRepositoryDialect().getBranchFilterFragment();
		assertThat(fragment).doesNotContain("||");
	}

	@Test
	void mysqlBranchFilterHasTwoPlaceholders() {
		String fragment = new MysqlJdbcSessionRepositoryDialect().getBranchFilterFragment();
		long count = fragment.chars().filter(c -> c == '?').count();
		assertThat(count).as("branch filter must bind two parameters (exact match + LIKE)").isEqualTo(2);
	}

	// -------------------------------------------------------------------------
	// Default method contract — all dialects must have two placeholders
	// -------------------------------------------------------------------------

	@Test
	void allDialectsBranchFilterHaveTwoPlaceholders() {
		for (JdbcSessionRepositoryDialect dialect : new JdbcSessionRepositoryDialect[] {
				new PostgresJdbcSessionRepositoryDialect(),
				new H2JdbcSessionRepositoryDialect(),
				new MysqlJdbcSessionRepositoryDialect() }) {
			String fragment = dialect.getBranchFilterFragment();
			long count = fragment.chars().filter(c -> c == '?').count();
			assertThat(count)
				.as("dialect %s must bind exactly two parameters", dialect.getClass().getSimpleName())
				.isEqualTo(2);
		}
	}

}
