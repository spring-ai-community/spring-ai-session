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

package org.springframework.ai.session.compaction;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Tests for {@link RecursiveSummarizationCompactionStrategy}.
 *
 * <p>
 * The LLM call is mocked so tests run without a real AI model.
 */
class RecursiveSummarizationCompactionStrategyTests {

	private static final String SESSION_ID = "test-session";

	private static final String SUMMARY_TEXT = "Summary: the user asked about Java and got answers.";

	private ChatClient chatClient;

	@BeforeEach
	void setUp() {
		// Deep mock: ChatClient → prompt() → system() → user() → call() → content()
		this.chatClient = mock(ChatClient.class, Answers.RETURNS_DEEP_STUBS);
		given(this.chatClient.prompt().system(anyString()).user(anyString()).call().content()).willReturn(SUMMARY_TEXT);
		// Reset interaction counts so test verifications start from a clean slate
		clearInvocations(this.chatClient);
	}

	@Test
	void noCompactionWhenUnderLimit() {
		RecursiveSummarizationCompactionStrategy strategy = RecursiveSummarizationCompactionStrategy
			.builder(this.chatClient)
			.maxEventsToKeep(10)
			.build();

		List<SessionEvent> events = buildRealEvents(5);
		CompactionRequest context = contextFor(events);

		CompactionResult result = strategy.compact(context);

		assertThat(result.compactedEvents()).hasSize(5);
		assertThat(result.archivedEvents()).isEmpty();
		assertThat(result.eventsRemoved()).isEqualTo(0);
		verifyNoMoreInteractions(this.chatClient);
	}

	@Test
	void noCompactionWhenAtExactLimit() {
		RecursiveSummarizationCompactionStrategy strategy = RecursiveSummarizationCompactionStrategy
			.builder(this.chatClient)
			.maxEventsToKeep(5)
			.build();

		List<SessionEvent> events = buildRealEvents(5);
		CompactionRequest context = contextFor(events);

		CompactionResult result = strategy.compact(context);

		assertThat(result.compactedEvents()).hasSize(5);
		assertThat(result.eventsRemoved()).isEqualTo(0);
		verifyNoMoreInteractions(this.chatClient);
	}

	@Test
	void compactionProducesSyntheticSummaryPlusActiveWindow() {
		RecursiveSummarizationCompactionStrategy strategy = RecursiveSummarizationCompactionStrategy
			.builder(this.chatClient)
			.maxEventsToKeep(3)
			.overlapSize(0)
			.build();

		List<SessionEvent> events = buildRealEvents(6);
		CompactionRequest context = contextFor(events);

		CompactionResult result = strategy.compact(context);

		// 2 synthetic summary turn events (USER shadow + ASSISTANT summary) + 3 active
		// window events
		assertThat(result.compactedEvents()).hasSize(5);
		assertThat(result.compactedEvents().get(0).isSynthetic()).isTrue();
		assertThat(result.compactedEvents().get(0).getMessageType()).isEqualTo(MessageType.USER);
		assertThat(result.compactedEvents().get(1).isSynthetic()).isTrue();
		assertThat(result.compactedEvents().get(1).getMessageType()).isEqualTo(MessageType.ASSISTANT);
		assertThat(result.compactedEvents().get(1).getMessage().getText()).isEqualTo(SUMMARY_TEXT);
		// last 3 real events are preserved intact
		assertThat(result.compactedEvents().get(2).getMessage().getText()).isEqualTo("msg-4");
		assertThat(result.compactedEvents().get(3).getMessage().getText()).isEqualTo("msg-5");
		assertThat(result.compactedEvents().get(4).getMessage().getText()).isEqualTo("msg-6");
	}

	@Test
	void archivedEventsContainsOlderEvents() {
		RecursiveSummarizationCompactionStrategy strategy = RecursiveSummarizationCompactionStrategy
			.builder(this.chatClient)
			.maxEventsToKeep(2)
			.overlapSize(0)
			.build();

		List<SessionEvent> events = buildRealEvents(5);
		CompactionRequest context = contextFor(events);

		CompactionResult result = strategy.compact(context);

		assertThat(result.archivedEvents()).hasSize(3);
		assertThat(result.archivedEvents().stream().map(e -> e.getMessage().getText()).toList())
			.containsExactly("msg-1", "msg-2", "msg-3");
		assertThat(result.eventsRemoved()).isEqualTo(3);
	}

	@Test
	void priorSyntheticSummaryIsReplacedButNotCountedAsArchived() {
		RecursiveSummarizationCompactionStrategy strategy = RecursiveSummarizationCompactionStrategy
			.builder(this.chatClient)
			.maxEventsToKeep(2)
			.overlapSize(0)
			.build();

		List<SessionEvent> events = new ArrayList<>();
		// Prior synthetic summary from a previous compaction pass
		events.add(SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(new UserMessage(RecursiveSummarizationCompactionStrategy.DEFAULT_SUMMARY_SHADOW_PROMPT))
			.metadata(SessionEvent.METADATA_SYNTHETIC, true)
			.metadata(SessionEvent.METADATA_COMPACTION_SOURCE, "recursive-summarization")
			.build());
		events.add(SessionEvent.builder()
			.sessionId(SESSION_ID)
			.message(new AssistantMessage("Prior summary text"))
			.metadata(SessionEvent.METADATA_SYNTHETIC, true)
			.metadata(SessionEvent.METADATA_COMPACTION_SOURCE, "recursive-summarization")
			.build());
		events.addAll(buildRealEvents(4)); // msg-1 … msg-4

		CompactionRequest context = contextFor(events);
		CompactionResult result = strategy.compact(context);

		// archivedEvents contains only the real events that were summarized;
		// prior synthetic events are implicitly replaced by the new summaryTurn and are
		// NOT included in archivedEvents (consistent with other strategies).
		assertThat(result.archivedEvents().stream().noneMatch(SessionEvent::isSynthetic)).isTrue();
		assertThat(result.archivedEvents()).hasSize(2); // msg-1 and msg-2

		// New synthetic summary turn (USER shadow + ASSISTANT summary) is first in
		// compacted, followed by the last 2 real events.
		assertThat(result.compactedEvents()).hasSize(4);
		assertThat(result.compactedEvents().get(0).isSynthetic()).isTrue();
		assertThat(result.compactedEvents().get(0).getMessageType()).isEqualTo(MessageType.USER);
		assertThat(result.compactedEvents().get(1).isSynthetic()).isTrue();
		assertThat(result.compactedEvents().get(1).getMessage().getText()).isEqualTo(SUMMARY_TEXT);
	}

	@Test
	void customShadowPromptAppearsInSummaryTurn() {
		String customShadow = "Bitte fasse unser bisheriges Gespräch zusammen.";
		RecursiveSummarizationCompactionStrategy strategy = RecursiveSummarizationCompactionStrategy
			.builder(this.chatClient)
			.maxEventsToKeep(2)
			.overlapSize(0)
			.shadowPrompt(customShadow)
			.build();

		List<SessionEvent> events = buildRealEvents(4);
		CompactionResult result = strategy.compact(contextFor(events));

		SessionEvent shadowEvent = result.compactedEvents().get(0);
		assertThat(shadowEvent.isSynthetic()).isTrue();
		assertThat(shadowEvent.getMessageType()).isEqualTo(MessageType.USER);
		assertThat(shadowEvent.getMessage().getText()).isEqualTo(customShadow);
	}

	@Test
	void summaryEventHasCorrectCompactionSource() {
		RecursiveSummarizationCompactionStrategy strategy = RecursiveSummarizationCompactionStrategy
			.builder(this.chatClient)
			.maxEventsToKeep(1)
			.overlapSize(0)
			.build();

		List<SessionEvent> events = buildRealEvents(3);
		CompactionResult result = strategy.compact(contextFor(events));

		// Both events in the summary turn carry the compactionSource metadata
		SessionEvent shadowPrompt = result.compactedEvents().get(0);
		SessionEvent summaryAssistant = result.compactedEvents().get(1);
		assertThat(shadowPrompt.isSynthetic()).isTrue();
		assertThat(shadowPrompt.getMetadata()).containsEntry(SessionEvent.METADATA_COMPACTION_SOURCE,
				"recursive-summarization");
		assertThat(summaryAssistant.isSynthetic()).isTrue();
		assertThat(summaryAssistant.getMetadata()).containsEntry(SessionEvent.METADATA_COMPACTION_SOURCE,
				"recursive-summarization");
	}

	@Test
	void summaryTurnIsUserAssistantPairWithShadowPrompt() {
		RecursiveSummarizationCompactionStrategy strategy = RecursiveSummarizationCompactionStrategy
			.builder(this.chatClient)
			.maxEventsToKeep(2)
			.overlapSize(0)
			.build();

		List<SessionEvent> events = buildRealEvents(4);
		CompactionResult result = strategy.compact(contextFor(events));

		// First event: synthetic USER shadow prompt
		SessionEvent shadow = result.compactedEvents().get(0);
		assertThat(shadow.isSynthetic()).isTrue();
		assertThat(shadow.getMessageType()).isEqualTo(MessageType.USER);
		assertThat(shadow.getMessage().getText())
			.isEqualTo(RecursiveSummarizationCompactionStrategy.DEFAULT_SUMMARY_SHADOW_PROMPT);

		// Second event: synthetic ASSISTANT summary
		SessionEvent summaryMsg = result.compactedEvents().get(1);
		assertThat(summaryMsg.isSynthetic()).isTrue();
		assertThat(summaryMsg.getMessageType()).isEqualTo(MessageType.ASSISTANT);
		assertThat(summaryMsg.getMessage().getText()).isEqualTo(SUMMARY_TEXT);
	}

	@Test
	void snapsToTurnBoundaryWhenRawCutLandsOnAssistantEvent() {
		// maxEventsToKeep = 2
		// Session: [u1, a1, u2, a2, u3, a3] (3 turns, 6 events)
		// Raw cutIndex = 6 - 2 = 4 → real[4] = u3 (USER — already a turn start, no snap)
		//
		// Test the split case: multi-step turn where assistant follows assistant.
		// Session: [u1, a1, u2, a2a, a2b, u3, a3] (3 turns, 7 events)
		// maxEventsToKeep = 3 → rawCutIndex = 7 - 3 = 4 → real[4] = a2b (ASSISTANT)
		// Snap: real[4]=a2b(ASSISTANT) → real[5]=u3(USER) → cutIndex=5
		// activeWindow = [u3, a3], toArchive = [u1, a1, u2, a2a, a2b]
		RecursiveSummarizationCompactionStrategy strategy = RecursiveSummarizationCompactionStrategy
			.builder(this.chatClient)
			.maxEventsToKeep(3)
			.overlapSize(0)
			.build();

		List<SessionEvent> events = new ArrayList<>();
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage("u1")).build());
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new AssistantMessage("a1")).build());
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage("u2")).build());
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new AssistantMessage("a2a")).build()); // multi-step
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new AssistantMessage("a2b")).build()); // raw
																												// cut
																												// lands
																												// here
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage("u3")).build());
		events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new AssistantMessage("a3")).build());

		CompactionResult result = strategy.compact(contextFor(events));

		// Summary turn: [0]=synthetic USER shadow, [1]=synthetic ASSISTANT summary
		// Active window starts at index 2: must start at u3, not at a2b
		assertThat(result.compactedEvents().get(0).isSynthetic()).isTrue();
		assertThat(result.compactedEvents().get(0).getMessageType()).isEqualTo(MessageType.USER);
		assertThat(result.compactedEvents().get(1).isSynthetic()).isTrue();
		assertThat(result.compactedEvents().get(1).getMessageType()).isEqualTo(MessageType.ASSISTANT);
		assertThat(result.compactedEvents().get(2).getMessage().getText()).isEqualTo("u3");
		assertThat(result.compactedEvents().get(3).getMessage().getText()).isEqualTo("a3");

		// a2b should be archived, not kept
		assertThat(result.archivedEvents().stream().map(e -> e.getMessage().getText()).toList()).contains("a2b");
	}

	@Test
	void nullEventsThrowsException() {
		RecursiveSummarizationCompactionStrategy strategy = RecursiveSummarizationCompactionStrategy
			.builder(this.chatClient)
			.build();

		assertThatThrownBy(() -> strategy.compact(null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void nullChatClientThrowsException() {
		assertThatThrownBy(() -> RecursiveSummarizationCompactionStrategy.builder(null).build())
			.isInstanceOf(IllegalArgumentException.class);
	}

	// --- LLM failure handling ---

	@Test
	void llmReturningNullSummarySkipsCompactionAndReturnsUnchangedEvents() {
		given(this.chatClient.prompt().system(anyString()).user(anyString()).call().content()).willReturn(null);
		clearInvocations(this.chatClient);

		RecursiveSummarizationCompactionStrategy strategy = RecursiveSummarizationCompactionStrategy
			.builder(this.chatClient)
			.maxEventsToKeep(2)
			.overlapSize(0)
			.build();

		List<SessionEvent> events = buildRealEvents(5);
		CompactionResult result = strategy.compact(contextFor(events));

		assertThat(result.archivedEvents()).isEmpty();
		assertThat(result.eventsRemoved()).isZero();
		assertThat(result.compactedEvents()).hasSize(5);
	}

	@Test
	void llmReturningBlankSummarySkipsCompactionAndReturnsUnchangedEvents() {
		given(this.chatClient.prompt().system(anyString()).user(anyString()).call().content()).willReturn("   ");
		clearInvocations(this.chatClient);

		RecursiveSummarizationCompactionStrategy strategy = RecursiveSummarizationCompactionStrategy
			.builder(this.chatClient)
			.maxEventsToKeep(2)
			.overlapSize(0)
			.build();

		List<SessionEvent> events = buildRealEvents(5);
		CompactionResult result = strategy.compact(contextFor(events));

		assertThat(result.archivedEvents()).isEmpty();
		assertThat(result.eventsRemoved()).isZero();
	}

	@Test
	void onSummarizationFailureCallbackInvokedWhenLlmReturnsNull() {
		given(this.chatClient.prompt().system(anyString()).user(anyString()).call().content()).willReturn(null);
		clearInvocations(this.chatClient);

		AtomicReference<CompactionRequest> captured = new AtomicReference<>();
		RecursiveSummarizationCompactionStrategy strategy = RecursiveSummarizationCompactionStrategy
			.builder(this.chatClient)
			.maxEventsToKeep(2)
			.overlapSize(0)
			.onSummarizationFailure(captured::set)
			.build();

		List<SessionEvent> events = buildRealEvents(5);
		CompactionRequest context = contextFor(events);
		strategy.compact(context);

		assertThat(captured.get()).isNotNull();
		assertThat(captured.get().session().id()).isEqualTo(SESSION_ID);
	}

	@Test
	void onSummarizationFailureCallbackNotInvokedOnSuccess() {
		// chatClient already wired to return SUMMARY_TEXT in @BeforeEach

		AtomicReference<CompactionRequest> captured = new AtomicReference<>();
		RecursiveSummarizationCompactionStrategy strategy = RecursiveSummarizationCompactionStrategy
			.builder(this.chatClient)
			.maxEventsToKeep(2)
			.overlapSize(0)
			.onSummarizationFailure(captured::set)
			.build();

		List<SessionEvent> events = buildRealEvents(5);
		strategy.compact(contextFor(events));

		assertThat(captured.get()).isNull();
	}

	// --- overlapSize / maxEventsToKeep cross-validation ---

	@Test
	void overlapSizeEqualToMaxEventsToKeepIsRejected() {
		assertThatThrownBy(() -> RecursiveSummarizationCompactionStrategy.builder(this.chatClient)
			.maxEventsToKeep(5)
			.overlapSize(5)
			.build()).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("overlapSize")
			.hasMessageContaining("maxEventsToKeep");
	}

	@Test
	void overlapSizeGreaterThanMaxEventsToKeepIsRejected() {
		assertThatThrownBy(() -> RecursiveSummarizationCompactionStrategy.builder(this.chatClient)
			.maxEventsToKeep(3)
			.overlapSize(4)
			.build()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("overlapSize");
	}

	@Test
	void overlapSizeOneLessThanMaxEventsToKeepIsAccepted() {
		// Boundary case: overlapSize = maxEventsToKeep - 1 is valid (though unusual)
		RecursiveSummarizationCompactionStrategy strategy = RecursiveSummarizationCompactionStrategy
			.builder(this.chatClient)
			.maxEventsToKeep(5)
			.overlapSize(4)
			.build();
		assertThat(strategy).isNotNull();
	}

	// --- helpers ---

	private List<SessionEvent> buildRealEvents(int count) {
		List<SessionEvent> events = new ArrayList<>();
		for (int i = 1; i <= count; i++) {
			events.add(SessionEvent.builder().sessionId(SESSION_ID).message(new UserMessage("msg-" + i)).build());
		}
		return events;
	}

	private CompactionRequest contextFor(List<SessionEvent> events) {
		Session session = Session.builder().id(SESSION_ID).userId("test-user").build();
		return CompactionRequest.of(session, new ArrayList<>(events));
	}

}
