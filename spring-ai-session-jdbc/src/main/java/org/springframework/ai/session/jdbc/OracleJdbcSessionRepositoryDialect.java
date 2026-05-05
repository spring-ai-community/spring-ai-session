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

import java.util.List;

/**
 * {@link JdbcSessionRepositoryDialect} for Oracle.
 *
 * @author Christian Tzolov
 * @since 2.0.0
 */
public class OracleJdbcSessionRepositoryDialect implements JdbcSessionRepositoryDialect {

	/**
	 * Oracle upsert for {@code AI_SESSION} using {@code MERGE}.
	 *
	 * <p>
	 * A single-row source is projected from {@code dual} and matched on {@code id}. If a
	 * row already exists, only mutable session fields are updated
	 * ({@code user_id/created_at/expires_at/metadata}). If no row exists, a new one is
	 * inserted. The {@code event_version} column is intentionally not touched here so
	 * event-version lifecycle remains owned by event mutations.
	 *
	 * @return Oracle {@code MERGE} statement compatible with
	 * {@link org.springframework.jdbc.core.JdbcTemplate}
	 */
	@Override
	public String getUpsertSessionSql() {
		return "MERGE INTO AI_SESSION target "
				+ "USING (SELECT ? AS id, ? AS user_id, ? AS created_at, ? AS expires_at, ? AS metadata FROM dual) source "
				+ "ON (target.id = source.id) "
				+ "WHEN MATCHED THEN "
				+ "UPDATE SET "
				+ "target.user_id = source.user_id, "
				+ "target.created_at = source.created_at, "
				+ "target.expires_at = source.expires_at, "
				+ "target.metadata = source.metadata "
				+ "WHEN NOT MATCHED THEN "
				+ "INSERT (id, user_id, created_at, expires_at, metadata) "
				+ "VALUES (source.id, source.user_id, source.created_at, source.expires_at, source.metadata)";
	}

	/**
	 * Case-insensitive keyword predicate appended to the event-search query.
	 *
	 * <p>
	 * {@code COALESCE} protects against {@code NULL} message content; caller binds one
	 * parameter in the form {@code %keyword%}.
	 *
	 * @return SQL fragment beginning with {@code AND}
	 */
	@Override
	public String getKeywordFilterFragment() {
		return "AND LOWER(COALESCE(e.message_content, '')) LIKE ?";
	}

	/**
	 * SQL clause for retrieving the most recent {@code N} events.
	 *
	 * <p>
	 * Events are ordered descending by timestamp so callers can reverse them back to
	 * ascending order when needed.
	 */
	@Override
	public String getLastNClause() {
		return "ORDER BY e.timestamp DESC FETCH FIRST ? ROWS ONLY ";
	}

	/**
	 * SQL clause for page-based event retrieval in ascending timestamp order.
	 *
	 * <p>
	 * Expects two bound parameters: row offset, then page size.
	 */
	@Override
	public String getPagedClause() {
		return "ORDER BY e.timestamp ASC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY ";
	}

	@Override
	public void addPagingParameters(List<Object> params, int page, int pageSize) {
		params.add((long) page * pageSize);
		params.add(pageSize);
	}

}
