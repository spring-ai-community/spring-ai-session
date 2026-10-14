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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import io.micrometer.context.ContextRegistry;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallDecision;
import org.springframework.ai.model.tool.ToolCallDecisions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.EventType;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.Interaction;
import org.springframework.ai.session.InteractionEvents;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.SessionStatus;
import org.springframework.ai.session.SessionStatusEvents;
import org.springframework.ai.session.StopReason;
import org.springframework.ai.session.ToolConfirmations;
import org.springframework.ai.session.compaction.CompactionRequest;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.CompactionStrategy;
import org.springframework.ai.session.compaction.SlidingWindowCompactionStrategy;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.metadata.ToolMetadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.spy;
import static org.springframework.ai.session.advisor.ScriptedChatModel.describe;

/**
 * {@link SessionTurnAdvisor} around a real {@link ToolCallingAdvisor} loop and the
 * {@link SessionMemoryAdvisor}, driven through a {@link ChatClient} with a scripted model:
 * one turn per call, however the loop ends.
 *
 * @author Christian Tzolov
 */
class SessionTurnAdvisorTests {

	private final ScriptedChatModel model = new ScriptedChatModel();

	private final SessionService sessionService = DefaultSessionService.builder()
		.sessionRepository(InMemorySessionRepository.builder().build())
		.build();

	private final String sessionId = "s1";

	private final ToolCallingManager manager = ToolCallingManager.builder().build();

	private final List<String> ran = new CopyOnWriteArrayList<>();

	@Test
	void aCallRunsATurnAndRecordsItInTheStatusAndTheLog() {
		this.model.then(prompt -> {
			assertThat(status()).isEqualTo(SessionStatus.RUNNING);
			return answer("Hi!");
		});

		say(client(), "Hello");

		assertThat(session().status()).isEqualTo(SessionStatus.IDLE);
		assertThat(session().stopReason()).isEqualTo(StopReason.END_TURN);
		assertThat(statuses()).containsExactly(SessionStatus.RUNNING, SessionStatus.IDLE);
		assertThat(this.sessionService.getActiveMessages(this.sessionId)).extracting(m -> m.getText())
			.containsExactly("Hello", "Hi!");
	}

	@Test
	void aToolLoopRunsAsOneTurnUntilTheFinalReply() {
		this.model.thenCall("c1", "Lisbon").then(prompt -> {
			assertThat(status()).isEqualTo(SessionStatus.RUNNING);
			return answer("Sunny.");
		});

		say(client(), "Weather in Lisbon?");

		assertThat(session().stopReason()).isEqualTo(StopReason.END_TURN);
		assertThat(statuses()).containsExactly(SessionStatus.RUNNING, SessionStatus.IDLE);
	}

	@Test
	void aReturnDirectToolEndsTheTurn() {
		this.model.thenCall("c1", "Lisbon").thenAnswer("Hello again.");
		ToolCallback direct = FunctionToolCallback.builder("get_weather", (City city) -> "Sunny in " + city.city())
			.description("The weather in a city")
			.inputType(City.class)
			.toolMetadata(ToolMetadata.builder().returnDirect(true).build())
			.build();
		ChatClient client = client(direct, ToolCallingAdvisor.builder().build());

		assertThat(say(client, "Weather in Lisbon?")).contains("Sunny in Lisbon");

		assertThat(session().status()).isEqualTo(SessionStatus.IDLE);
		assertThat(session().stopReason()).isEqualTo(StopReason.END_TURN);
		// The next call starts its own turn
		assertThat(say(client, "Hi")).isEqualTo("Hello again.");
	}

	@Test
	void aBreachedToolCallLimitEndsTheTurn() {
		this.model.thenCall("c1", "Lisbon").thenCall("c2", "Porto").thenAnswer("Hello again.");
		ToolCallingAdvisor limited = ToolCallingAdvisor.builder()
			.toolCallingManager(ToolCallingManager.builder().maxTotalToolCalls(1).build())
			.build();
		ChatClient client = client(weather(), limited);

		say(client, "Weather in Lisbon and Porto?");

		assertThat(this.model.prompts()).hasSize(2);
		assertThat(session().status()).isEqualTo(SessionStatus.IDLE);
		assertThat(session().stopReason()).isEqualTo(StopReason.END_TURN);
		assertThat(say(client, "Hi")).isEqualTo("Hello again.");
	}

	@Test
	void aFailedCallEndsTheTurnWithAnError() {
		this.model.then(prompt -> {
			throw new IllegalStateException("model down");
		}).thenAnswer("Back.");
		ChatClient client = client();

		assertThatThrownBy(() -> say(client, "Hello")).hasMessageContaining("model down");

		assertThat(session().status()).isEqualTo(SessionStatus.IDLE);
		assertThat(session().stopReason()).isEqualTo(StopReason.ERROR);
		assertThat(say(client, "Again")).isEqualTo("Back.");
	}

	@Test
	void aFailureInsideTheMemoryAdvisorEndsTheTurnWithAnError() {
		ChatClient client = client();

		assertThatThrownBy(() -> client.prompt()
			.user("Hello")
			.advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId)
				.param(SessionMemoryAdvisor.EVENT_FILTER_CONTEXT_KEY, "not a filter"))
			.call()
			.content()).isInstanceOf(IllegalArgumentException.class);

		assertThat(session().stopReason()).isEqualTo(StopReason.ERROR);
	}

	@Test
	void aSecondConcurrentTurnIsRejectedAndTheRunningOneLeftAlone() {
		ChatClient client = client();
		say(client, "Hello", "Hi!");
		this.sessionService.startTurn(this.sessionId);

		assertThatThrownBy(() -> say(client, "Hello again")).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("could not start a turn: it is RUNNING");

		assertThat(status()).isEqualTo(SessionStatus.RUNNING);
		assertThat(this.model.prompts()).hasSize(1);
	}

	@Test
	void aStreamedCallRunsATurn() {
		this.model.thenAnswer("Hi!");

		List<ChatResponse> chunks = client().prompt()
			.user("Hello")
			.advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId))
			.stream()
			.chatResponse()
			.collectList()
			.block();

		assertThat(chunks).isNotEmpty();
		assertThat(session().stopReason()).isEqualTo(StopReason.END_TURN);
		assertThat(this.sessionService.getActiveMessages(this.sessionId)).hasSize(2);
	}

	@Test
	void aFailedStreamEndsTheTurnWithAnError() {
		this.model.then(prompt -> {
			throw new IllegalStateException("stream down");
		});

		assertThatThrownBy(() -> client().prompt()
			.user("Hello")
			.advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId))
			.stream()
			.content()
			.blockLast()).hasMessageContaining("stream down");

		assertThat(session().status()).isEqualTo(SessionStatus.IDLE);
		assertThat(session().stopReason()).isEqualTo(StopReason.ERROR);
	}

	@Test
	void compactionRunsOnceTheTurnHasEnded() {
		AtomicInteger triggered = new AtomicInteger();
		SessionTurnAdvisor turn = SessionTurnAdvisor.builder(this.sessionService)
			.compactionTrigger(request -> triggered.incrementAndGet() > 0)
			.compactionStrategy(SlidingWindowCompactionStrategy.builder().maxEvents(2).build())
			.build();
		ChatClient client = client(weather(), turn, ToolCallingAdvisor.builder().build());
		say(client, "Hello", "Hi!");
		this.model.thenCall("c1", "Lisbon").thenAnswer("Sunny.");

		say(client, "Weather in Lisbon?");

		// Once per turn, not once per round of the tool loop
		assertThat(triggered).hasValue(2);
		// The window keeps the newest turn whole: the first one is compacted away
		assertThat(describe(new Prompt(this.sessionService.getActiveMessages(this.sessionId))))
			.containsExactly("Weather in Lisbon?", "call:c1", "result:c1", "Sunny.");
	}

	@Test
	void aFailedCompactionDoesNotFailTheCall() {
		SessionTurnAdvisor turn = SessionTurnAdvisor.builder(this.sessionService)
			.compactionTrigger(request -> true)
			.compactionStrategy(request -> {
				throw new IllegalStateException("summarizer unavailable");
			})
			.build();

		assertThat(say(client(weather(), turn, ToolCallingAdvisor.builder().build()), "Hello", "Hi!"))
			.isEqualTo("Hi!");
		assertThat(session().stopReason()).isEqualTo(StopReason.END_TURN);
		assertThat(this.sessionService.getActiveMessages(this.sessionId)).hasSize(2);
	}

	@Test
	void asyncCompactionRunsAfterCallReturns() throws Exception {
		HeldCompaction held = new HeldCompaction();
		SessionTurnAdvisor turn = SessionTurnAdvisor.builder(this.sessionService)
			.compactionTrigger(request -> true)
			.compactionStrategy(held)
			.asyncCompaction(true)
			.build();
		ChatClient client = client(weather(), turn, ToolCallingAdvisor.builder().build());

		// The reply comes back while the compaction is still held
		assertThat(say(client, "Hello", "Hi!")).isEqualTo("Hi!");
		assertThat(held.started.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(turn.isCompacting(this.sessionId)).isTrue();
		held.release.countDown();
		await(() -> !turn.isCompacting(this.sessionId));

		// The next turn's compaction keeps it, the newest turn
		say(client, "Still there?", "Yes.");
		await(() -> describe(new Prompt(this.sessionService.getActiveMessages(this.sessionId)))
			.equals(List.of("Still there?", "Yes.")));
	}

	@Test
	void asyncCompactionThatNewTurnOvertakesRunsAgainAfterIt() throws Exception {
		this.sessionService.create(CreateSessionRequest.builder().id(this.sessionId).build());
		this.sessionService.appendMessage(this.sessionId, new UserMessage("Earlier question"));
		this.sessionService.appendMessage(this.sessionId, new AssistantMessage("Earlier answer"));
		HeldCompaction held = new HeldCompaction();
		SessionTurnAdvisor turn = SessionTurnAdvisor.builder(this.sessionService)
			.compactionTrigger(request -> true)
			.compactionStrategy(held)
			.asyncCompaction(true)
			.build();
		ChatClient client = client(weather(), turn, ToolCallingAdvisor.builder().build());
		say(client, "Hello", "Hi!");
		assertThat(held.started.await(5, TimeUnit.SECONDS)).isTrue();

		// A second turn writes while the first compaction runs: no second compaction
		// starts, and the running one, overtaken, writes nothing
		say(client, "Still there?", "Yes.");
		held.release.countDown();
		await(() -> !turn.isCompacting(this.sessionId));
		assertThat(held.calls).hasValue(1);
		assertThat(this.sessionService.getActiveMessages(this.sessionId)).hasSize(6);

		// The next turn's compaction lands
		say(client, "Bye", "Bye!");
		await(() -> describe(new Prompt(this.sessionService.getActiveMessages(this.sessionId)))
			.equals(List.of("Bye", "Bye!")));
		assertThat(held.calls).hasValue(2);
	}

	@Test
	void asyncCompactionRunsWithCallersContext() throws Exception {
		// Stands in for Spring Security's SecurityContext, which registers the same way
		ThreadLocal<String> caller = new ThreadLocal<>();
		ContextRegistry.getInstance()
			.registerThreadLocalAccessor("test.caller", caller::get, caller::set, caller::remove);
		AtomicReference<String> seen = new AtomicReference<>();
		CountDownLatch compacted = new CountDownLatch(1);
		CompactionStrategy recording = request -> {
			seen.set(caller.get());
			compacted.countDown();
			return CompactionResult.unchanged();
		};
		ChatClient client = client(weather(), SessionTurnAdvisor.builder(this.sessionService)
			.compactionTrigger(request -> true)
			.compactionStrategy(recording)
			.asyncCompaction(true)
			.build(), ToolCallingAdvisor.builder().build());
		try {
			caller.set("alice");
			say(client, "Hello", "Hi!");

			assertThat(compacted.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(seen).hasValue("alice");
		}
		finally {
			caller.remove();
			ContextRegistry.getInstance().removeThreadLocalAccessor("test.caller");
		}
	}

	@Test
	void theCompactionOptionsAreSetTogether() {
		assertThatThrownBy(() -> SessionTurnAdvisor.builder(this.sessionService)
			.compactionStrategy(SlidingWindowCompactionStrategy.builder().build())
			.build()).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void aFailedParkEndsTheTurnWithAnError() {
		SessionService failing = spy(this.sessionService);
		willThrow(new IllegalArgumentException("The store is down")).given(failing).park(any(), any(), any(), any());
		ChatClient client = gated(SessionTurnAdvisor.builder(failing).toolCallingManager(this.manager).build(),
				tool("get_weather", "Sunny in ", confirmed()));
		this.model.thenCall("c1", "Paris");

		assertThatThrownBy(() -> say(client, "Weather in Paris?")).hasMessageContaining("The store is down");

		assertThat(session().status()).isEqualTo(SessionStatus.IDLE);
		assertThat(session().stopReason()).isEqualTo(StopReason.ERROR);
	}

	@Test
	void aResumptionThatFailedAfterACallRanAnswersTheCalls() {
		ToolCallingManager failsAfterRunning = new ToolCallingManager() {

			@Override
			public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
				return SessionTurnAdvisorTests.this.manager.resolveToolDefinitions(chatOptions);
			}

			@Override
			public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
				return SessionTurnAdvisorTests.this.manager.executeToolCalls(prompt, chatResponse);
			}

			@Override
			public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse,
					ToolCallDecisions decisions) {
				SessionTurnAdvisorTests.this.manager.executeToolCalls(prompt, chatResponse, decisions);
				throw new IllegalStateException("The second tool is down");
			}

		};
		ChatClient client = gated(
				SessionTurnAdvisor.builder(this.sessionService).toolCallingManager(failsAfterRunning).build(),
				tool("get_weather", "Sunny in ", confirmed()));
		this.model.thenCall("c1", "Paris");
		say(client, "Weather in Paris?");

		assertThatThrownBy(() -> decide(client, ToolCallDecision.allow("c1"))).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("The second tool is down");

		assertThat(this.ran).containsExactly("get_weather");
		assertThat(session().stopReason()).isEqualTo(StopReason.ERROR);
		// The call is answered with the failure, so deciding again cannot run it twice
		assertThatThrownBy(() -> decide(client, ToolCallDecision.allow("c1")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("not waiting");
		assertThat(describe(new Prompt(this.sessionService.getActiveMessages(this.sessionId))))
			.containsExactly("Weather in Paris?", "call:c1", "result:c1");
		assertThat(toolResults(new Prompt(this.sessionService.getActiveMessages(this.sessionId))))
			.singleElement()
			.asString()
			.contains("The second tool is down");
		// The next turn goes on with a valid history
		assertThat(say(client, "And now?", "Sunny.")).isEqualTo("Sunny.");
		assertThat(this.ran).hasSize(1);
	}

	@Test
	void aStreamedResumptionOfAReturnDirectToolReturnsItsResult() {
		ChatClient client = gated(
				tool("get_weather", "Sunny in ", ToolMetadata.builder().requiresConfirmation(true).returnDirect(true).build()));
		this.model.thenCall("c1", "Paris");
		say(client, "Weather in Paris?");

		String answer = String.join("", client.prompt()
			.advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId)
				.param(SessionTurnAdvisor.TOOL_CALL_DECISIONS_CONTEXT_KEY, ToolCallDecision.allow("c1")))
			.stream()
			.content()
			.collectList()
			.block());

		assertThat(answer).contains("Sunny in Paris");
		assertThat(this.model.prompts()).hasSize(1);
		assertThat(session().stopReason()).isEqualTo(StopReason.END_TURN);
	}

	@Test
	void aBadDecisionsValueCreatesNoSession() {
		ChatClient client = gated(tool("get_weather", "Sunny in ", confirmed()));

		assertThatThrownBy(() -> decide(client, "yes please")).isInstanceOf(IllegalArgumentException.class);

		assertThat(session()).isNull();
	}

	@Test
	void chatClientDefaultMetadataLabelsSessionsTurnAdvisorCreates() {
		ChatClient client = ChatClient.builder(this.model)
			.defaultAdvisors(SessionTurnAdvisor.builder(this.sessionService).build(),
					SessionMemoryAdvisor.builder(this.sessionService).build())
			.defaultAdvisors(a -> a.param(SessionMemoryAdvisor.SESSION_METADATA_CONTEXT_KEY,
					Map.of(SessionAttributes.AGENT_NAME, "support", SessionAttributes.AGENT_VERSION, "2")))
			.build();

		say(client, "Hello", "Hi!");

		assertThat(SessionAttributes.agentName(session())).isEqualTo("support");
		assertThat(SessionAttributes.agentVersion(session())).isEqualTo("2");
	}

	// --- Tool calls that need confirmation ---

	@Test
	void aGatedCallParksTheTurnAndTheDecisionResumesAndEndsIt() {
		ChatClient client = gated(tool("get_weather", "Sunny in ", confirmed()));
		this.model.thenCall("c1", "Paris").thenAnswer("Sunny in Paris.");

		say(client, "Weather in Paris?");

		assertThat(session().status()).isEqualTo(SessionStatus.IDLE);
		assertThat(session().stopReason()).isEqualTo(StopReason.REQUIRES_ACTION);
		assertThat(this.ran).isEmpty();
		// The turn names the reply with the calls and the question it parks on
		List<SessionEvent> log = this.sessionService.getEvents(this.sessionId);
		SessionEvent assistant = log.stream().filter(SessionEvent::hasToolCalls).findFirst().orElseThrow();
		SessionEvent question = log.stream().filter(InteractionEvents::isRequired).findFirst().orElseThrow();
		assertThat(SessionStatusEvents.eventIds(log.get(log.size() - 1))).containsExactly(assistant.getId(),
				question.getId());
		Interaction asked = InteractionEvents.interaction(question);
		assertThat(asked.kind()).isEqualTo(Interaction.KIND_TOOL_CONFIRMATION);
		assertThat(asked.payload()).isEqualTo(Map.of("toolCalls",
				List.of(Map.of("id", "c1", "name", "get_weather", "arguments", "{\"city\":\"Paris\"}")), "awaiting",
				List.of("c1")));
		assertThat(asked.resumeType()).isEqualTo(ToolCallDecisions.class.getName());

		String answer = decide(client, ToolCallDecision.allow("c1"));

		assertThat(answer).isEqualTo("Sunny in Paris.");
		assertThat(this.ran).containsExactly("get_weather");
		assertThat(session().stopReason()).isEqualTo(StopReason.END_TURN);
		assertThat(statuses()).containsExactly(SessionStatus.RUNNING, SessionStatus.IDLE, SessionStatus.RUNNING,
				SessionStatus.IDLE);
		assertThat(describe(this.model.prompts().get(1))).containsExactly("Weather in Paris?", "call:c1", "result:c1");
		// The decisions answer the question the turn parked on
		assertThat(this.sessionService.pendingInteraction(this.sessionId)).isNull();
		assertThat(ToolConfirmations.fromAnswer(InteractionEvents.answer(answered().get(0))))
			.isEqualTo(ToolCallDecisions.of(ToolCallDecision.allow("c1")));
		assertThat(describe(new Prompt(this.sessionService.getActiveMessages(this.sessionId))))
			.containsExactly("Weather in Paris?", "call:c1", "result:c1", "Sunny in Paris.");
	}

	@Test
	void aDeniedCallAnswersTheModelWithItsMessageWithoutRunningTheTool() {
		ChatClient client = gated(tool("get_weather", "Sunny in ", confirmed()));
		this.model.thenCall("c1", "Paris").thenAnswer("No weather then.");
		say(client, "Weather in Paris?");

		String answer = decide(client, ToolCallDecisions.of(ToolCallDecision.deny("c1", "No weather today.")));

		assertThat(answer).isEqualTo("No weather then.");
		assertThat(this.ran).isEmpty();
		assertThat(toolResults(this.model.prompts().get(1))).containsExactly("No weather today.");
	}

	@Test
	void aCallThatDoesNotWaitRunsWithoutADecision() {
		ChatClient client = gated(tool("get_weather", "Sunny in ", confirmed()),
				tool("get_time", "Noon in ", ToolMetadata.builder().build()));
		this.model.then(prompt -> calls(call("c1", "get_weather", "Paris"), call("c2", "get_time", "Paris")))
			.thenAnswer("It is noon.");
		say(client, "Weather and time in Paris?");
		assertThat(ToolConfirmations.awaiting(question())).containsExactly("c1");

		// The gated call still needs its decision
		assertThatThrownBy(() -> decide(client, ToolCallDecision.allow("c2"))).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("missing: [c1]");

		decide(client, List.of(ToolCallDecision.deny("c1", "No.")));

		assertThat(this.ran).containsExactly("get_time");
		assertThat(toolResults(this.model.prompts().get(1))).containsExactly("No.", "\"Noon in Paris\"");
		// Both decisions are in the log, the implicit one as an allow
		assertThat(ToolConfirmations.fromAnswer(InteractionEvents.answer(answered().get(0))).decisions())
			.containsExactly(ToolCallDecision.deny("c1", "No."), ToolCallDecision.allow("c2"));
	}

	@Test
	void decisionsAreCheckedBeforeAnythingIsRecorded() {
		ChatClient client = gated(tool("get_weather", "Sunny in ", confirmed()));

		// Nothing waits
		assertThatThrownBy(() -> decide(client, ToolCallDecision.allow("c1")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("not waiting");

		this.model.thenCall("c1", "Paris");
		say(client, "Weather in Paris?");
		// The wrong call
		assertThatThrownBy(() -> decide(client, List.of(ToolCallDecision.allow("c1"), ToolCallDecision.allow("c9"))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("c9");
		// A bad context value
		assertThatThrownBy(() -> decide(client, "yes please")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining(SessionTurnAdvisor.TOOL_CALL_DECISIONS_CONTEXT_KEY);
		// New user text goes in the next turn
		assertThatThrownBy(() -> client.prompt()
			.user("And Rome?")
			.advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId)
				.param(SessionTurnAdvisor.TOOL_CALL_DECISIONS_CONTEXT_KEY, ToolCallDecision.allow("c1")))
			.call()
			.content()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no new user message");
		// An allowed call needs its tool on the request
		assertThatThrownBy(() -> decide(gated(), ToolCallDecision.allow("c1")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("must carry the tool 'get_weather'");

		// None of that touched the session: still parked, nothing recorded
		assertThat(session().stopReason()).isEqualTo(StopReason.REQUIRES_ACTION);
		assertThat(answered()).isEmpty();
		assertThat(this.ran).isEmpty();
	}

	@Test
	void aSecondResumptionIsRejectedBeforeTheToolRuns() {
		ChatClient client = gated(tool("get_weather", "Sunny in ", confirmed()));
		this.model.thenCall("c1", "Paris").thenAnswer("Sunny in Paris.");
		say(client, "Weather in Paris?");
		// Another node resumed the turn first
		this.sessionService.startTurn(this.sessionId);

		assertThatThrownBy(() -> decide(client, ToolCallDecision.allow("c1"))).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("RUNNING");

		assertThat(status()).isEqualTo(SessionStatus.RUNNING);
		assertThat(this.ran).isEmpty();
		assertThat(answered()).isEmpty();

		// Once the calls are answered, nothing waits any more
		this.sessionService.endTurn(this.sessionId, StopReason.END_TURN);
		decide(client, ToolCallDecision.allow("c1"));
		assertThatThrownBy(() -> decide(client, ToolCallDecision.allow("c1")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("not waiting");
		assertThat(this.ran).containsExactly("get_weather");
	}

	@Test
	void aResumptionThatFailedCanBeDecidedAgain() {
		AtomicBoolean fail = new AtomicBoolean(true);
		ToolCallingManager flaky = new ToolCallingManager() {

			@Override
			public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
				return SessionTurnAdvisorTests.this.manager.resolveToolDefinitions(chatOptions);
			}

			@Override
			public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
				return SessionTurnAdvisorTests.this.manager.executeToolCalls(prompt, chatResponse);
			}

			@Override
			public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse,
					ToolCallDecisions decisions) {
				if (fail.getAndSet(false)) {
					throw new IllegalStateException("The tools are down");
				}
				return SessionTurnAdvisorTests.this.manager.executeToolCalls(prompt, chatResponse, decisions);
			}

		};
		ChatClient client = gated(SessionTurnAdvisor.builder(this.sessionService).toolCallingManager(flaky).build(),
				tool("get_weather", "Sunny in ", confirmed()));
		this.model.thenCall("c1", "Paris").thenAnswer("Sunny in Paris.");
		say(client, "Weather in Paris?");

		assertThatThrownBy(() -> decide(client, ToolCallDecision.allow("c1"))).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("The tools are down");
		assertThat(session().stopReason()).isEqualTo(StopReason.ERROR);

		// The calls are still unanswered in the log
		assertThat(decide(client, ToolCallDecision.allow("c1"))).isEqualTo("Sunny in Paris.");
		assertThat(session().stopReason()).isEqualTo(StopReason.END_TURN);
		assertThat(answered()).hasSize(1);
	}

	@Test
	void aModelWithoutToolCallIdsPausesResumesAndKeepsTheLogPaired() {
		ChatClient client = gated(tool("get_weather", "Sunny in ", confirmed()));
		this.model.then(prompt -> calls(call("", "get_weather", "Paris"))).thenAnswer("Sunny in Paris.");
		say(client, "Weather in Paris?");
		// The decision names the id the loop made up for the call
		String id = ToolConfirmations.awaiting(question()).get(0);
		assertThat(id).isNotBlank();

		assertThat(decide(client, ToolCallDecision.allow(id))).isEqualTo("Sunny in Paris.");

		// The results answer the stored call under its own id
		assertThat(describe(this.model.prompts().get(1))).containsExactly("Weather in Paris?", "call:", "result:");
		assertThat(describe(new Prompt(this.sessionService.getActiveMessages(this.sessionId))))
			.containsExactly("Weather in Paris?", "call:", "result:", "Sunny in Paris.");
	}

	@Test
	void aStreamedPauseParksTheTurn() {
		ChatClient client = gated(tool("get_weather", "Sunny in ", confirmed()));
		this.model.thenCall("c1", "Paris").thenAnswer("Sunny in Paris.");

		client.prompt()
			.user("Weather in Paris?")
			.advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId))
			.stream()
			.content()
			.blockLast();

		assertThat(session().stopReason()).isEqualTo(StopReason.REQUIRES_ACTION);
		assertThat(ToolConfirmations.awaiting(question())).containsExactly("c1");
		String answer = String.join("", client.prompt()
			.advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId)
				.param(SessionTurnAdvisor.TOOL_CALL_DECISIONS_CONTEXT_KEY, ToolCallDecision.allow("c1")))
			.stream()
			.content()
			.collectList()
			.block());
		assertThat(answer).isEqualTo("Sunny in Paris.");
		assertThat(session().stopReason()).isEqualTo(StopReason.END_TURN);
	}

	@Test
	void aReturnDirectToolReturnsItsResultWithoutTheModelOnResumption() {
		ChatClient client = gated(
				tool("get_weather", "Sunny in ", ToolMetadata.builder().requiresConfirmation(true).returnDirect(true).build()));
		this.model.thenCall("c1", "Paris");
		say(client, "Weather in Paris?");

		assertThat(decide(client, ToolCallDecision.allow("c1"))).contains("Sunny in Paris");

		assertThat(this.model.prompts()).hasSize(1);
		assertThat(session().stopReason()).isEqualTo(StopReason.END_TURN);
		assertThat(describe(new Prompt(this.sessionService.getActiveMessages(this.sessionId))))
			.containsExactly("Weather in Paris?", "call:c1", "result:c1");
		// The next turn sends a valid history
		assertThat(say(client, "Thanks", "You are welcome.")).isEqualTo("You are welcome.");
		assertThat(describe(this.model.prompts().get(1))).containsExactly("Weather in Paris?", "call:c1", "result:c1",
				"Thanks");
	}

	@Test
	void anInterruptWhileTheModelAnswersStopsBeforeTheToolsRun() {
		ChatClient client = gated(tool("get_weather", "Sunny in ", ToolMetadata.builder().build()));
		this.model.then(prompt -> {
			this.sessionService.interrupt(this.sessionId, "Stop");
			return calls(call("c1", "get_weather", "Paris"));
		});

		assertThatThrownBy(() -> say(client, "Weather in Paris?")).isInstanceOf(TurnInterruptedException.class);

		assertThat(this.ran).isEmpty();
		assertThat(this.model.prompts()).hasSize(1);
		assertThat(session().stopReason()).isEqualTo(StopReason.INTERRUPTED);
		// The call is answered, so the log stays valid and the next turn's model learns why
		assertThat(toolResults(new Prompt(this.sessionService.getActiveMessages(this.sessionId))))
			.containsExactly(SessionMemoryAdvisor.INTERRUPTED_TOOL_RESULT);
		assertThat(say(client, "Never mind", "OK.")).isEqualTo("OK.");
		assertThat(describe(this.model.prompts().get(1))).containsExactly("Weather in Paris?", "call:c1", "result:c1",
				"Never mind");
	}

	@Test
	void anInterruptWhileAGatedCallIsAskedForStopsBeforeTheTurnParks() {
		ChatClient client = gated(tool("get_weather", "Sunny in ", confirmed()));
		this.model.then(prompt -> {
			this.sessionService.interrupt(this.sessionId, "Stop");
			return calls(call("c1", "get_weather", "Paris"));
		});

		assertThatThrownBy(() -> say(client, "Weather in Paris?")).isInstanceOf(TurnInterruptedException.class);

		assertThat(session().stopReason()).isEqualTo(StopReason.INTERRUPTED);
		assertThatThrownBy(() -> decide(client, ToolCallDecision.allow("c1")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("not waiting");
		assertThat(this.ran).isEmpty();
	}

	@Test
	void anInterruptDuringAToolRunStopsBeforeTheNextModelCall() {
		ToolCallback interrupting = FunctionToolCallback.builder("get_weather", (City city) -> {
			this.ran.add("get_weather");
			this.sessionService.interrupt(this.sessionId, "Stop");
			return "Sunny in " + city.city();
		}).description("The weather in a city").inputType(City.class).build();
		ChatClient client = gated(interrupting);
		this.model.thenCall("c1", "Paris").thenAnswer("Sunny in Paris.");

		assertThatThrownBy(() -> say(client, "Weather in Paris?")).isInstanceOf(TurnInterruptedException.class);

		assertThat(this.ran).containsExactly("get_weather");
		assertThat(this.model.prompts()).hasSize(1);
		assertThat(session().stopReason()).isEqualTo(StopReason.INTERRUPTED);
		// The tool's own result is stored
		assertThat(toolResults(new Prompt(this.sessionService.getActiveMessages(this.sessionId))))
			.containsExactly("\"Sunny in Paris\"");
	}

	@Test
	void anInterruptStopsAStreamedCallBeforeTheToolsRun() {
		ChatClient client = gated(tool("get_weather", "Sunny in ", ToolMetadata.builder().build()));
		this.model.then(prompt -> {
			this.sessionService.interrupt(this.sessionId, "Stop");
			return calls(call("c1", "get_weather", "Paris"));
		});

		assertThatThrownBy(() -> client.prompt()
			.user("Weather in Paris?")
			.advisors(SessionAdvisors.session(this.sessionId))
			.stream()
			.content()
			.blockLast()).isInstanceOf(TurnInterruptedException.class);

		assertThat(this.ran).isEmpty();
		assertThat(session().stopReason()).isEqualTo(StopReason.INTERRUPTED);
		assertThat(toolResults(new Prompt(this.sessionService.getActiveMessages(this.sessionId))))
			.containsExactly(SessionMemoryAdvisor.INTERRUPTED_TOOL_RESULT);
	}

	@Test
	void interruptedTurnNeverEndsTurnThatStartedAfterIt() {
		ChatClient client = gated(tool("get_weather", "Sunny in ", ToolMetadata.builder().build()));
		this.model.then(prompt -> {
			// Stopped from elsewhere, and another request starts its turn at once
			this.sessionService.interrupt(this.sessionId, "Stop");
			this.sessionService.startTurn(this.sessionId, "next-run");
			return calls(call("c1", "get_weather", "Paris"));
		});

		assertThatThrownBy(() -> say(client, "Weather in Paris?")).isInstanceOf(TurnInterruptedException.class);

		// The stopped call ends nothing: the next run's turn still runs
		assertThat(this.ran).isEmpty();
		assertThat(session().status()).isEqualTo(SessionStatus.RUNNING);
		assertThat(session().runId()).isEqualTo("next-run");
	}

	@Test
	void turnRunsUnderCallersRunId() {
		this.model.then(prompt -> {
			assertThat(session().runId()).isEqualTo("run-7");
			return answer("Hi!");
		});

		client().prompt()
			.user("Hello")
			.advisors(SessionAdvisors.session(this.sessionId, "run-7"))
			.call()
			.content();

		assertThat(session().status()).isEqualTo(SessionStatus.IDLE);
		assertThat(session().runId()).isNull();
	}

	@Test
	void theMemoryAdvisorAloneIsNotStoppedByAnEarlierInterrupt() {
		this.sessionService.create(CreateSessionRequest.builder().id(this.sessionId).build());
		this.sessionService.startTurn(this.sessionId);
		this.sessionService.interrupt(this.sessionId, "Stop");
		ChatClient client = ChatClient.builder(this.model)
			.defaultAdvisors(SessionMemoryAdvisor.builder(this.sessionService).build())
			.build();

		assertThat(say(client, "Hello", "Hi!")).isEqualTo("Hi!");
	}

	private ChatClient client() {
		return client(weather(), ToolCallingAdvisor.builder().build());
	}

	private ChatClient client(ToolCallback tool, ToolCallingAdvisor toolCalling) {
		return client(tool, SessionTurnAdvisor.builder(this.sessionService).build(), toolCalling);
	}

	private ChatClient client(ToolCallback tool, Advisor... advisors) {
		return ChatClient.builder(this.model)
			.defaultAdvisors(advisors)
			.defaultAdvisors(SessionMemoryAdvisor.builder(this.sessionService).build())
			.defaultToolCallbacks(tool)
			.build();
	}

	private ChatClient gated(ToolCallback... tools) {
		return gated(SessionTurnAdvisor.builder(this.sessionService).toolCallingManager(this.manager).build(), tools);
	}

	/** The turn advisor and the loop share the manager that decides which calls wait. */
	private ChatClient gated(SessionTurnAdvisor turn, ToolCallback... tools) {
		return ChatClient.builder(this.model)
			.defaultAdvisors(turn, ToolCallingAdvisor.builder().toolCallingManager(this.manager).build(),
					SessionMemoryAdvisor.builder(this.sessionService).build())
			.defaultToolCallbacks(tools)
			.build();
	}

	private String say(ChatClient client, String text) {
		return client.prompt()
			.user(text)
			.advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId))
			.call()
			.content();
	}

	private String say(ChatClient client, String text, String reply) {
		this.model.thenAnswer(reply);
		return say(client, text);
	}

	private String decide(ChatClient client, Object decisions) {
		return client.prompt()
			.advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionId)
				.param(SessionTurnAdvisor.TOOL_CALL_DECISIONS_CONTEXT_KEY, decisions))
			.call()
			.content();
	}

	private ToolCallback tool(String name, String result, ToolMetadata metadata) {
		return FunctionToolCallback.builder(name, (City city) -> {
			this.ran.add(name);
			return result + city.city();
		}).description("A tool about a city").inputType(City.class).toolMetadata(metadata).build();
	}

	private static ToolMetadata confirmed() {
		return ToolMetadata.builder().requiresConfirmation(true).build();
	}

	private static AssistantMessage.ToolCall call(String id, String tool, String city) {
		return new AssistantMessage.ToolCall(id, "function", tool, "{\"city\":\"" + city + "\"}");
	}

	private static ChatResponse calls(AssistantMessage.ToolCall... calls) {
		return new ChatResponse(
				List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(calls)).build())));
	}

	private Interaction question() {
		SessionEvent pending = this.sessionService.pendingInteraction(this.sessionId);
		assertThat(pending).isNotNull();
		return InteractionEvents.interaction(pending);
	}

	private List<SessionEvent> answered() {
		return this.sessionService.getEvents(this.sessionId)
			.stream()
			.filter(InteractionEvents::isAnswered)
			.toList();
	}

	private static List<String> toolResults(Prompt prompt) {
		return prompt.getInstructions()
			.stream()
			.filter(ToolResponseMessage.class::isInstance)
			.map(ToolResponseMessage.class::cast)
			.flatMap(results -> results.getResponses().stream())
			.map(ToolResponseMessage.ToolResponse::responseData)
			.toList();
	}

	private static ToolCallback weather() {
		return FunctionToolCallback.builder("get_weather", (City city) -> "Sunny in " + city.city())
			.description("The weather in a city")
			.inputType(City.class)
			.build();
	}

	private static ChatResponse answer(String text) {
		return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
	}

	private Session session() {
		return this.sessionService.findById(this.sessionId);
	}

	private SessionStatus status() {
		return session().status();
	}

	private List<SessionStatus> statuses() {
		return this.sessionService
			.getEvents(this.sessionId, EventFilter.builder().eventTypes(Set.of(EventType.STATUS)).build())
			.stream()
			.map(SessionStatusEvents::status)
			.toList();
	}

	/** Keeps the newest turn, once released. */
	static final class HeldCompaction implements CompactionStrategy {

		final CountDownLatch started = new CountDownLatch(1);

		final CountDownLatch release = new CountDownLatch(1);

		final AtomicInteger calls = new AtomicInteger();

		@Override
		public CompactionResult compact(CompactionRequest request) {
			this.calls.incrementAndGet();
			this.started.countDown();
			try {
				this.release.await(5, TimeUnit.SECONDS);
				return SlidingWindowCompactionStrategy.builder().maxEvents(2).build().compact(request);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(ex);
			}
		}

	}

	private static void await(BooleanSupplier condition) throws InterruptedException {
		for (int i = 0; i < 100 && !condition.getAsBoolean(); i++) {
			Thread.sleep(50);
		}
		assertThat(condition.getAsBoolean()).isTrue();
	}

	record City(String city) {
	}

}
