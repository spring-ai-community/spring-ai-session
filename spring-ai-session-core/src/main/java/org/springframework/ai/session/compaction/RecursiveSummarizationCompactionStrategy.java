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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.util.Assert;

/**
 * LLM-powered compaction strategy that summarizes older conversation events into a
 * synthetic user+assistant turn using a sliding-window approach.
 *
 * <h3>Algorithm</h3>
 * <ol>
 * <li>Separate synthetic summary events from real conversation events.</li>
 * <li>Keep the last {@code maxEventsToKeep} real events intact (the <em>active
 * window</em>), snapping the cut point back to the nearest turn boundary so a partial
 * turn is never kept.</li>
 * <li>Everything before the active window — plus any prior synthetic summaries — forms
 * the <em>events to summarize</em>.</li>
 * <li>An LLM call condenses them into a rolling summary, optionally including the last
 * {@code overlapSize} events from the active window for continuity.</li>
 * <li>The result is placed as a <em>synthetic summary turn</em>: a pair of synthetic
 * events [{@code USER} shadow prompt, {@code ASSISTANT} summary] followed by the active
 * window. This mirrors the OpenAI Agents SDK approach and ensures the conversation always
 * has a coherent user↔assistant alternation.</li>
 * </ol>
 *
 * <h3>Recursive / rolling behaviour</h3> Any prior synthetic summary produced by a
 * previous compaction pass is fed back to the LLM as context when generating the new
 * summary. This means each summary <em>builds on</em> its predecessors rather than
 * starting from scratch, creating a rolling window of compressed context.
 *
 * <h3>No-op condition</h3> If the total number of real events does not exceed
 * {@code maxEventsToKeep} no LLM call is made and the events are returned unchanged.
 *
 * @author Christian Tzolov
 * @since 2.0.0
 * @see SlidingWindowCompactionStrategy
 */
public final class RecursiveSummarizationCompactionStrategy implements CompactionStrategy {

	private static final Logger logger = LoggerFactory.getLogger(RecursiveSummarizationCompactionStrategy.class);

	/** Default number of recent real events preserved after compaction. */
	public static final int DEFAULT_MAX_EVENTS_TO_KEEP = 10;

	/**
	 * Default number of active-window events fed into the summary prompt for continuity.
	 */
	public static final int DEFAULT_OVERLAP_SIZE = 2;

	private static final String STRATEGY_NAME = "recursive-summarization";

	/**
	 * The synthetic user message that opens each summary turn, modelled after the OpenAI
	 * Agents SDK shadow-prompt pattern.
	 */
	public static final String DEFAULT_SUMMARY_SHADOW_PROMPT = "Summarize the conversation we had so far.";

	private static final String DEFAULT_SYSTEM_PROMPT = """
			You are a conversation summarizer. Your task is to create a concise summary of the \
			conversation history provided. The summary will replace the original events in the \
			context window and must preserve all key information needed to continue the conversation \
			coherently.

			Guidelines:
			- Preserve key facts, decisions, user preferences, and important outcomes.
			- Note any unresolved questions or pending actions.
			- Write in third-person narrative ("The user asked...", "The assistant explained...").
			- Be concise but complete. Omit filler and repetition.
			- If a prior summary is provided, incorporate it naturally — do not repeat it verbatim.
			""";

	private final ChatClient chatClient;

	private final int maxEventsToKeep;

	private final int overlapSize;

	private final String systemPrompt;

	private final String shadowPrompt;

	private final TokenCountEstimator tokenCountEstimator;

	@Nullable private final Consumer<CompactionRequest> onSummarizationFailure;

	private RecursiveSummarizationCompactionStrategy(ChatClient chatClient, int maxEventsToKeep, int overlapSize,
			String systemPrompt, String shadowPrompt, TokenCountEstimator tokenCountEstimator,
			@Nullable Consumer<CompactionRequest> onSummarizationFailure) {
		Assert.notNull(chatClient, "chatClient must not be null");
		Assert.isTrue(maxEventsToKeep > 0, "maxEventsToKeep must be greater than 0");
		Assert.isTrue(overlapSize >= 0, "overlapSize must be >= 0");
		Assert.isTrue(overlapSize < maxEventsToKeep, "overlapSize must be less than maxEventsToKeep");
		Assert.hasText(systemPrompt, "systemPrompt must not be empty");
		Assert.hasText(shadowPrompt, "shadowPrompt must not be empty");
		Assert.notNull(tokenCountEstimator, "tokenCountEstimator must not be null");
		this.chatClient = chatClient;
		this.maxEventsToKeep = maxEventsToKeep;
		this.overlapSize = overlapSize;
		this.systemPrompt = systemPrompt;
		this.shadowPrompt = shadowPrompt;
		this.tokenCountEstimator = tokenCountEstimator;
		this.onSummarizationFailure = onSummarizationFailure;
	}

	@Override
	public CompactionResult compact(CompactionRequest context) {

		Assert.notNull(context, "context must not be null");
		Assert.notNull(context.session(), "session must not be null");

		List<SessionEvent> events = context.events();

		List<SessionEvent> syntheticEvents = events.stream().filter(SessionEvent::isSynthetic).toList();
		List<SessionEvent> realEvents = events.stream().filter(e -> !e.isSynthetic()).toList();

		if (realEvents.size() <= this.maxEventsToKeep) {
			// Nothing to compact — return as-is
			return new CompactionResult(events, List.of(), 0);
		}

		// Raw cut point based on event count alone
		int rawCutIndex = realEvents.size() - this.maxEventsToKeep;

		// Snap forward to the nearest turn start (USER message) so the active window
		// always begins at a turn boundary and is never a partial turn.
		int cutIndex = CompactionUtils.snapToTurnStart(realEvents, rawCutIndex);

		// Split real events: archive the older ones, keep the newest window
		List<SessionEvent> toArchive = realEvents.subList(0, cutIndex);
		List<SessionEvent> activeWindow = realEvents.subList(cutIndex, realEvents.size());

		// Overlap: the first `overlapSize` events from the active window are also fed
		// into the summary prompt so the LLM has continuity context
		List<SessionEvent> overlapEvents = activeWindow.subList(0, Math.min(this.overlapSize, activeWindow.size()));

		// Build the user prompt for the LLM
		String userPrompt = buildSummarizationPrompt(syntheticEvents, toArchive, overlapEvents);

		// Call the LLM
		String summary = this.chatClient.prompt().system(this.systemPrompt).user(userPrompt).call().content();

		if (summary == null || summary.isBlank()) {
			logger.warn(
					"RecursiveSummarizationCompactionStrategy: LLM returned a null or blank summary for session '{}'. "
							+ "Compaction skipped — event history is unchanged.",
					context.session().id());
			if (this.onSummarizationFailure != null) {
				this.onSummarizationFailure.accept(context);
			}
			return new CompactionResult(events, List.of(), 0);
		}

		// Build the compacted event list: synthetic summary turn (user + assistant) +
		// active window. The two-event turn mirrors the OpenAI Agents SDK shadow-prompt
		// pattern so the model always sees a coherent user↔assistant alternation.
		// Both events share the same timestamp so they are treated as an atomic pair.
		String sessionId = context.session().id();
		Instant now = Instant.now();
		List<SessionEvent> summaryTurn = List.of(
				SessionEvent.builder()
					.sessionId(sessionId)
					.timestamp(now)
					.message(new UserMessage(this.shadowPrompt))
					.metadata(SessionEvent.METADATA_SYNTHETIC, true)
					.metadata(SessionEvent.METADATA_COMPACTION_SOURCE, STRATEGY_NAME)
					.build(),
				SessionEvent.builder()
					.sessionId(sessionId)
					.timestamp(now)
					.message(new AssistantMessage(summary))
					.metadata(SessionEvent.METADATA_SYNTHETIC, true)
					.metadata(SessionEvent.METADATA_COMPACTION_SOURCE, STRATEGY_NAME)
					.build());

		List<SessionEvent> compacted = new ArrayList<>();
		compacted.addAll(summaryTurn);
		compacted.addAll(activeWindow);

		// Archived = only the real events that were summarized and removed.
		// Prior synthetic summaries are implicitly replaced by the new summaryTurn above
		// and are therefore NOT included in archivedEvents. This keeps the semantics of
		// archivedEvents consistent with the other strategies, which only report the real
		// events they removed from the session.
		List<SessionEvent> archived = new ArrayList<>(toArchive);

		int tokensArchived = toArchive.stream()
			.map(e -> e.getMessage().getText())
			.filter(t -> t != null)
			.mapToInt(this.tokenCountEstimator::estimate)
			.sum();

		return new CompactionResult(compacted, archived, tokensArchived);
	}

	/**
	 * Builds the user-facing summarization prompt. Includes:
	 * <ol>
	 * <li>Any prior synthetic summary (recursive context).</li>
	 * <li>The events to be archived (the content to summarize).</li>
	 * <li>The overlap events from the active window (for continuity).</li>
	 * </ol>
	 */
	private String buildSummarizationPrompt(List<SessionEvent> priorSummaries, List<SessionEvent> eventsToSummarize,
			List<SessionEvent> overlapEvents) {

		StringBuilder prompt = new StringBuilder();

		if (!priorSummaries.isEmpty()) {
			prompt.append("=== PRIOR SUMMARY ===\n");
			// Exclude synthetic USER shadow prompts — they are structural placeholders,
			// not summary content. Include only ASSISTANT (and legacy SYSTEM) events
			// whose text carries the actual compressed history.
			priorSummaries.stream()
				.filter(e -> e.getMessageType() != MessageType.USER)
				.forEach(e -> prompt.append(e.getMessage().getText()).append("\n"));
			prompt.append("\n");
		}

		prompt.append("=== CONVERSATION TO SUMMARIZE ===\n");
		eventsToSummarize.forEach(e -> prompt.append(formatEvent(e)).append("\n"));

		if (!overlapEvents.isEmpty()) {
			prompt.append("\n=== UPCOMING CONTEXT (do not summarize — for continuity only) ===\n");
			overlapEvents.forEach(e -> prompt.append(formatEvent(e)).append("\n"));
		}

		prompt.append("\nPlease write the summary now:");
		return prompt.toString();
	}

	private static String formatEvent(SessionEvent event) {
		String role = switch (event.getMessageType()) {
			case USER -> "User";
			case ASSISTANT -> "Assistant";
			case SYSTEM -> "System";
			case TOOL -> "Tool";
		};
		String text = event.getMessage().getText();
		return role + ": " + (text != null ? text : "[no text content]");
	}

	// --- Builder ---

	public static Builder builder(ChatClient chatClient) {
		return new Builder(chatClient);
	}

	public static final class Builder {

		private final ChatClient chatClient;

		private int maxEventsToKeep = DEFAULT_MAX_EVENTS_TO_KEEP;

		private int overlapSize = DEFAULT_OVERLAP_SIZE;

		private String systemPrompt = DEFAULT_SYSTEM_PROMPT;

		private String shadowPrompt = DEFAULT_SUMMARY_SHADOW_PROMPT;

		private TokenCountEstimator tokenCountEstimator = new JTokkitTokenCountEstimator();

		@Nullable private Consumer<CompactionRequest> onSummarizationFailure;

		private Builder(ChatClient chatClient) {
			Assert.notNull(chatClient, "chatClient must not be null");
			this.chatClient = chatClient;
		}

		/**
		 * Number of recent real events kept in full after compaction. Older events are
		 * summarized. Default: {@value #DEFAULT_MAX_EVENTS_TO_KEEP}.
		 */
		public Builder maxEventsToKeep(int maxEventsToKeep) {
			Assert.isTrue(maxEventsToKeep > 0, "maxEventsToKeep must be greater than 0");
			this.maxEventsToKeep = maxEventsToKeep;
			return this;
		}

		/**
		 * Number of events from the active window included in the summarization prompt
		 * for continuity. Default: {@value #DEFAULT_OVERLAP_SIZE}.
		 */
		public Builder overlapSize(int overlapSize) {
			Assert.isTrue(overlapSize >= 0, "overlapSize must be >= 0");
			this.overlapSize = overlapSize;
			return this;
		}

		/**
		 * Replaces the default system prompt sent to the summarization LLM.
		 */
		public Builder systemPrompt(String systemPrompt) {
			Assert.hasText(systemPrompt, "systemPrompt must not be empty");
			this.systemPrompt = systemPrompt;
			return this;
		}

		/**
		 * Replaces the synthetic {@code USER} message that opens each summary turn (the
		 * "shadow prompt"). Defaults to {@link #DEFAULT_SUMMARY_SHADOW_PROMPT}. Override
		 * for multilingual applications or domain-specific agents that need a different
		 * framing.
		 */
		public Builder shadowPrompt(String shadowPrompt) {
			Assert.hasText(shadowPrompt, "shadowPrompt must not be empty");
			this.shadowPrompt = shadowPrompt;
			return this;
		}

		/**
		 * Overrides the token estimator used to calculate {@code tokensEstimatedSaved}.
		 * Defaults to {@link JTokkitTokenCountEstimator}.
		 */
		public Builder tokenCountEstimator(TokenCountEstimator tokenCountEstimator) {
			Assert.notNull(tokenCountEstimator, "tokenCountEstimator must not be null");
			this.tokenCountEstimator = tokenCountEstimator;
			return this;
		}

		/**
		 * Registers an optional callback invoked when the LLM returns a null or blank
		 * summary. The callback receives the {@link CompactionRequest} that triggered the
		 * summarization attempt, giving the caller access to the session and its events.
		 * <p>
		 * A {@link org.slf4j.Logger#warn warn}-level log is always emitted on failure
		 * regardless of whether this callback is set.
		 */
		public Builder onSummarizationFailure(Consumer<CompactionRequest> onSummarizationFailure) {
			Assert.notNull(onSummarizationFailure, "onSummarizationFailure must not be null");
			this.onSummarizationFailure = onSummarizationFailure;
			return this;
		}

		public RecursiveSummarizationCompactionStrategy build() {
			if (this.overlapSize >= this.maxEventsToKeep) {
				throw new IllegalArgumentException("overlapSize (" + this.overlapSize
						+ ") must be less than maxEventsToKeep (" + this.maxEventsToKeep + ")");
			}
			return new RecursiveSummarizationCompactionStrategy(this.chatClient, this.maxEventsToKeep, this.overlapSize,
					this.systemPrompt, this.shadowPrompt, this.tokenCountEstimator, this.onSummarizationFailure);
		}

	}

}
