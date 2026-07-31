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

import java.util.UUID;

import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.session.SessionEvent;

/**
 * Derives the {@link SessionEvent#getId()} used when {@link SessionMemoryAdvisor#after}
 * persists an assistant reply message.
 *
 * <p>
 * Separate from {@link SessionEventRequestIdGenerator} rather than one shared signature
 * because {@code after(ChatClientResponse, AdvisorChain)} never receives the original
 * {@code ChatClientRequest} -- that's {@code BaseAdvisor}'s contract, not something this
 * SPI can paper over. Session id and any other context needed for a deterministic
 * derivation are still available via {@link ChatClientResponse#context()}, which carries
 * forward whatever the request's context held.
 *
 * <p>
 * The default, {@link #random()}, reproduces the id-less behaviour
 * {@code SessionService.appendMessage} always had before this SPI existed.
 *
 * @see SessionEventRequestIdGenerator the counterpart used in {@link SessionMemoryAdvisor#before}
 */
@FunctionalInterface
public interface SessionEventResponseIdGenerator {

	String generate(ChatClientResponse response, Message message);

	static SessionEventResponseIdGenerator random() {
		return (response, message) -> UUID.randomUUID().toString();
	}

}
