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
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.util.StringUtils;

/**
 * A deterministic, chain-shape-agnostic {@link SessionEventRequestIdGenerator}/
 * {@link SessionEventResponseIdGenerator} that makes retried
 * {@code SessionMemoryAdvisor} writes idempotent instead of a fresh random id every
 * call. Implements both generator interfaces via overloaded {@code generate} methods,
 * sharing one hybrid derivation:
 *
 * <ul>
 * <li>For a tool-call/tool-response message, reuse the model's own
 * {@code ToolCall}/{@code ToolResponse} ids -- already globally unique per model turn,
 * and the natural idempotency key for any tool-calling durability layer sitting
 * elsewhere in the same application, so both stay consistent on the same identity. If
 * any of those ids is blank (some providers, e.g. Ollama, do not assign them), the tool
 * names, arguments and response data are hashed instead.
 * <li>Otherwise (plain user/system/assistant-text messages), fall back to a hash of the
 * message text.
 * </ul>
 * The derived key is combined with a context key/value fingerprint -- see below -- and
 * hashed with SHA-256, so every id has the bounded form {@code <messageType>-<sha256 hex>}
 * (at most 74 characters) regardless of how long the session id, the context values or
 * the list of tool-call ids are. Folding in more distinguishing context never causes a
 * false collision, it can only add uniqueness.
 *
 * <p>
 * This needs no run id, no loop-iteration counter, and no cooperation from whichever
 * advisor(s) happen to wrap {@code SessionMemoryAdvisor} -- it stays correct however many
 * times the advisor runs per call (e.g. nested inside a {@code ToolCallingAdvisor} loop,
 * or any other recursive advisor).
 *
 * <p>
 * <strong>Context key fingerprint:</strong> ids are derived from the values of a
 * configurable list of context keys (read from {@link ChatClientRequest#context()} /
 * {@link ChatClientResponse#context()}), joined in the order given. The no-arg
 * constructor defaults to {@code [SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY]} --
 * session-scoped ids, matching what a plain {@code SessionMemoryAdvisor} would log.
 * {@link #IdempotentSessionEventIdGenerator(String...)} takes the *complete* key list
 * instead of appending to that default, so a caller who also wants session-scoping
 * alongside another key must list {@code SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY}
 * explicitly, e.g. if the application also assigns a durable per-request run id
 * (context key conventionally named {@code "run-id"} or similar):
 * {@code new IdempotentSessionEventIdGenerator("run-id", SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY)}.
 * Folding in a run id makes ids retry-scoped rather than only session-scoped: a genuine
 * retry (same run id, same content) still dedupes, but two distinct calls that happen to
 * share identical content no longer collide just because they share a session.
 *
 * <p>
 * <strong>Known limitation of the content-hash fallback:</strong> two
 * <em>legitimately</em> identical messages within the same fingerprint collide and the
 * second is dropped as an apparent replay. With the default, session-scoped fingerprint
 * this applies across the <em>whole session</em> -- a user answering "yes" or "ok" twice
 * in one conversation, or an assistant repeating the same short reply, silently loses
 * the second occurrence. Add a context key that varies per logical turn (e.g. a durable
 * run id) if that distinction matters.
 *
 * @author Christian Tzolov
 * @since 0.7.0
 */
public final class IdempotentSessionEventIdGenerator implements SessionEventRequestIdGenerator, SessionEventResponseIdGenerator {

	private final List<String> contextKeys;

	/** Session-scoped ids only, equivalent to {@code (SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY)}. */
	public IdempotentSessionEventIdGenerator() {
		this(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY);
	}

	/**
	 * @param contextKeys the complete, ordered list of context keys to fingerprint --
	 * not appended to the no-arg default, so include
	 * {@code SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY} explicitly if session-scoping
	 * is still wanted alongside the other key(s).
	 */
	public IdempotentSessionEventIdGenerator(String... contextKeys) {
		this.contextKeys = List.of(contextKeys);
	}

	@Override
	public String generate(ChatClientRequest request, Message message) {
		return deriveEventId(contextFingerprint(request.context()), message);
	}

	@Override
	public String generate(ChatClientResponse response, Message message) {
		return deriveEventId(contextFingerprint(response.context()), message);
	}

	private String contextFingerprint(Map<String, Object> context) {
		return this.contextKeys.stream().map(key -> key + ":" + context.get(key)).collect(Collectors.joining(","));
	}

	private static String deriveEventId(String contextFingerprint, Message message) {
		String type = message.getMessageType().getValue();
		if (message instanceof AssistantMessage assistantMessage && assistantMessage.hasToolCalls()) {
			List<String> callIds = assistantMessage.getToolCalls().stream().map(AssistantMessage.ToolCall::id).toList();
			String key = allHaveText(callIds) ? "toolcall:" + String.join(",", callIds)
					: "content:" + assistantMessage.getText() + ":" + assistantMessage.getToolCalls()
						.stream()
						.map(tc -> tc.name() + "(" + tc.arguments() + ")")
						.collect(Collectors.joining(","));
			return type + "-" + sha256Hex(contextFingerprint + ":" + key);
		}
		if (message instanceof ToolResponseMessage toolResponseMessage) {
			List<String> responseIds = toolResponseMessage.getResponses()
				.stream()
				.map(ToolResponseMessage.ToolResponse::id)
				.toList();
			String key = allHaveText(responseIds) ? "toolresp:" + String.join(",", responseIds)
					: "content:" + toolResponseMessage.getResponses()
						.stream()
						.map(r -> r.name() + "->" + r.responseData())
						.collect(Collectors.joining(","));
			return type + "-" + sha256Hex(contextFingerprint + ":" + key);
		}
		String text = message.getText() == null ? "" : message.getText();
		return type + "-" + sha256Hex(contextFingerprint + ":content:" + text);
	}

	/**
	 * Some providers (e.g. Ollama) return empty tool-call ids; those are not unique and
	 * must not be used as an idempotency key.
	 */
	private static boolean allHaveText(List<String> ids) {
		return !ids.isEmpty() && ids.stream().allMatch(StringUtils::hasText);
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
