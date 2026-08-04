package org.springaicommunity.session.mongodb.autoconfigure;

import org.springframework.ai.session.mongodb.MongoDbSessionRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.MongoTemplate;

@AutoConfiguration
@ConditionalOnClass({MongoDbSessionRepository.class, MongoTemplate.class})
public class MongoDbSessionRepositoryAutoconfiguration {

    @Bean
    @ConditionalOnMissingBean
    MongoDbSessionRepository mongoDbSessionRepository(MongoTemplate mongoTemplate) {
        return MongoDbSessionRepository.builder().mongoTemplate(mongoTemplate).build();
    }
}
