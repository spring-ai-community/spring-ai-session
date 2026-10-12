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

package org.springframework.ai.session;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link SessionSelector} factories.
 *
 * @author Christian Tzolov
 */
class SessionSelectorTests {

	private final SessionService service = DefaultSessionService.builder()
		.sessionRepository(InMemorySessionRepository.builder().build())
		.build();

	@Test
	void principalSelectsTheSessionsOfThatPrincipal() {
		Session alice1 = create(SessionAttributes.PRINCIPAL_NAME, "alice");
		Session alice2 = create(SessionAttributes.PRINCIPAL_NAME, "alice");
		create(SessionAttributes.PRINCIPAL_NAME, "bob");

		assertThat(SessionSelector.principal("alice").select(this.service)).containsExactlyInAnyOrder(alice1.id(),
				alice2.id());
		assertThat(SessionSelector.principal("nobody").select(this.service)).isEmpty();
	}

	@Test
	void principalLeavesChildrenOutUnlessAsked() {
		Session root = create(SessionAttributes.PRINCIPAL_NAME, "alice");
		Session child = this.service.createChild(root.id(), "researcher");

		assertThat(SessionSelector.principal("alice").select(this.service)).containsExactly(root.id());
		assertThat(SessionSelector.principalWithChildren("alice").select(this.service))
			.containsExactlyInAnyOrder(root.id(), child.id());
	}

	@Test
	void byIndexSelectsOnAnyAttribute() {
		Session acme = create("tenant", "acme");
		create("tenant", "globex");

		assertThat(SessionSelector.byIndex("tenant", "acme").select(this.service)).containsExactly(acme.id());
	}

	@Test
	void ofIsAFixedCopy() {
		List<String> ids = new ArrayList<>(List.of("a", "b"));
		SessionSelector selector = SessionSelector.of(ids);
		ids.add("c");

		assertThat(selector.select(this.service)).containsExactly("a", "b");
	}

	private Session create(String key, String value) {
		return this.service.create(CreateSessionRequest.builder().metadata(key, value).build());
	}

	@Test
	void principalSelectorsLeaveArchivedSessionsOutButByIndexKeepsThem() {
		Session open = create(SessionAttributes.PRINCIPAL_NAME, "alice");
		Session archived = create(SessionAttributes.PRINCIPAL_NAME, "alice");
		Session child = this.service.createChild(archived.id(), "researcher");
		this.service.archive(archived.id());

		assertThat(SessionSelector.principal("alice").select(this.service)).containsExactly(open.id());
		assertThat(SessionSelector.principalWithChildren("alice").select(this.service)).containsExactly(open.id());
		assertThat(SessionSelector.byIndex(SessionAttributes.PRINCIPAL_NAME, "alice").select(this.service))
			.containsExactlyInAnyOrder(open.id(), archived.id(), child.id());
	}

}
