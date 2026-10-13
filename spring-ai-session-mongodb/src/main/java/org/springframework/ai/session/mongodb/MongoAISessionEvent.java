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
import org.springframework.ai.session.SessionEvent;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.ai.chat.messages.Message;

import java.time.Instant;
import java.util.Map;

@Document
public record MongoAISessionEvent(String sessionEventId, @Indexed String sessionId, Instant timestamp, Message message, Map<String, Object> metadata, @Nullable String branch) {
    public static MongoAISessionEvent from(SessionEvent sessionEvent) {
        return new MongoAISessionEvent(sessionEvent.getId(), sessionEvent.getSessionId(), sessionEvent.getTimestamp(), sessionEvent.getMessage(), sessionEvent.getMetadata(), sessionEvent.getBranch());
    }

    public SessionEvent toSessionEvent() {
        SessionEvent.Builder builder = SessionEvent.builder()
                .id(this.sessionEventId())
                .sessionId(this.sessionId())
                .timestamp(this.timestamp())
                .message(this.message())
                .metadata(this.metadata());
        if (this.branch() != null) {
            builder.branch(this.branch());
        }
        return builder.build();
    }
}