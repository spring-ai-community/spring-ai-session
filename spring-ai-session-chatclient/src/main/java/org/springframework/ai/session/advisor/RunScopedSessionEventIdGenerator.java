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

package org.springframework.ai.session.advisor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * Event ids by the position of a write in a run: the session, the run, how the turn
 * began (a new call, or the resumption of a parked turn), the round of the tool-calling
 * loop, and what the round writes (the request's message, or the reply of a generation). Never by what the message says, so two messages with the same text never
 * share an id, and a replay of the same run writes nothing twice.
 * <p>
 * The run is the caller's idempotency key for one {@code ChatClient} call, given with
 * {@link SessionTurnAdvisor#RUN_ID_CONTEXT_KEY}: a retry of the call uses the same run id,
 * a new call a new one. The {@link SessionTurnAdvisor} counts the rounds. Without a run id,
 * or without the turn advisor, the ids are random, as with
 * {@link SessionEventRequestIdGenerator#random()}: there is nothing to tell a retry from a
 * new call, so nothing is taken for a replay.
 * <p>
 * Ids have the form {@code <messageType>-<sha256 hex>}, at most 74 characters. A replayed
 * round whose model gives a different reply gets the same id as the first; the log keeps
 * the first reply.
 *
 * @author Christian Tzolov
 * @since 0.11.0
 */
public final class RunScopedSessionEventIdGenerator
		implements SessionEventRequestIdGenerator, SessionEventResponseIdGenerator {

	@Override
	public String generate(ChatClientRequest request, Message message) {
		return id(request.context(), message, "request");
	}

	@Override
	public String generate(ChatClientResponse response, Message message) {
		return id(response.context(), message, "reply-" + generationIndex(response.chatResponse(), message));
	}

	private static String id(Map<String, @Nullable Object> context, Message message, String slot) {
		String runId = SessionTurnAdvisor.runId(context);
		String phase = SessionTurnAdvisor.phase(context);
		Integer round = SessionTurnAdvisor.round(context);
		if (runId == null || phase == null || round == null) {
			return UUID.randomUUID().toString();
		}
		return message.getMessageType().getValue() + "-" + sha256Hex(
				SessionContexts.sessionId(context) + "\n" + runId + "\n" + phase + "\n" + round + "\n" + slot);
	}

	/** The place of the message among the reply's generations, found by identity. */
	private static int generationIndex(@Nullable ChatResponse chatResponse, Message message) {
		if (chatResponse != null) {
			List<Generation> generations = chatResponse.getResults();
			for (int i = 0; i < generations.size(); i++) {
				if (generations.get(i).getOutput() == message) {
					return i;
				}
			}
		}
		return 0;
	}

	private static String sha256Hex(String input) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 not available", ex);
		}
	}

}
