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

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.SlidingWindowCompactionStrategy;
import org.springframework.ai.session.compaction.TokenCountCompactionStrategy;
import org.springframework.ai.session.compaction.TurnWindowCompactionStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for session lifecycle, event management, compaction strategies, and
 * multi-agent branch isolation — exercised end-to-end through a Spring Boot application
 * context with {@link DefaultSessionService} and {@link InMemorySessionRepository}.
 *
 * @author Christian Tzolov
 */
@SpringBootTest(classes = SessionMemoryIT.TestConfig.class)
class SessionMemoryIT {

	@Autowired
	SessionService sessionService;

	// --- Session lifecycle ---

	@Test
	void createSessionAndFindById() {
		Session session = this.sessionService.create(CreateSessionRequest.builder().userId("alice").build());

		assertThat(session.id()).isNotBlank();
		assertThat(session.userId()).isEqualTo("alice");
		assertThat(session.createdAt()).isNotNull();

		Session found = this.sessionService.findById(session.id());
		assertThat(found).isNotNull();
		assertThat(found.id()).isEqualTo(session.id());
	}

	@Test
	void findByUserIdReturnsAllUserSessions() {
		this.sessionService.create(CreateSessionRequest.builder().userId("bob").build());
		this.sessionService.create(CreateSessionRequest.builder().userId("bob").build());
		this.sessionService.create(CreateSessionRequest.builder().userId("carol").build());

		List<Session> bobSessions = this.sessionService.findByUserId("bob");
		assertThat(bobSessions).hasSizeGreaterThanOrEqualTo(2);
		assertThat(bobSessions).allMatch(s -> "bob".equals(s.userId()));
	}

	@Test
	void deleteRemovesSessionCompletely() {
		Session session = this.sessionService.create(CreateSessionRequest.builder().userId("eve").build());

		this.sessionService.delete(session.id());

		assertThat(this.sessionService.findById(session.id())).isNull();
	}

	@Test
	void sessionWithTimeToLiveHasExpiresAt() {
		Session session = this.sessionService
			.create(CreateSessionRequest.builder().userId("frank").timeToLive(Duration.ofHours(1)).build());

		assertThat(session.expiresAt()).isNotNull();
		assertThat(session.expiresAt()).isAfter(session.createdAt());
	}

	// --- Event management ---

	@Test
	void appendMessagesAndRetrieveInOrder() {
		Session session = this.sessionService.create(CreateSessionRequest.builder().userId("user-1").build());

		this.sessionService.appendMessage(session.id(), new UserMessage("What is Spring AI?"));
		this.sessionService.appendMessage(session.id(), new AssistantMessage("Spring AI is a framework."));
		this.sessionService.appendMessage(session.id(), new UserMessage("Tell me more."));

		List<Message> messages = this.sessionService.getMessages(session.id());

		assertThat(messages).hasSize(3);
		assertThat(messages.get(0).getText()).isEqualTo("What is Spring AI?");
		assertThat(messages.get(1).getText()).isEqualTo("Spring AI is a framework.");
		assertThat(messages.get(2).getText()).isEqualTo("Tell me more.");
	}

	@Test
	void getEventsWithLastNFilterReturnsCorrectSlice() {
		Session session = this.sessionService.create(CreateSessionRequest.builder().userId("user-2").build());

		for (int i = 1; i <= 6; i++) {
			this.sessionService.appendMessage(session.id(), new UserMessage("message " + i));
		}

		List<SessionEvent> last3 = this.sessionService.getEvents(session.id(), EventFilter.lastN(3));

		assertThat(last3).hasSize(3);
		assertThat(last3.get(0).getMessage().getText()).isEqualTo("message 4");
		assertThat(last3.get(2).getMessage().getText()).isEqualTo("message 6");
	}

	@Test
	void keywordSearchReturnsOnlyMatchingEvents() {
		Session session = this.sessionService.create(CreateSessionRequest.builder().userId("user-3").build());

		this.sessionService.appendMessage(session.id(), new UserMessage("Spring AI is awesome"));
		this.sessionService.appendMessage(session.id(), new UserMessage("Machine learning is fascinating"));
		this.sessionService.appendMessage(session.id(), new UserMessage("Spring Boot makes things easy"));

		List<SessionEvent> results = this.sessionService.getEvents(session.id(), EventFilter.keywordSearch("spring"));

		assertThat(results).hasSize(2);
		assertThat(results).allMatch(e -> e.getMessage().getText().toLowerCase().contains("spring"));
	}

	@Test
	void excludeSyntheticFilterHidesSummaryEvents() {
		Session session = this.sessionService.create(CreateSessionRequest.builder().userId("user-4").build());

		this.sessionService.appendMessage(session.id(), new UserMessage("Hello"));
		this.sessionService.appendMessage(session.id(), new AssistantMessage("Hi there!"));

		// Inject synthetic summary events directly
		this.sessionService.appendEvent(SessionEvent.builder()
			.sessionId(session.id())
			.message(new UserMessage("Summarize the conversation so far."))
			.metadata(SessionEvent.METADATA_SYNTHETIC, true)
			.metadata(SessionEvent.METADATA_COMPACTION_SOURCE, "SlidingWindowCompactionStrategy")
			.build());
		this.sessionService.appendEvent(SessionEvent.builder()
			.sessionId(session.id())
			.message(new AssistantMessage("User said Hello, assistant replied Hi."))
			.metadata(SessionEvent.METADATA_SYNTHETIC, true)
			.metadata(SessionEvent.METADATA_COMPACTION_SOURCE, "SlidingWindowCompactionStrategy")
			.build());

		List<SessionEvent> allEvents = this.sessionService.getEvents(session.id());
		assertThat(allEvents).hasSize(4);

		List<SessionEvent> realOnly = this.sessionService.getEvents(session.id(), EventFilter.realOnly());
		assertThat(realOnly).hasSize(2);
		assertThat(realOnly).noneMatch(SessionEvent::isSynthetic);
	}

	// --- Compaction strategies ---

	@Test
	void slidingWindowCompactionKeepsLastNEvents() {
		Session session = this.sessionService.create(CreateSessionRequest.builder().userId("user-5").build());

		for (int i = 1; i <= 6; i++) {
			this.sessionService.appendMessage(session.id(), new UserMessage("user turn " + i));
			this.sessionService.appendMessage(session.id(), new AssistantMessage("assistant reply " + i));
		}

		CompactionResult result = this.sessionService.compact(session.id(), req -> true,
				SlidingWindowCompactionStrategy.builder().maxEvents(4).build());

		assertThat(result.eventsRemoved()).isPositive();
		assertThat(result.compactedEvents()).hasSizeLessThanOrEqualTo(4);

		// Verify the compacted events are persisted
		List<Message> messages = this.sessionService.getMessages(session.id());
		assertThat(messages).hasSizeLessThanOrEqualTo(4);
		// Window must start on a turn boundary (user message)
		assertThat(messages.get(0).getMessageType().getValue()).isEqualTo("user");
	}

	@Test
	void turnWindowCompactionKeepsLastNTurns() {
		Session session = this.sessionService.create(CreateSessionRequest.builder().userId("user-6").build());

		for (int i = 1; i <= 5; i++) {
			this.sessionService.appendMessage(session.id(), new UserMessage("user turn " + i));
			this.sessionService.appendMessage(session.id(), new AssistantMessage("assistant reply " + i));
		}

		CompactionResult result = this.sessionService.compact(session.id(), req -> true,
				TurnWindowCompactionStrategy.builder().maxTurns(2).build());

		assertThat(result.eventsRemoved()).isPositive();

		List<Message> messages = this.sessionService.getMessages(session.id());
		// 2 turns × 2 messages per turn = 4 messages
		assertThat(messages).hasSize(4);
		assertThat(messages.get(0).getText()).isEqualTo("user turn 4");
		assertThat(messages.get(3).getText()).isEqualTo("assistant reply 5");
	}

	@Test
	void tokenCountCompactionDropsOldestEventsOverBudget() {
		Session session = this.sessionService.create(CreateSessionRequest.builder().userId("user-7").build());

		// Add messages — each "token" word approximation ~1 token each
		for (int i = 1; i <= 10; i++) {
			this.sessionService.appendMessage(session.id(), new UserMessage("short msg " + i));
		}

		// Very small token budget forces compaction
		CompactionResult result = this.sessionService.compact(session.id(), req -> true,
				TokenCountCompactionStrategy.builder().maxTokens(20).build());

		assertThat(result.eventsRemoved()).isPositive();
		assertThat(result.compactedEvents().size()).isLessThan(10);
		// Kept window must start on a user message (turn boundary)
		if (!result.compactedEvents().isEmpty()) {
			assertThat(result.compactedEvents().get(0).getMessageType().getValue()).isEqualTo("user");
		}
	}

	@Test
	void compactionIsNoopWhenEventsWithinBudget() {
		Session session = this.sessionService.create(CreateSessionRequest.builder().userId("user-8").build());

		this.sessionService.appendMessage(session.id(), new UserMessage("hi"));
		this.sessionService.appendMessage(session.id(), new AssistantMessage("hello"));

		// Large window — no compaction needed
		CompactionResult result = this.sessionService.compact(session.id(), req -> true,
				SlidingWindowCompactionStrategy.builder().maxEvents(100).build());

		assertThat(result.eventsRemoved()).isEqualTo(0);
		assertThat(result.compactedEvents()).hasSize(2);
	}

	@Test
	void syntheticSummaryEventsArePreservedAfterCompaction() {
		Session session = this.sessionService.create(CreateSessionRequest.builder().userId("user-9").build());

		// Add some real events
		for (int i = 1; i <= 4; i++) {
			this.sessionService.appendMessage(session.id(), new UserMessage("msg " + i));
		}

		// Inject a synthetic summary
		this.sessionService.appendEvent(SessionEvent.builder()
			.sessionId(session.id())
			.message(new UserMessage("Summarize the conversation."))
			.metadata(SessionEvent.METADATA_SYNTHETIC, true)
			.metadata(SessionEvent.METADATA_COMPACTION_SOURCE, "SlidingWindowCompactionStrategy")
			.build());
		this.sessionService.appendEvent(SessionEvent.builder()
			.sessionId(session.id())
			.message(new AssistantMessage("User sent 4 messages."))
			.metadata(SessionEvent.METADATA_SYNTHETIC, true)
			.metadata(SessionEvent.METADATA_COMPACTION_SOURCE, "SlidingWindowCompactionStrategy")
			.build());

		// Compact aggressively — only 1 real event slot
		CompactionResult result = this.sessionService.compact(session.id(), req -> true,
				SlidingWindowCompactionStrategy.builder().maxEvents(1).build());

		// Synthetic events must survive compaction
		long syntheticCount = result.compactedEvents().stream().filter(SessionEvent::isSynthetic).count();
		assertThat(syntheticCount).isEqualTo(2);
	}

	// --- Multi-agent branch isolation ---

	@Test
	void branchFilterIsolatesSiblingAgentEvents() {
		Session session = this.sessionService.create(CreateSessionRequest.builder().userId("user-10").build());

		// Root event (no branch) — visible to all
		this.sessionService.appendEvent(SessionEvent.builder()
			.sessionId(session.id())
			.message(new UserMessage("orchestrator root task"))
			.build());

		// Researcher agent events
		this.sessionService.appendEvent(SessionEvent.builder()
			.sessionId(session.id())
			.message(new UserMessage("research query"))
			.branch("orch.researcher")
			.build());
		this.sessionService.appendEvent(SessionEvent.builder()
			.sessionId(session.id())
			.message(new AssistantMessage("research result"))
			.branch("orch.researcher")
			.build());

		// Writer agent events (sibling — must NOT see researcher events)
		this.sessionService.appendEvent(SessionEvent.builder()
			.sessionId(session.id())
			.message(new UserMessage("write task"))
			.branch("orch.writer")
			.build());
		this.sessionService.appendEvent(SessionEvent.builder()
			.sessionId(session.id())
			.message(new AssistantMessage("written content"))
			.branch("orch.writer")
			.build());

		// Researcher's view: sees root + own events, NOT writer's events
		List<SessionEvent> researcherView = this.sessionService.getEvents(session.id(),
				EventFilter.forBranch("orch.researcher"));
		assertThat(researcherView).hasSize(3);
		assertThat(researcherView).noneMatch(e -> "orch.writer".equals(e.getBranch()));

		// Writer's view: sees root + own events, NOT researcher's events
		List<SessionEvent> writerView = this.sessionService.getEvents(session.id(),
				EventFilter.forBranch("orch.writer"));
		assertThat(writerView).hasSize(3);
		assertThat(writerView).noneMatch(e -> "orch.researcher".equals(e.getBranch()));
	}

	@Test
	void branchFilterAncestorEventsVisibleToChildAgent() {
		Session session = this.sessionService.create(CreateSessionRequest.builder().userId("user-11").build());

		// Orchestrator-level event
		this.sessionService.appendEvent(SessionEvent.builder()
			.sessionId(session.id())
			.message(new UserMessage("orch task"))
			.branch("orch")
			.build());

		// Sub-researcher event
		this.sessionService.appendEvent(SessionEvent.builder()
			.sessionId(session.id())
			.message(new AssistantMessage("sub result"))
			.branch("orch.researcher")
			.build());

		// Deep child — can see both orch and orch.researcher events
		List<SessionEvent> deepView = this.sessionService.getEvents(session.id(),
				EventFilter.forBranch("orch.researcher.sub"));
		assertThat(deepView).hasSize(2);
	}

	// --- Spring Boot configuration ---

	@SpringBootConfiguration
	static class TestConfig {

		@Bean
		SessionRepository sessionRepository() {
			return InMemorySessionRepository.builder().build();
		}

		@Bean
		SessionService sessionService(SessionRepository sessionRepository) {
			return DefaultSessionService.builder().sessionRepository(sessionRepository).build();
		}

	}

}
