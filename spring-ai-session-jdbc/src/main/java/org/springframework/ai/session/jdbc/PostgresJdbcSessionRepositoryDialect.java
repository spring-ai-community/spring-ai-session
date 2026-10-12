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

/**
 * {@link JdbcSessionRepositoryDialect} for PostgreSQL.
 *
 * @author Christian Tzolov
 * @since 0.1.0
 */
public class PostgresJdbcSessionRepositoryDialect implements JdbcSessionRepositoryDialect {

	@Override
	public String getUpsertSessionSql() {
		return """
				INSERT INTO AI_SESSION (id, user_id, parent_session_id, root_session_id, status, stop_reason, archived_at,
					created_at, expires_at, metadata)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (id) DO UPDATE
					SET user_id           = EXCLUDED.user_id,
						parent_session_id = EXCLUDED.parent_session_id,
						root_session_id   = EXCLUDED.root_session_id,
						status            = EXCLUDED.status,
						stop_reason       = EXCLUDED.stop_reason,
						archived_at       = EXCLUDED.archived_at,
						expires_at        = EXCLUDED.expires_at,
						metadata          = EXCLUDED.metadata
				""";
	}

	@Override
	public String getInsertSessionIfAbsentSql() {
		// A failed INSERT would abort an enclosing PostgreSQL transaction; ignore the
		// conflict instead and report it through the update count.
		return """
				INSERT INTO AI_SESSION (id, user_id, parent_session_id, root_session_id, status, stop_reason, archived_at,
					created_at, expires_at, metadata)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (id) DO NOTHING
				""";
	}

	@Override
	public String getKeywordFilterFragment() {
		return "AND LOWER(COALESCE(e.message_content, '')) LIKE ? ESCAPE '!'";
	}

}
