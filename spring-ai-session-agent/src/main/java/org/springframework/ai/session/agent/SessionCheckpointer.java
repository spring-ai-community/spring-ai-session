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

import org.jspecify.annotations.Nullable;
import org.springframework.ai.agent.ResumeState;
import org.springframework.ai.agent.RunIdentification;
import org.springframework.ai.agent.checkpointer.Checkpointer;
import org.springframework.ai.agent.graph.NodeResumeState;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.EventType;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

/**
 * A {@link Checkpointer} over the session log: a checkpoint is a
 * {@link EventType#CHECKPOINT} event of the run's root session holding the resume chain
 * without its conversations, which are the sessions themselves. Resuming reads the newest
 * checkpoint of the run and rebuilds each level's conversation from the session of its
 * path, so one store gives memory, replay, resume and the live stream, and a message is
 * never stored twice.
 *
 * @author Christian Tzolov
 * @since 0.11.0
 */
public final class SessionCheckpointer implements Checkpointer {

	/** Payload key: the run the checkpoint belongs to. */
	public static final String PAYLOAD_RUN_ID = "runId";

	/** Payload key: the resume chain, conversations left out. */
	public static final String PAYLOAD_STATE = "state";

	/** Payload key: how many messages each context path had stored at the checkpoint. */
	public static final String PAYLOAD_STORED = "stored";

	private final SessionService sessions;

	private final SessionFeature sessionFeature;

	private final JsonMapper jsonMapper;

	SessionCheckpointer(SessionService sessions, SessionFeature feature, JsonMapper jsonMapper) {
		this.sessions = sessions;
		this.sessionFeature = feature;
		this.jsonMapper = jsonMapper;
	}

	@Override
	public void save(RunIdentification runId, ResumeState snapshot) {
		RunState state = this.sessionFeature.state(runId);
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put(PAYLOAD_RUN_ID, runId.runId());
		payload.put(PAYLOAD_STATE, this.jsonMapper.convertValue(withoutConversations(snapshot), Object.class));
		payload.put(PAYLOAD_STORED, new LinkedHashMap<>(state.stored));
		this.sessions.appendEvent(SessionEvent.builder()
			.sessionId(runId.sessionId())
			.type(EventType.CHECKPOINT)
			.payload(payload)
			.build());
	}

	@Override
	public @Nullable ResumeState getLatestSnapshot(RunIdentification runId) {
		List<SessionEvent> checkpoints = this.sessions.getEvents(runId.sessionId(),
				EventFilter.builder().eventTypes(Set.of(EventType.CHECKPOINT)).build());
		SessionEvent newest = null;
		for (SessionEvent event : checkpoints) {
			if (runId.runId().equals(event.getPayload().get(PAYLOAD_RUN_ID))) {
				newest = event;
			}
		}
		if (newest == null) {
			return null;
		}
		ResumeState chain = this.jsonMapper.convertValue(newest.getPayload().get(PAYLOAD_STATE), ResumeState.class);
		RunState state = this.sessionFeature.state(runId);
		if (newest.getPayload().get(PAYLOAD_STORED) instanceof Map<?, ?> stored) {
			stored.forEach((path, count) -> {
				if (path != null && count instanceof Number n) {
					state.stored.put(String.valueOf(path), n.intValue());
				}
			});
		}
		return withConversations(chain, runId.sessionId(), "");
	}

	/** The chain with empty conversations: the sessions hold them. */
	private static ResumeState withoutConversations(ResumeState state) {
		if (state instanceof NodeResumeState node) {
			return new NodeResumeState(List.of(), node.getSerializedStorage(), node.getName(),
					node.getSerializedNodeOutput());
		}
		if (state.hasChild()) {
			return ResumeState.forNonLeaf(state.getName(), List.of(), state.getSerializedStorage(),
					withoutConversations(state.getChild()));
		}
		return ResumeState.forLeaf(state.getName(), List.of(), state.getSerializedStorage());
	}

	/** The chain with each level's conversation read from the session of its path. */
	private ResumeState withConversations(ResumeState state, String rootSessionId, String path) {
		List<JsonNode> conversation = new ArrayList<>();
		String sessionId = SessionFeature.sessionIdFor(rootSessionId, path);
		if (this.sessions.findById(sessionId) != null) {
			for (Message message : this.sessions.getActiveMessages(sessionId)) {
				conversation.add(this.jsonMapper.valueToTree(message));
			}
		}
		if (state instanceof NodeResumeState node) {
			return new NodeResumeState(conversation, node.getSerializedStorage(), node.getName(),
					node.getSerializedNodeOutput());
		}
		if (state.hasChild()) {
			return ResumeState.forNonLeaf(state.getName(), conversation, state.getSerializedStorage(),
					withConversations(state.getChild(), rootSessionId, path + state.getName() + "/"));
		}
		return ResumeState.forLeaf(state.getName(), conversation, state.getSerializedStorage());
	}

}
