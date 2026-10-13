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

package org.springframework.ai.session.agent;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.agent.Conversation;

/**
 * What the {@link SessionFeature} keeps for one run in flight: the sessions of its context
 * paths, how many messages of each path are already stored, and the inherited prefix of
 * each nested conversation.
 */
final class RunState {

	/** The session of each context path; the root path is {@code ""}. */
	final Map<String, String> sessions = new ConcurrentHashMap<>();

	/** How many messages of each path's conversation are stored. */
	final Map<String, Integer> stored = new ConcurrentHashMap<>();

	/** How many messages a nested conversation started with (inherited, not its own). */
	final Map<String, Integer> inherited = new ConcurrentHashMap<>();

	/** The nested paths whose request marker was recorded on the parent. */
	final Set<String> requestMarked = ConcurrentHashMap.newKeySet();

	@Nullable Conversation rootConversation;

	int nextOrdinal(String path) {
		return this.stored.merge(path, 1, Integer::sum) - 1;
	}

	String root() {
		String root = this.sessions.get("");
		if (root == null) {
			throw new IllegalStateException("The run has no root session: the SessionFeature saw no run start");
		}
		return root;
	}

}
