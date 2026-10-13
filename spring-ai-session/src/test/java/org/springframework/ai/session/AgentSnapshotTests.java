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

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Tests for {@link AgentSnapshot} and the attributes it is stored as.
 *
 * @author Christian Tzolov
 */
class AgentSnapshotTests {

	@Test
	void aSnapshotRoundTripsThroughTheSessionAttributes() {
		AgentSnapshot full = AgentSnapshot.of("reviewer", "3", "claude-sonnet-5-5");
		AgentSnapshot bare = AgentSnapshot.of("reviewer");

		assertThat(full.toAttributes()).containsExactly(entry(SessionAttributes.AGENT_NAME, "reviewer"),
				entry(SessionAttributes.AGENT_VERSION, "3"), entry(SessionAttributes.AGENT_MODEL, "claude-sonnet-5-5"));
		assertThat(bare.toAttributes()).containsOnlyKeys(SessionAttributes.AGENT_NAME);
		// Blank version and model read as absent
		assertThat(AgentSnapshot.of("x", " ", "")).isEqualTo(AgentSnapshot.of("x"));
		Session stored = Session.builder().id(UUID.randomUUID().toString()).metadata(full.toAttributes()).build();
		assertThat(SessionAttributes.agent(stored)).isEqualTo(full);
		assertThat(SessionAttributes.agentName(stored)).isEqualTo("reviewer");
		assertThat(SessionAttributes.agent(Session.builder().id(UUID.randomUUID().toString()).build())).isNull();
		assertThatIllegalArgumentException().isThrownBy(() -> AgentSnapshot.of(" "));
		// The request builder stores the same attributes
		CreateSessionRequest request = CreateSessionRequest.builder().agent(full).metadata("team", "core").build();
		assertThat(request.metadata()).containsEntry(SessionAttributes.AGENT_VERSION, "3").containsEntry("team", "core");
	}

	private static java.util.Map.Entry<String, Object> entry(String key, Object value) {
		return java.util.Map.entry(key, value);
	}

}
