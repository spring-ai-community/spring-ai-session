package org.springframework.ai.session.mongodb;

import org.junit.jupiter.api.Test;
import org.springframework.ai.session.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration;
import org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ContextConfiguration(classes = MongoDbSessionRepositoryTest.TestConfig.class)
@Testcontainers
class MongoDbSessionRepositoryTest {

    @Autowired
    private MongoDbSessionRepository sessionRepository;

    @Container
    @ServiceConnection
    static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7");

    @Test
    void saveAndFindByIdRoundTrip() {
        Session session = buildSession("user-1");
        this.sessionRepository.save(session);

        Optional<Session> found = this.sessionRepository.findById(session.id());
        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(session.id());
        assertThat(found.get().userId()).isEqualTo("user-1");
    }


    private Session buildSession(String userId) {
        return Session.builder().id(UUID.randomUUID().toString()).userId(userId).build();
    }

    @Import({MongoAutoConfiguration.class, DataMongoAutoConfiguration.class})
    static class TestConfig {
        @Bean
        MongoDbSessionRepository sessionRepository(MongoTemplate mongoTemplate) {
            return MongoDbSessionRepository.builder().mongoTemplate(mongoTemplate).build();
        }
    }

}