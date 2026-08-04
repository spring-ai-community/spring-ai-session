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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;

/**
 * Performs collection and index initialization for the MongoDB Session Repository.
 *
 * <p>
 * Creates indexes on the {@code ai_sessions} and {@code ai_session_events} collections to
 * optimize the most common query patterns:
 * <ul>
 * <li>{@code ai_sessions.userId} — for {@code findByUserId}</li>
 * <li>{@code ai_sessions.expiresAt} — for {@code findExpiredSessionIds}</li>
 * <li>{@code ai_session_events.sessionId + seq} — for ordered event retrieval</li>
 * </ul>
 *
 * @author Christian Tzolov
 * @since 2.0.0
 */
class MongoSessionRepositorySchemaInitializer implements InitializingBean {

	private static final Logger logger = LoggerFactory.getLogger(MongoSessionRepositorySchemaInitializer.class);

	private static final String COLLECTION_SESSIONS = "ai_sessions";

	private static final String COLLECTION_EVENTS = "ai_session_events";

	private final MongoTemplate mongoTemplate;

	MongoSessionRepositorySchemaInitializer(MongoTemplate mongoTemplate) {
		this.mongoTemplate = mongoTemplate;
	}

	@Override
	public void afterPropertiesSet() {
		IndexOperations sessionIndexOps = this.mongoTemplate.indexOps(COLLECTION_SESSIONS);
		sessionIndexOps.ensureIndex(new Index().on("userId", Sort.Direction.ASC).named("idx_userId"));
		sessionIndexOps.ensureIndex(new Index().on("expiresAt", Sort.Direction.ASC).named("idx_expiresAt"));

		IndexOperations eventIndexOps = this.mongoTemplate.indexOps(COLLECTION_EVENTS);
		eventIndexOps.ensureIndex(
				new Index().on("sessionId", Sort.Direction.ASC).on("seq", Sort.Direction.ASC).named("idx_sessionId_seq"));
		eventIndexOps.ensureIndex(new Index().on("sessionId", Sort.Direction.ASC).on("archived", Sort.Direction.ASC)
			.named("idx_sessionId_archived"));

		logger.info("Initialized MongoDB collections and indexes for Spring AI Session");
	}

}
