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

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.SlidingWindowCompactionStrategy;
import org.springframework.ai.session.internal.DefaultSessionService;
import org.springframework.ai.session.internal.InMemorySessionRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link DefaultSessionService}.
 */
class DefaultSessionServiceTests {

	private SessionService service;

	@BeforeEach
	void setUp() {
		this.service = new DefaultSessionService(InMemorySessionRepository.builder().build());
	}

	@Test
	void createReturnsSession() {
		Session session = this.service.create(CreateSessionRequest.builder().userId("user-1").build());

		assertThat(session).isNotNull();
		assertThat(session.id()).isNotBlank();
		assertThat(session.userId()).isEqualTo("user-1");
		assertThat(this.service.getEvents(session.id())).isEmpty();
	}

	@Test
	void appendMessageThenGetMessagesReturnsThatMessage() {
		Session session = this.service.create(CreateSessionRequest.builder().userId("user-1").build());

		this.service.appendMessage(session.id(), new UserMessage("hello"));

		List<Message> messages = this.service.getMessages(session.id());
		assertThat(messages).hasSize(1);
		assertThat(messages.get(0).getText()).isEqualTo("hello");
	}

	@Test
	void appendMultipleMessagesPreservesOrder() {
		Session session = this.service.create(CreateSessionRequest.builder().userId("user-1").build());

		this.service.appendMessage(session.id(), new UserMessage("first"));
		this.service.appendMessage(session.id(), new UserMessage("second"));
		this.service.appendMessage(session.id(), new UserMessage("third"));

		List<Message> messages = this.service.getMessages(session.id());
		assertThat(messages).hasSize(3);
		assertThat(messages.get(0).getText()).isEqualTo("first");
		assertThat(messages.get(1).getText()).isEqualTo("second");
		assertThat(messages.get(2).getText()).isEqualTo("third");
	}

	@Test
	void deleteRemovesSession() {
		Session session = this.service.create(CreateSessionRequest.builder().userId("user-1").build());

		this.service.delete(session.id());

		assertThat(this.service.findById(session.id())).isNull();
	}

	@Test
	void findByUserIdReturnsAllSessions() {
		this.service.create(CreateSessionRequest.builder().userId("alice").build());
		this.service.create(CreateSessionRequest.builder().userId("alice").build());
		this.service.create(CreateSessionRequest.builder().userId("bob").build());

		assertThat(this.service.findByUserId("alice")).hasSize(2);
		assertThat(this.service.findByUserId("bob")).hasSize(1);
	}

	@Test
	void compactWithSlidingWindowKeepsOnlyMaxEvents() {
		Session session = this.service.create(CreateSessionRequest.builder().userId("user-1").build());

		for (int i = 1; i <= 5; i++) {
			this.service.appendMessage(session.id(), new UserMessage("msg-" + i));
		}

		CompactionResult result = this.service.compact(session.id(), req -> true,
				new SlidingWindowCompactionStrategy(2));

		assertThat(result.eventsRemoved()).isEqualTo(3);
		assertThat(result.compactedEvents()).hasSize(2);

		List<Message> messages = this.service.getMessages(session.id());
		assertThat(messages).hasSize(2);
		assertThat(messages.get(0).getText()).isEqualTo("msg-4");
		assertThat(messages.get(1).getText()).isEqualTo("msg-5");
	}

	@Test
	void compactNoOpWhenTriggerDoesNotFire() {
		Session session = this.service.create(CreateSessionRequest.builder().userId("user-1").build());
		this.service.appendMessage(session.id(), new UserMessage("only message"));

		// Trigger never fires — no compaction, no repository write
		CompactionResult result = this.service.compact(session.id(), req -> false,
				new SlidingWindowCompactionStrategy(10));

		assertThat(result.archivedEvents()).isEmpty();
		assertThat(result.eventsRemoved()).isZero();
		assertThat(this.service.getEvents(session.id())).hasSize(1);
	}

	@Test
	void compactNoOpWhenStrategyArchivesNothing() {
		Session session = this.service.create(CreateSessionRequest.builder().userId("user-1").build());
		this.service.appendMessage(session.id(), new UserMessage("only message"));

		// Trigger fires but window is larger than event count — strategy returns no-op
		CompactionResult result = this.service.compact(session.id(), req -> true,
				new SlidingWindowCompactionStrategy(10));

		assertThat(result.archivedEvents()).isEmpty();
		assertThat(result.eventsRemoved()).isZero();
		assertThat(this.service.getEvents(session.id())).hasSize(1);
	}

	@Test
	void compactWithPreloadedSessionProducesSameResultAsSessionIdOverload() {
		Session session = this.service.create(CreateSessionRequest.builder().userId("user-1").build());

		for (int i = 1; i <= 5; i++) {
			this.service.appendMessage(session.id(), new UserMessage("msg-" + i));
		}

		// Session-object overload skips the internal findById() round-trip but must
		// produce an identical result to the sessionId overload.
		CompactionResult result = this.service.compact(session.id(), req -> true,
				new SlidingWindowCompactionStrategy(2));

		assertThat(result.eventsRemoved()).isEqualTo(3);
		assertThat(result.compactedEvents()).hasSize(2);

		List<Message> messages = this.service.getMessages(session.id());
		assertThat(messages).hasSize(2);
		assertThat(messages.get(0).getText()).isEqualTo("msg-4");
		assertThat(messages.get(1).getText()).isEqualTo("msg-5");
	}

	@Test
	void getEventsWithFilterAppliesFilter() {
		Session session = this.service.create(CreateSessionRequest.builder().userId("user-1").build());

		this.service.appendMessage(session.id(), new UserMessage("a"));
		this.service.appendMessage(session.id(), new UserMessage("b"));
		this.service.appendMessage(session.id(), new UserMessage("c"));

		List<SessionEvent> last2 = this.service.getEvents(session.id(), EventFilter.lastN(2));
		assertThat(last2).hasSize(2);
		assertThat(last2.get(0).getMessage().getText()).isEqualTo("b");
		assertThat(last2.get(1).getMessage().getText()).isEqualTo("c");
	}

	@Test
	void concurrentCompactionOnlyAppliesOnce() throws InterruptedException {
		Session session = this.service.create(CreateSessionRequest.builder().userId("user-1").build());

		for (int i = 1; i <= 5; i++) {
			this.service.appendMessage(session.id(), new UserMessage("msg-" + i));
		}

		// Two threads race to compact the same session to a window of 2.
		// The CAS in replaceEvents guarantees that only the first writer lands;
		// the second detects a version mismatch and skips silently.
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch go = new CountDownLatch(1);
		CompactionResult[] results = new CompactionResult[2];

		ExecutorService executor = Executors.newFixedThreadPool(2);
		for (int i = 0; i < 2; i++) {
			int idx = i;
			executor.submit(() -> {
				ready.countDown();
				try {
					go.await();
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				results[idx] = this.service.compact(session.id(), req -> true, new SlidingWindowCompactionStrategy(2));
			});
		}

		ready.await();
		go.countDown();
		executor.shutdown();
		assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

		// Exactly one compaction must have removed 3 events; the other is a no-op.
		int totalRemoved = results[0].eventsRemoved() + results[1].eventsRemoved();
		assertThat(totalRemoved).isEqualTo(3);

		// The surviving event list must be exactly the last 2 messages.
		List<Message> messages = this.service.getMessages(session.id());
		assertThat(messages).hasSize(2);
		assertThat(messages.get(0).getText()).isEqualTo("msg-4");
		assertThat(messages.get(1).getText()).isEqualTo("msg-5");
	}

}
