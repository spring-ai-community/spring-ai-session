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

package org.springaicommunity.session.autoconfigure;

import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for {@link DefaultSessionService}.
 *
 * <p>
 * Creates a {@link DefaultSessionService} bean whenever a {@link SessionRepository} bean
 * is present in the application context and no {@link SessionService} bean has been
 * declared by the application. This applies regardless of which
 * {@link SessionRepository} implementation is in use — in-memory, JDBC, Redis, etc.
 *
 * <p>
 * The auto-configuration is ordered after the JDBC repository auto-configuration so that
 * the repository bean is guaranteed to be available when this configuration runs.
 *
 * @author Christian Tzolov
 * @since 2.0.0
 */
@AutoConfiguration(afterName = "org.springaicommunity.session.jdbc.autoconfigure.JdbcSessionRepositoryAutoConfiguration")
@ConditionalOnClass(SessionService.class)
@ConditionalOnBean(SessionRepository.class)
@ConditionalOnMissingBean(SessionService.class)
public class SessionServiceAutoConfiguration {

	@Bean
	DefaultSessionService sessionService(SessionRepository sessionRepository) {
		return DefaultSessionService.builder().sessionRepository(sessionRepository).build();
	}

}
