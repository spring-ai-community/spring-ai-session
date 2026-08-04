package org.springframework.ai.session.mongodb;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public class MongoDbSessionRepository implements SessionRepository {

    private final MongoTemplate mongoTemplate;

    private MongoDbSessionRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public Session save(Session session) {
        return this.mongoTemplate.insert(MongoAISession.from(session)).toSession();
    }

    @Override
    public Optional<Session> findById(String sessionId) {
        MongoAISession mongoSession = this.mongoTemplate.findOne(Query.query(Criteria.where("sessionId").is(sessionId)), MongoAISession.class);
        if (mongoSession != null) {
            return Optional.of(mongoSession.toSession());
        }
        return Optional.empty();
    }

    @Override
    public List<Session> findByUserId(String userId) {
        List<MongoAISession> mongoAISessions = this.mongoTemplate.find(Query.query(Criteria.where("userId").is(userId)), MongoAISession.class);
        return mongoAISessions.stream().map(MongoAISession::toSession).toList();
    }

    @Override
    public List<String> findExpiredSessionIds(Instant before) {
        List<MongoAISession> expiredSessions = this.mongoTemplate.find(Query.query(Criteria.where("expiresAt").lt(before)), MongoAISession.class);
        return expiredSessions.stream().map(MongoAISession::sessionId).toList();
    }

    @Override
    public void delete(String sessionId) {
        this.mongoTemplate.remove(Session.class).matching(Criteria.where("id").is(sessionId)).all();

    }

    @Override
    public void appendEvent(SessionEvent event) {
        sessionExists(event.getSessionId());

    }

    private MongoAISession sessionExists(String event) {
        MongoAISession mongoAISession = this.mongoTemplate.findOne(Query.query(Criteria.where("sessionId").is(event)), MongoAISession.class);
        if (mongoAISession == null) {
            throw new IllegalArgumentException("Session not found for id: " + event);
        }
        return mongoAISession;
    }

    @Override
    public void replaceEvents(String sessionId, List<SessionEvent> events) {

    }

    @Override
    public boolean replaceEvents(String sessionId, List<SessionEvent> events, long expectedVersion) {
        return false;
    }

    @Override
    public long getEventVersion(String sessionId) {
        MongoAISession mongoSession = this.mongoTemplate.findOne(Query.query(Criteria.where("sessionId").is(sessionId)), MongoAISession.class);
        if (mongoSession == null) {
            throw new IllegalArgumentException("Session not found for id: " + sessionId);
        }
        return mongoSession.eventVersion();
    }

    @Override
    public List<SessionEvent> findEvents(String sessionId, EventFilter filter) {
        return List.of();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        @Nullable
        private MongoTemplate mongoTemplate;

        private Builder() {
        }


        public Builder mongoTemplate(MongoTemplate mongoTemplate) {
            this.mongoTemplate = mongoTemplate;
            return this;
        }

        public MongoDbSessionRepository build() {
            if (this.mongoTemplate == null) {
                throw new IllegalStateException("MongoTemplate must be provided");
            }
            return new MongoDbSessionRepository(this.mongoTemplate);
        }
    }
}
