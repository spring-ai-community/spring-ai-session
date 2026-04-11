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

import org.junit.jupiter.api.Test;

import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link SessionServiceAutoConfiguration}.
 *
 * @author Christian Tzolov
 */
class SessionServiceAutoConfigurationTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(SessionServiceAutoConfiguration.class));

	@Test
	void sessionServiceBeanIsNotCreatedWithoutRepository() {
		this.contextRunner.run(context -> assertThat(context).doesNotHaveBean(SessionService.class));
	}

	@Test
	void sessionServiceBeanIsCreatedWhenRepositoryPresent() {
		this.contextRunner
			.withBean(SessionRepository.class, () -> InMemorySessionRepository.builder().build())
			.run(context -> {
				assertThat(context).hasSingleBean(SessionService.class);
				assertThat(context).hasSingleBean(DefaultSessionService.class);
			});
	}

	@Test
	void sessionServiceBeanBacksOffWhenUserDefined() {
		SessionService customService = mock(SessionService.class);
		this.contextRunner
			.withBean(SessionRepository.class, () -> InMemorySessionRepository.builder().build())
			.withBean(SessionService.class, () -> customService)
			.run(context -> {
				assertThat(context).hasSingleBean(SessionService.class);
				assertThat(context.getBean(SessionService.class)).isSameAs(customService);
				assertThat(context).doesNotHaveBean(DefaultSessionService.class);
			});
	}

}
