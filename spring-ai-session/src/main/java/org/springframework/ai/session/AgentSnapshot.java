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

import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import org.springframework.util.Assert;

/**
 * The resolved agent definition a session was created with: the name, and when known the
 * version and the model. The counterpart of an agent runtime's agent snapshot, which a
 * session takes at creation so that later updates to the agent never change a running
 * session. Stored as the session's {@link SessionAttributes#AGENT_NAME},
 * {@link SessionAttributes#AGENT_VERSION} and {@link SessionAttributes#AGENT_MODEL}
 * attributes, never updated.
 *
 * @param name the agent's name
 * @param version the agent definition's version, or {@code null} when it has none
 * @param model the model the agent is configured with, or {@code null} when unknown
 * @author Christian Tzolov
 * @since 0.11.0
 */
public record AgentSnapshot(String name, @Nullable String version, @Nullable String model) {

	public AgentSnapshot {
		Assert.hasText(name, "name must not be null or empty");
		version = (version != null && !version.isBlank()) ? version : null;
		model = (model != null && !model.isBlank()) ? model : null;
	}

	/** A snapshot of an agent known by name only. */
	public static AgentSnapshot of(String name) {
		return new AgentSnapshot(name, null, null);
	}

	/** A snapshot with a name, a version and a model. */
	public static AgentSnapshot of(String name, @Nullable String version, @Nullable String model) {
		return new AgentSnapshot(name, version, model);
	}

	/** The session attributes that store this snapshot. */
	public Map<String, Object> toAttributes() {
		Map<String, Object> attributes = new LinkedHashMap<>();
		attributes.put(SessionAttributes.AGENT_NAME, this.name);
		if (this.version != null) {
			attributes.put(SessionAttributes.AGENT_VERSION, this.version);
		}
		if (this.model != null) {
			attributes.put(SessionAttributes.AGENT_MODEL, this.model);
		}
		return attributes;
	}

}
