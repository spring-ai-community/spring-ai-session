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

package org.springframework.ai.session.mongodb;

import org.jspecify.annotations.Nullable;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.ai.session.Session;

import java.time.Instant;
import java.util.Map;

@Document
public record MongoAISession(String sessionId, @Indexed String userId, Instant createdAt, @Nullable Instant expiresAt,
                             Map<String, Object> metadata, Long eventVersion) {

    public static MongoAISession from(Session session) {
        return new MongoAISession(session.id(), session.userId(), session.createdAt(), session.expiresAt(), session.metadata(), 0L);
    }

    public static MongoAISession from(Session session, Long eventVersion) {
        return new MongoAISession(session.id(), session.userId(), session.createdAt(), session.expiresAt(), session.metadata(), eventVersion);
    }

    public Session toSession() {
        Instant expiresAt = this.expiresAt();
        Session.Builder builder = Session.builder()
                .id(this.sessionId())
                .userId(this.userId())
                .createdAt(this.createdAt())
                .metadata(this.metadata());
        if (expiresAt != null) {
            builder.expiresAt(expiresAt);
        }
        return builder.build();
    }
}
