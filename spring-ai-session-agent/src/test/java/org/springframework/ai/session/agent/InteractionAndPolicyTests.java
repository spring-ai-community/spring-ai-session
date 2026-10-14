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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import org.springframework.ai.agent.Agent;
import org.springframework.ai.agent.InteractionRequiredException;
import org.springframework.ai.agent.RunException;
import org.springframework.ai.agent.RunIdentification;
import org.springframework.ai.agent.checkpointer.CheckpointerFeature;
import org.springframework.ai.agent.graph.GraphAgent;
import org.springframework.ai.agent.graph.ModelAwareGraphAgent;
import org.springframework.ai.agent.graph.builtins.Graphs;
import org.springframework.ai.agent.graph.builtins.Nodes;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.graph.Graph;
import org.springframework.ai.graph.Node;
import org.springframework.ai.model.tool.ToolCallConfirmationPolicy;
import org.springframework.ai.model.tool.ToolCallDecision;
import org.springframework.ai.session.Budget;
import org.springframework.ai.session.BudgetPolicy;
import org.springframework.ai.session.BudgetReachedException;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.EventType;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.Interaction;
import org.springframework.ai.session.InteractionEvents;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.SessionStatus;
import org.springframework.ai.session.SessionStatusEvents;
import org.springframework.ai.session.StopReason;
import org.springframework.ai.session.ToolConfirmations;
import org.springframework.ai.tool.annotation.Tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Tests for an interrupted run parked in the session and resumed from it, and for the
 * {@link BudgetFeature} and {@link ToolConfirmationFeature}.
 *
 * @author Christian Tzolov
 */
class InteractionAndPolicyTests {

	private final SessionService sessions = DefaultSessionService.builder()
		.sessionRepository(InMemorySessionRepository.builder().build())
		.build();

	@Test
	void anInterruptedRunIsParkedInTheSessionAndResumedFromItByAnotherAgentInstance() {
		AtomicInteger drafts = new AtomicInteger();
		InteractionRequiredException interrupted = assertThatExceptionOfType(InteractionRequiredException.class)
			.isThrownBy(() -> publisher(drafts).output("spring", new RunIdentification("poem-1", "r1")))
			.actual();
		assertThat(interrupted.getPayload()).isEqualTo("Publish 'A poem about spring'?");

		Session parked = this.sessions.findById("poem-1");
		assertThat(parked.status()).isEqualTo(SessionStatus.IDLE);
		assertThat(parked.stopReason()).isEqualTo(StopReason.REQUIRES_ACTION);
		List<SessionEvent> log = this.sessions.getEvents("poem-1");
		SessionEvent question = log.stream().filter(InteractionEvents::isRequired).findFirst().orElseThrow();
		Interaction interaction = InteractionEvents.interaction(question);
		assertThat(interaction.path()).isEqualTo("approve");
		assertThat(interaction.payload()).isEqualTo("Publish 'A poem about spring'?");
		assertThat(SessionStatusEvents.eventIds(log.get(log.size() - 1))).containsExactly(question.getId());

		// Another agent instance, as on another node after a restart, resumes from the session
		Approval approval = publisher(drafts).resumeOutput("spring", interrupted.getRunId(), new Approval(true, "Lovely"));

		assertThat(approval).isEqualTo(new Approval(true, "Lovely"));
		assertThat(drafts).hasValue(1);
		List<SessionEvent> interactions = this.sessions.getEvents("poem-1",
				EventFilter.builder().eventTypes(Set.of(EventType.INTERACTION)).build());
		assertThat(interactions).hasSize(2);
		assertThat(InteractionEvents.requiredEventId(interactions.get(1))).isEqualTo(question.getId());
		assertThat(InteractionEvents.answer(interactions.get(1))).isEqualTo(Map.of("approved", true, "comment", "Lovely"));
		assertThat(this.sessions.findById("poem-1").stopReason()).isEqualTo(StopReason.END_TURN);
	}

	@Test
	void aRunIsRefusedOnceTheTreeHasReachedItsBudget() {
		ScriptedChatModel chatModel = new ScriptedChatModel(100, 10).reply("one").reply("two");
		Agent<String, String> agent = ModelAwareGraphAgent.builder(Graphs.toolCallLoop(), chatModel)
			.enable(SessionFeature.builder(this.sessions).build())
			.enable(new BudgetFeature(this.sessions, BudgetPolicy.fixed(Budget.ofTotalTokens(100))))
			.build();

		assertThat(agent.output("first", "b-1")).isEqualTo("one");
		assertThat(this.sessions.usage("b-1").totalTokens()).isEqualTo(110);
		assertThatExceptionOfType(RunException.class).isThrownBy(() -> agent.output("second", "b-1"))
			.withRootCauseInstanceOf(BudgetReachedException.class);
		assertThat(this.sessions.findById("b-1").stopReason()).isEqualTo(StopReason.BUDGET_REACHED);
	}

	@Test
	void aGatedToolCallParksTheRunAndRunsOnceConfirmed() {
		ScriptedChatModel chatModel = new ScriptedChatModel().replyWithToolCall("getWeather", "{\"city\":\"Paris\"}")
			.reply("It is sunny in Paris");
		AtomicInteger calls = new AtomicInteger();
		InteractionRequiredException interrupted = assertThatExceptionOfType(InteractionRequiredException.class)
			.isThrownBy(() -> weatherAgent(chatModel, calls).output("Weather in Paris?", new RunIdentification("w-1", "r1")))
			.actual();
		assertThat(interrupted.getPayload()).isEqualTo(new ToolConfirmationFeature.PendingToolCall("getWeather", "{\"city\":\"Paris\"}"));
		assertThat(calls).hasValue(0);
		assertThat(this.sessions.findById("w-1").stopReason()).isEqualTo(StopReason.REQUIRES_ACTION);

		String answer = weatherAgent(chatModel, calls).resumeOutput("Weather in Paris?", interrupted.getRunId(),
				ToolCallDecision.allow("getWeather"));

		assertThat(answer).isEqualTo("It is sunny in Paris");
		assertThat(calls).hasValue(1);
		// The decision answers the question the run parked on, a tool confirmation
		List<SessionEvent> interactions = this.sessions.getEvents("w-1",
				EventFilter.builder().eventTypes(Set.of(EventType.INTERACTION)).build());
		assertThat(interactions).hasSize(2);
		Interaction question = InteractionEvents.interaction(interactions.get(0));
		assertThat(question.kind()).isEqualTo(Interaction.KIND_TOOL_CONFIRMATION);
		assertThat(ToolConfirmations.awaiting(question)).containsExactly("getWeather");
		assertThat(ToolConfirmations.reply(question).getToolCalls()).extracting(AssistantMessage.ToolCall::arguments)
			.containsExactly("{\"city\":\"Paris\"}");
		assertThat(ToolConfirmations.fromAnswer(InteractionEvents.answer(interactions.get(1))).decisions())
			.containsExactly(ToolCallDecision.allow("getWeather"));
		// The gated attempt reads as a started then failed call (the interruption unwinds the
		// tool call), the confirmed one as started then completed
		assertThat(this.sessions.getEvents("w-1", EventFilter.builder().eventTypes(Set.of(EventType.TOOL_CALL)).build()))
			.extracting(e -> e.getPayload().get(AgentEventCodec.PAYLOAD_PHASE))
			.containsExactly("started", "failed", "started", "completed");
		assertThat(this.sessions.findById("w-1").stopReason()).isEqualTo(StopReason.END_TURN);
	}

	private Agent<String, Approval> publisher(AtomicInteger drafts) {
		Node<String, String> draft = Node.builder("draft")
			.withInputType(String.class)
			.withOutputType(String.class)
			.withAction(topic -> {
				drafts.incrementAndGet();
				return "A poem about " + topic;
			})
			.build();
		Node<String, Approval> approve = Nodes.interrupt("approve", String.class, Approval.class,
				text -> "Publish '%s'?".formatted(text));
		Graph<String, Approval> graph = Graph.builder()
			.withInputType(String.class)
			.withOutputType(Approval.class)
			.build(g -> g.edge(g.start, draft).edge(draft, approve).edge(approve, g.end));
		SessionFeature feature = SessionFeature.builder(this.sessions).build();
		return GraphAgent.builder(graph).enable(feature).enable(new CheckpointerFeature(feature.checkpointer())).build();
	}

	private Agent<String, String> weatherAgent(ScriptedChatModel chatModel, AtomicInteger calls) {
		SessionFeature feature = SessionFeature.builder(this.sessions).build();
		return ModelAwareGraphAgent.builder(Graphs.toolCallLoop(), chatModel)
			.tools(new WeatherTools(calls))
			.enable(feature)
			.enable(new CheckpointerFeature(feature.checkpointer()))
			.enable(new ToolConfirmationFeature(ToolCallConfirmationPolicy.forTools("getWeather")))
			.build();
	}

	record Approval(boolean approved, String comment) {
	}

	static class WeatherTools {

		private final AtomicInteger calls;

		WeatherTools(AtomicInteger calls) {
			this.calls = calls;
		}

		@Tool(description = "The weather in a city")
		String getWeather(String city) {
			this.calls.incrementAndGet();
			return "Sunny in " + city;
		}

	}

}
