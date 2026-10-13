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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import org.springframework.ai.agent.Agent;
import org.springframework.ai.agent.AgentEvent;
import org.springframework.ai.agent.ExecutionContext;
import org.springframework.ai.agent.ExecutionContextPropagation;
import org.springframework.ai.agent.RunException;
import org.springframework.ai.agent.RunIdentification;
import org.springframework.ai.agent.checkpointer.CheckpointerFeature;
import org.springframework.ai.agent.graph.GraphAgent;
import org.springframework.ai.agent.graph.ModelAwareGraphAgent;
import org.springframework.ai.agent.graph.builtins.Graphs;
import org.springframework.ai.agent.graph.builtins.Nodes;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.graph.Graph;
import org.springframework.ai.graph.Node;
import org.springframework.ai.session.AgentSnapshot;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.EventType;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.SessionStatus;
import org.springframework.ai.session.SessionStatusEvents;
import org.springframework.ai.session.SessionTreeEvents;
import org.springframework.ai.session.StopReason;
import org.springframework.ai.session.UsageEvents;
import org.springframework.ai.session.compaction.SlidingWindowCompactionStrategy;
import org.springframework.ai.session.compaction.TurnCountTrigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Tests for {@link SessionFeature}: the run as a turn, the conversation as the log, nested
 * agents as child sessions, and the agent's events as typed log events.
 *
 * @author Christian Tzolov
 */
class SessionFeatureTests {

	private final SessionService sessions = DefaultSessionService.builder()
		.sessionRepository(InMemorySessionRepository.builder().build())
		.maxTreeDepth(3)
		.build();

	@Test
	void aSessionContinuesAcrossRunsAndRecordsTheRunAsATurn() {
		ScriptedChatModel chatModel = new ScriptedChatModel(100, 10).reply("Paris is lovely in October")
			.reply("You mentioned Paris, in October")
			.reply("You did not mention any city");
		Agent<String, String> agent = ModelAwareGraphAgent.builder(Graphs.toolCallLoop(), chatModel)
			.enable(SessionFeature.builder(this.sessions).agent(AgentSnapshot.of("planner", "1", "scripted")).build())
			.build();

		agent.output("I am planning a trip to Paris in October.", new RunIdentification("trip-1", "r1"));
		assertThat(agent.output("Which city and month did I mention?", new RunIdentification("trip-1", "r2")))
			.isEqualTo("You mentioned Paris, in October");

		assertThat(texts(chatModel.lastPrompt().getInstructions())).containsExactly(
				"I am planning a trip to Paris in October.", "Paris is lovely in October",
				"Which city and month did I mention?");
		Session session = this.sessions.findById("trip-1");
		assertThat(SessionAttributes.agent(session)).isEqualTo(AgentSnapshot.of("planner", "1", "scripted"));
		assertThat(session.status()).isEqualTo(SessionStatus.IDLE);
		assertThat(session.stopReason()).isEqualTo(StopReason.END_TURN);
		assertThat(texts(this.sessions.getActiveMessages("trip-1"))).containsExactly(
				"I am planning a trip to Paris in October.", "Paris is lovely in October",
				"Which city and month did I mention?", "You mentioned Paris, in October");
		// The turns name their runs, the model calls their usage, and every step is in the log
		List<SessionEvent> statuses = this.sessions.getEvents("trip-1",
				EventFilter.builder().eventTypes(Set.of(EventType.STATUS)).build());
		assertThat(statuses).extracting(SessionStatusEvents::runId).containsExactly("r1", "r1", "r2", "r2");
		assertThat(this.sessions.usage("trip-1").totalTokens()).isEqualTo(220);
		assertThat(this.sessions.getEvents("trip-1")).extracting(SessionEvent::getType)
			.contains(EventType.NODE, EventType.MODEL_CALL, EventType.USAGE);
		// A completed model call is one USAGE event, with where and how big it was
		SessionEvent usage = this.sessions.getEvents("trip-1", EventFilter.builder().eventTypes(Set.of(EventType.USAGE)).build()).get(0);
		assertThat(usage.getPayload()).containsEntry(UsageEvents.PAYLOAD_PATH, "").containsEntry(UsageEvents.PAYLOAD_MESSAGES, 1);
		assertThat(this.sessions.getEvents("trip-1", EventFilter.builder().eventTypes(Set.of(EventType.MODEL_CALL)).build()))
			.extracting(e -> e.getPayload().get(AgentEventCodec.PAYLOAD_PHASE))
			.containsOnly("started");
		// Another session starts empty
		agent.output("Which city did I mention?", "trip-2");
		assertThat(texts(chatModel.lastPrompt().getInstructions())).containsExactly("Which city did I mention?");
	}

	@Test
	void theRootSessionIsCompactedAfterAnUpdate() {
		ScriptedChatModel chatModel = new ScriptedChatModel().reply("Paris is lovely in October")
			.reply("You mentioned Paris, in October");
		Agent<String, String> agent = ModelAwareGraphAgent.builder(Graphs.toolCallLoop(), chatModel)
			.enable(SessionFeature.builder(this.sessions)
				.compaction(new TurnCountTrigger(1), SlidingWindowCompactionStrategy.builder().maxEvents(2).build())
				.build())
			.build();

		agent.output("I am planning a trip to Paris in October.", "trip-1");
		agent.output("Which city and month did I mention?", "trip-1");

		assertThat(texts(this.sessions.getActiveMessages("trip-1")))
			.containsExactly("Which city and month did I mention?", "You mentioned Paris, in October");
	}

	@Test
	void aFailingCompactionDoesNotFailTheRun() {
		ScriptedChatModel chatModel = new ScriptedChatModel().reply("Paris is lovely in October");
		Agent<String, String> agent = ModelAwareGraphAgent.builder(Graphs.toolCallLoop(), chatModel)
			.enable(SessionFeature.builder(this.sessions)
				.compaction(new TurnCountTrigger(1), request -> {
					throw new IllegalStateException("summarizer down");
				})
				.build())
			.build();

		assertThat(agent.output("I am planning a trip to Paris in October.", "trip-1"))
			.isEqualTo("Paris is lovely in October");

		assertThat(texts(this.sessions.getActiveMessages("trip-1")))
			.containsExactly("I am planning a trip to Paris in October.", "Paris is lovely in October");
		assertThat(this.sessions.findById("trip-1").stopReason()).isEqualTo(StopReason.END_TURN);
	}

	@Test
	void aReattemptOfACrashedRunResumesFromTheLogAndStoresNothingTwice() {
		AtomicInteger replies = new AtomicInteger();
		AtomicBoolean crash = new AtomicBoolean(true);
		SessionFeature feature = SessionFeature.builder(this.sessions).build();
		Agent<String, String> agent = GraphAgent.builder(replyThenPublish(replies, crash))
			.enable(feature)
			.enable(new CheckpointerFeature(feature.checkpointer()))
			.build();
		String session = "s-" + UUID.randomUUID();
		RunIdentification attempt = new RunIdentification(session, "r1");

		assertThatExceptionOfType(RunException.class).isThrownBy(() -> agent.output("hello", attempt))
			.withStackTraceContaining("simulated crash while publishing");
		assertThat(this.sessions.findById(session).stopReason()).isEqualTo(StopReason.ERROR);

		assertThat(agent.output("hello", attempt)).isEqualTo("reply #1 after 1 message(s)");
		assertThat(replies).hasValue(1);
		assertThat(texts(this.sessions.getActiveMessages(session))).containsExactly("hello",
				"reply #1 after 1 message(s)");
		assertThat(this.sessions.getEvents(session, EventFilter.builder().eventTypes(Set.of(EventType.CHECKPOINT)).build()))
			.isNotEmpty();
		assertThat(this.sessions.findById(session).stopReason()).isEqualTo(StopReason.END_TURN);
		// Running the completed run again replays it from its last checkpoint
		assertThat(agent.output("hello", attempt)).isEqualTo("reply #1 after 1 message(s)");
		assertThat(replies).hasValue(1);
	}

	@Test
	void anApplicationsOwnEventIsStoredUnderItsOwnType() {
		Node<String, String> progress = Node.builder("progress")
			.withInputType(String.class)
			.withOutputType(String.class)
			.withAction(ExecutionContext.class, (text, ctx) -> {
				ctx.publish(new ProgressEvent(ctx.getPath(), 42));
				return text;
			})
			.build();
		Graph<String, String> graph = Graph.builder()
			.withInputType(String.class)
			.withOutputType(String.class)
			.build(g -> g.edge(g.start, progress).edge(progress, g.end));
		Agent<String, String> agent = GraphAgent.builder(graph).enable(SessionFeature.builder(this.sessions).build()).build();

		agent.output("go", "s-progress");

		List<SessionEvent> stored = this.sessions.getEvents("s-progress",
				EventFilter.builder().eventTypes(Set.of(EventType.of("AGENT.PROGRESS_EVENT"))).build());
		assertThat(stored).singleElement().satisfies(e -> assertThat(e.getPayload()).containsEntry("step", 42)
			.containsEntry(AgentEventCodec.PAYLOAD_PATH, ""));
	}

	@Test
	void nestedAgentsGetChildSessionsWithMarkersInTheParent() {
		ScriptedChatModel packerModel = new ScriptedChatModel().reply("Hiking boots and a raincoat");
		ScriptedChatModel strangerModel = new ScriptedChatModel().reply("I have no idea");
		Agent<String, String> packer = ModelAwareGraphAgent.builder(Graphs.toolCallLoop(), packerModel).build();
		Agent<String, String> stranger = ModelAwareGraphAgent.builder(Graphs.toolCallLoop(), strangerModel).build();
		Node<String, String> brief = Nodes.contributeMessage("brief", String.class,
				text -> new UserMessage("Trip brief: " + text));
		Node<String, String> pack = Nodes.agent("packer", packer, ExecutionContextPropagation.FORKED);
		Node<String, String> ask = Nodes.agent("stranger", stranger, ExecutionContextPropagation.ISOLATED);
		Graph<String, String> graph = Graph.builder()
			.withInputType(String.class)
			.withOutputType(String.class)
			.build(g -> g.edge(g.start, brief)
				.transformingEdge(brief, pack, text -> "In one sentence, what should I pack?")
				.transformingEdge(pack, ask, text -> "In one sentence, where am I going?")
				.edge(ask, g.end));
		Agent<String, String> parent = GraphAgent.builder(graph)
			.enable(SessionFeature.builder(this.sessions).agent(AgentSnapshot.of("planner")).build())
			.build();

		assertThat(parent.output("Paris, one week in October, hiking on the weekend.", "plan-1")).isEqualTo("I have no idea");

		// The parent keeps its own conversation and the markers of its children
		assertThat(texts(this.sessions.getActiveMessages("plan-1")))
			.containsExactly("Trip brief: Paris, one week in October, hiking on the weekend.");
		List<SessionEvent> markers = this.sessions.getEvents("plan-1", EventFilter.builder()
			.eventTypes(Set.of(EventType.CHILD_CREATED, EventType.MESSAGE_SENT, EventType.MESSAGE_RECEIVED))
			.build());
		assertThat(markers).extracting(SessionEvent::getType)
			.containsExactly(EventType.CHILD_CREATED, EventType.MESSAGE_SENT, EventType.MESSAGE_RECEIVED,
					EventType.CHILD_CREATED, EventType.MESSAGE_SENT, EventType.MESSAGE_RECEIVED);
		assertThat(markers.get(1).getMessage().getText()).isEqualTo("In one sentence, what should I pack?");
		assertThat(markers.get(2).getMessage().getText()).isEqualTo("Hiking boots and a raincoat");
		// The children are sessions of the tree, self-contained, with their agent's name
		List<Session> children = this.sessions.findChildren("plan-1");
		assertThat(children).extracting(Session::id).containsExactly("plan-1/packer", "plan-1/stranger");
		assertThat(SessionAttributes.agentName(children.get(0))).isEqualTo("packer");
		assertThat(children.get(0).metadata()).containsEntry(SessionFeature.INHERITED_MESSAGES, 1);
		assertThat(texts(this.sessions.getActiveMessages("plan-1/packer"))).containsExactly(
				"Trip brief: Paris, one week in October, hiking on the weekend.",
				"In one sentence, what should I pack?", "Hiking boots and a raincoat");
		assertThat(children.get(1).metadata()).containsEntry(SessionFeature.INHERITED_MESSAGES, 0);
		assertThat(texts(this.sessions.getActiveMessages("plan-1/stranger")))
			.containsExactly("In one sentence, where am I going?", "I have no idea");
		assertThat(this.sessions.getEvents("plan-1/packer")).extracting(SessionEvent::getType)
			.contains(EventType.NODE, EventType.MODEL_CALL);
	}

	@Test
	void aRunOnASessionAnotherRunHoldsFailsWithoutTouchingThatTurn() {
		ScriptedChatModel chatModel = new ScriptedChatModel().reply("hello");
		Agent<String, String> agent = ModelAwareGraphAgent.builder(Graphs.toolCallLoop(), chatModel)
			.enable(SessionFeature.builder(this.sessions).build())
			.build();
		this.sessions.create(org.springframework.ai.session.CreateSessionRequest.builder().id("held").build());
		this.sessions.startTurn("held", "other-run");

		// The runtime lets a listener's failure out as it is
		assertThatExceptionOfType(IllegalStateException.class)
			.isThrownBy(() -> agent.output("hi", new RunIdentification("held", "mine")))
			.withMessageContaining("could not start a turn");

		// The other run's turn is still running; nothing of this run was recorded
		assertThat(this.sessions.findById("held").status()).isEqualTo(SessionStatus.RUNNING);
		List<SessionEvent> statuses = this.sessions.getEvents("held",
				EventFilter.builder().eventTypes(Set.of(EventType.STATUS)).build());
		assertThat(statuses).extracting(SessionStatusEvents::runId).containsExactly("other-run");
		assertThat(this.sessions.getActiveMessages("held")).isEmpty();
	}

	@Test
	void aCancelledRunLeavesAnInterruptedTurn() {
		Node<String, String> cancelled = Node.builder("cancelled")
			.withInputType(String.class)
			.withOutputType(String.class)
			.withAction(text -> {
				throw new java.util.concurrent.CancellationException("Agent run cancelled");
			})
			.build();
		Graph<String, String> graph = Graph.builder()
			.withInputType(String.class)
			.withOutputType(String.class)
			.build(g -> g.edge(g.start, cancelled).edge(cancelled, g.end));
		Agent<String, String> agent = GraphAgent.builder(graph).enable(SessionFeature.builder(this.sessions).build()).build();

		assertThatExceptionOfType(RunException.class).isThrownBy(() -> agent.output("go", "s-cancel"));

		Session session = this.sessions.findById("s-cancel");
		assertThat(session.stopReason()).isEqualTo(StopReason.INTERRUPTED);
		assertThat(this.sessions.getEvents("s-cancel")).extracting(SessionEvent::getType).contains(EventType.INTERRUPT);
	}

	private static Graph<String, String> replyThenPublish(AtomicInteger replies, AtomicBoolean crash) {
		Node<String, String> note = Nodes.contributeMessage("note", String.class, UserMessage::new);
		Node<String, String> reply = Node.builder("reply")
			.withInputType(String.class)
			.withOutputType(String.class)
			.withAction(ExecutionContext.class, (text, ctx) -> {
				int seen = ctx.getConversation().getMessages().size();
				String answer = "reply #" + replies.incrementAndGet() + " after " + seen + " message(s)";
				ctx.getConversation().addMessages(List.of(new AssistantMessage(answer)));
				return answer;
			})
			.build();
		Node<String, String> publish = Node.builder("publish")
			.withInputType(String.class)
			.withOutputType(String.class)
			.withAction(text -> {
				if (crash.getAndSet(false)) {
					throw new IllegalStateException("simulated crash while publishing");
				}
				return text;
			})
			.build();
		return Graph.builder()
			.withInputType(String.class)
			.withOutputType(String.class)
			.build(g -> g.edge(g.start, note).edge(note, reply).edge(reply, publish).edge(publish, g.end));
	}

	static List<String> texts(List<Message> messages) {
		return messages.stream().map(Message::getText).toList();
	}

	record ProgressEvent(String path, int step) implements AgentEvent {
	}

}
