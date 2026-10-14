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
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.content.MediaContent;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.tokenizer.TokenCountEstimator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.ai.session.compaction.CompactionTestSupport.SESSION_ID;
import static org.springframework.ai.session.compaction.CompactionTestSupport.assistant;
import static org.springframework.ai.session.compaction.CompactionTestSupport.covered;
import static org.springframework.ai.session.compaction.CompactionTestSupport.events;
import static org.springframework.ai.session.compaction.CompactionTestSupport.kept;
import static org.springframework.ai.session.compaction.CompactionTestSupport.request;
import static org.springframework.ai.session.compaction.CompactionTestSupport.summaryTurn;
import static org.springframework.ai.session.compaction.CompactionTestSupport.system;
import static org.springframework.ai.session.compaction.CompactionTestSupport.toolTurn;
import static org.springframework.ai.session.compaction.CompactionTestSupport.turn;
import static org.springframework.ai.session.compaction.CompactionTestSupport.user;
import static org.springframework.ai.session.compaction.CompactionTestSupport.userMessages;

/**
 * Tests for {@link RecursiveSummarizationCompactionStrategy}. The LLM call is mocked so
 * tests run without a real AI model.
 */
class RecursiveSummarizationCompactionStrategyTests {

	private static final String SUMMARY_TEXT = "Summary: the user asked about Java and got answers.";

	private static final String SHADOW = RecursiveSummarizationCompactionStrategy.DEFAULT_SUMMARY_SHADOW_PROMPT;

	private ChatClient chatClient;

	@BeforeEach
	void setUp() {
		// Deep mock: ChatClient → prompt() → system() → user() → call() → content()
		this.chatClient = mock(ChatClient.class, Answers.RETURNS_DEEP_STUBS);
		given(this.chatClient.prompt().system(anyString()).user(anyString()).call().content()).willReturn(SUMMARY_TEXT);
		clearInvocations(this.chatClient);
	}

	private RecursiveSummarizationCompactionStrategy.Builder strategy(int maxEvents, int overlapSize) {
		return RecursiveSummarizationCompactionStrategy.builder(this.chatClient)
			.maxEvents(maxEvents)
			.overlapSize(overlapSize);
	}

	@Test
	void noCompactionWhenUnderLimit() {
		CompactionResult result = strategy(10, 2).build().compact(request(userMessages(5)));

		assertThat(result.isUnchanged()).isTrue();
		verifyNoMoreInteractions(this.chatClient);
	}

	@Test
	void noCompactionWhenAtExactLimit() {
		assertThat(strategy(5, 2).build().compact(request(userMessages(5))).isUnchanged()).isTrue();
		verifyNoMoreInteractions(this.chatClient);
	}

	@Test
	void compactionProducesASummaryTurnAndKeepsTheActiveWindow() {
		CompactionResult result = strategy(3, 0).build().compact(request(userMessages(6)));

		assertThat(result.summary()).extracting(m -> m.getMessageType())
			.containsExactly(MessageType.USER, MessageType.ASSISTANT);
		assertThat(kept(result)).containsExactly(SHADOW, SUMMARY_TEXT, "msg-4", "msg-5", "msg-6");
		assertThat(covered(result)).containsExactly("msg-1", "msg-2", "msg-3");
		assertThat(result.strategy()).isEqualTo("recursive-summarization");
	}

	@Test
	void priorSummaryIsReplacedAndCovered() {
		CompactionResult result = strategy(2, 0).build()
			.compact(request(events(summaryTurn("Prior summary text"), userMessages(4))));

		// The prior summary turn and the summarized real events leave the view; the new
		// summary turn precedes the kept window.
		assertThat(result.coveredEvents()).hasSize(4);
		assertThat(result.coveredEvents().stream().filter(SessionEvent::isSynthetic)).hasSize(2);
		assertThat(result.pinnedEvents()).isEmpty();
		assertThat(kept(result)).containsExactly(SHADOW, SUMMARY_TEXT, "msg-3", "msg-4");
	}

	@Test
	void customShadowPromptOpensTheSummaryTurn() {
		String customShadow = "Bitte fasse unser bisheriges Gespräch zusammen.";
		CompactionResult result = strategy(2, 0).shadowPrompt(customShadow).build().compact(request(userMessages(4)));

		assertThat(result.summary().get(0).getText()).isEqualTo(customShadow);
		assertThat(result.summary().get(1).getText()).isEqualTo(SUMMARY_TEXT);
	}

	@Test
	void snapsToTurnBoundaryWhenRawCutLandsOnAssistantEvent() {
		// [u1, a1, u2, a2a, a2b, u3, a3], keep 3: the raw cut lands on a2b and snaps to u3
		List<SessionEvent> view = List.of(user("u1"), assistant("a1"), user("u2"), assistant("a2a"), assistant("a2b"),
				user("u3"), assistant("a3"));

		CompactionResult result = strategy(3, 0).build().compact(request(view));

		assertThat(kept(result)).containsExactly(SHADOW, SUMMARY_TEXT, "u3", "a3");
		assertThat(covered(result)).contains("a2b");
	}

	@Test
	void nullRequestThrowsException() {
		assertThatThrownBy(() -> strategy(10, 2).build().compact(null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void nullChatClientThrowsException() {
		assertThatThrownBy(() -> RecursiveSummarizationCompactionStrategy.builder(null).build())
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void llmReturningNullSummaryLeavesTheViewUnchanged() {
		given(this.chatClient.prompt().system(anyString()).user(anyString()).call().content()).willReturn(null);
		clearInvocations(this.chatClient);

		assertThat(strategy(2, 0).build().compact(request(userMessages(5))).isUnchanged()).isTrue();
	}

	@Test
	void llmReturningBlankSummaryLeavesTheViewUnchanged() {
		given(this.chatClient.prompt().system(anyString()).user(anyString()).call().content()).willReturn("   ");
		clearInvocations(this.chatClient);
		List<SessionEvent> view = List.of(system("v1"), user("u1"), assistant("a1"), user("u2"), system("v2"),
				assistant("a2"), user("u3"), assistant("a3"));

		assertThat(strategy(2, 0).build().compact(request(view)).isUnchanged()).isTrue();
	}

	@Test
	void tokensEstimatedSavedIsNetOfTheNewSummaryTurn() {
		List<SessionEvent> view = List.of(user("u1 " + "x".repeat(200)), assistant("a1 " + "y".repeat(200)), user("u2"),
				assistant("a2"));

		CompactionResult result = strategy(2, 0).tokenCountEstimator(capturingEstimator(new ArrayList<>()))
			.build()
			.compact(request(view));

		int coveredTokens = result.coveredEvents().stream().mapToInt(e -> CompactionUtils.formatEvent(e).length()).sum();
		int summaryTokens = result.summary().stream().mapToInt(m -> CompactionUtils.formatMessage(m).length()).sum();
		assertThat(summaryTokens).isPositive();
		assertThat(result.tokensEstimatedSaved()).isEqualTo(coveredTokens - summaryTokens);
	}

	@Test
	void onSummarizationFailureCallbackInvokedWhenLlmReturnsNull() {
		given(this.chatClient.prompt().system(anyString()).user(anyString()).call().content()).willReturn(null);
		clearInvocations(this.chatClient);
		AtomicReference<CompactionRequest> captured = new AtomicReference<>();

		strategy(2, 0).onSummarizationFailure(captured::set).build().compact(request(userMessages(5)));

		assertThat(captured.get()).isNotNull();
		assertThat(captured.get().session().id()).isEqualTo(SESSION_ID);
	}

	@Test
	void onSummarizationFailureCallbackNotInvokedOnSuccess() {
		AtomicReference<CompactionRequest> captured = new AtomicReference<>();

		strategy(2, 0).onSummarizationFailure(captured::set).build().compact(request(userMessages(5)));

		assertThat(captured.get()).isNull();
	}

	@Test
	void overlapSizeEqualToMaxEventsToKeepIsRejected() {
		assertThatThrownBy(() -> strategy(5, 5).build()).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("overlapSize")
			.hasMessageContaining("maxEvents");
	}

	@Test
	void overlapSizeGreaterThanMaxEventsToKeepIsRejected() {
		assertThatThrownBy(() -> strategy(3, 4).build()).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("overlapSize");
	}

	@Test
	void overlapSizeOneLessThanMaxEventsToKeepIsAccepted() {
		assertThat(strategy(5, 4).build()).isNotNull();
	}

	@Test
	void toolCallNamesAndArgumentsAppearInFormattedOutput() {
		List<String> estimatedTexts = new ArrayList<>();

		strategy(2, 0).tokenCountEstimator(capturingEstimator(estimatedTexts))
			.build()
			.compact(request(events(toolTurn("What's the weather?"), turn("Thanks", "You're welcome"))));

		assertThat(estimatedTexts).anyMatch(t -> t.contains("get_weather"));
		assertThat(estimatedTexts).anyMatch(t -> t.contains("Paris"));
		assertThat(estimatedTexts).anyMatch(t -> t.contains("22C"));
	}

	@Test
	void tokensEstimatedSavedIsPositiveWhenCoveringToolEvents() {
		CompactionResult result = strategy(2, 0).build()
			.compact(request(events(toolTurn("What's the weather?"), turn("Thanks", "You're welcome"))));

		assertThat(result.tokensEstimatedSaved()).isGreaterThan(0);
	}

	@Test
	void customEventFormatterIsApplied() {
		List<String> estimatedTexts = new ArrayList<>();

		strategy(2, 0).tokenCountEstimator(capturingEstimator(estimatedTexts))
			.eventFormatter(e -> "CUSTOM[" + e.getMessageType() + "]")
			.build()
			.compact(request(userMessages(4)));

		// Every stored event is formatted by the custom formatter; only the new summary
		// turn, which is not an event yet, is measured with the default format.
		assertThat(estimatedTexts).filteredOn(t -> !t.contains(SUMMARY_TEXT) && !t.contains(SHADOW))
			.isNotEmpty()
			.allMatch(t -> t.startsWith("CUSTOM["));
	}

	@Test
	void oversizeLastTurnIsKeptInsteadOfSummarizingEverything() {
		List<SessionEvent> view = List.of(user("u1"), assistant("a1"), user("u2"), assistant("a2"), assistant("a3"),
				assistant("a4"));

		CompactionResult result = strategy(2, 1).build().compact(request(view));

		assertThat(covered(result)).containsExactly("u1", "a1");
		assertThat(kept(result)).containsExactly(SHADOW, SUMMARY_TEXT, "u2", "a2", "a3", "a4");
	}

	@Test
	void singleOversizeTurnSkipsSummarization() {
		List<SessionEvent> view = List.of(user("u1"), assistant("a1"), assistant("a2"), assistant("a3"));

		assertThat(strategy(2, 1).build().compact(request(view)).isUnchanged()).isTrue();
		verifyNoMoreInteractions(this.chatClient);
	}

	@Test
	void systemMessageStoredMidConversationIsPinnedAndNeverSummarized() {
		AtomicReference<String> summarizationPrompt = new AtomicReference<>();
		ChatClient.ChatClientRequestSpec afterUser = mock(ChatClient.ChatClientRequestSpec.class,
				Answers.RETURNS_DEEP_STUBS);
		given(afterUser.call().content()).willReturn(SUMMARY_TEXT);
		given(this.chatClient.prompt().system(anyString()).user(anyString())).willAnswer(invocation -> {
			summarizationPrompt.set(invocation.getArgument(0));
			return afterUser;
		});
		List<SessionEvent> view = List.of(user("u1"), system("Researcher rules"), assistant("a1"), user("u2"),
				assistant("a2"));

		CompactionResult result = strategy(2, 1).build().compact(request(view));

		assertThat(covered(result)).containsExactly("u1", "a1");
		assertThat(kept(result)).containsExactly("Researcher rules", SHADOW, SUMMARY_TEXT, "u2", "a2");
		assertThat(summarizationPrompt.get()).contains("u1", "a1").doesNotContain("Researcher rules");
	}

	@Test
	void latestStoredSystemMessageIsPinnedAndEarlierOneIsCovered() {
		List<SessionEvent> view = List.of(system("Answer in French"), user("u1"), assistant("a1"),
				system("Answer in German"), user("u2"), assistant("a2"));

		CompactionResult result = strategy(2, 1).build().compact(request(view));

		assertThat(kept(result)).containsExactly("Answer in German", SHADOW, SUMMARY_TEXT, "u2", "a2");
		assertThat(covered(result)).containsExactly("Answer in French", "u1", "a1");
	}

	@Test
	void storedSystemMessageIsNeverFedToTheSummarizer() {
		List<SessionEvent> formatted = new ArrayList<>();
		SessionEvent systemEvent = system("Answer in French");
		List<SessionEvent> view = List.of(systemEvent, user("u1"), assistant("a1"), user("u2"), assistant("a2"));

		CompactionResult result = strategy(2, 1).eventFormatter(e -> {
			formatted.add(e);
			return RecursiveSummarizationCompactionStrategy.formatEvent(e);
		}).build().compact(request(view));

		assertThat(kept(result)).containsExactly("Answer in French", SHADOW, SUMMARY_TEXT, "u2", "a2");
		assertThat(formatted).doesNotContain(systemEvent);
	}

	@Test
	void compactionWithoutUserMessageCoversNothing() {
		List<SessionEvent> view = new ArrayList<>();
		for (int i = 1; i <= 6; i++) {
			view.add(assistant("step " + i));
			view.add(assistant("answer " + i));
		}

		assertThat(strategy(4, 2).build().compact(request(view)).isUnchanged()).isTrue();
		verifyNoMoreInteractions(this.chatClient);
	}

	private static TokenCountEstimator capturingEstimator(List<String> sink) {
		return new TokenCountEstimator() {
			@Override
			public int estimate(String text) {
				sink.add(text);
				return text.length();
			}

			@Override
			public int estimate(MediaContent content) {
				return 0;
			}

			@Override
			public int estimate(Iterable<MediaContent> messages) {
				return 0;
			}
		};
	}

}
