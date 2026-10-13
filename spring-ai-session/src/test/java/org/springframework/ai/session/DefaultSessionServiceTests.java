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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import reactor.core.Disposable;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.compaction.CompactionBoundary;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.CompactionStrategy;
import org.springframework.ai.session.compaction.SlidingWindowCompactionStrategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Tests for {@link DefaultSessionService}.
 */
class DefaultSessionServiceTests {

	private SessionService service;

	@BeforeEach
	void setUp() {
		this.service = DefaultSessionService.builder()
			.sessionRepository(InMemorySessionRepository.builder().build())
			.build();
	}

	@Test
	void storingASystemMessageIsRejectedByDefault() {
		Session session = this.service.create(CreateSessionRequest.builder().metadata(SessionAttributes.PRINCIPAL_NAME, "user-1").build());

		assertThatIllegalArgumentException()
			.isThrownBy(() -> this.service.appendMessage(session.id(), new SystemMessage("Answer in French.")))
			.withMessageContaining(session.id())
			.withMessageContaining("allowSystemMessages(true)")
			.withMessageContaining("spring.ai.session.allow-system-messages=true");
		assertThat(this.service.getEvents(session.id())).isEmpty();
	}

	@Test
	void storingASystemMessageIsAllowedWhenEnabled() {
		SessionService permissive = DefaultSessionService.builder()
			.sessionRepository(InMemorySessionRepository.builder().build())
			.allowSystemMessages(true)
			.build();
		Session session = permissive.create(CreateSessionRequest.builder().metadata(SessionAttributes.PRINCIPAL_NAME, "user-1").build());

		permissive.appendMessage(session.id(), new SystemMessage("Answer in French."));

		assertThat(permissive.getMessages(session.id())).extracting(Message::getText)
			.containsExactly("Answer in French.");
	}

	@Test
	void conversationMessagesAreUnaffectedByTheSystemMessageRule() {
		Session session = this.service.create(CreateSessionRequest.builder().metadata(SessionAttributes.PRINCIPAL_NAME, "user-1").build());

		this.service.appendMessage(session.id(), new UserMessage("hi"));
		this.service.appendMessage(session.id(), new AssistantMessage("hello"));
		this.service.appendMessage(session.id(), ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "tool", "ok")))
			.build());

		assertThat(this.service.getEvents(session.id())).hasSize(3);
	}

	@Test
	void createReturnsSession() {
		Session session = this.service.create(CreateSessionRequest.builder().metadata(SessionAttributes.PRINCIPAL_NAME, "user-1").build());

		assertThat(session).isNotNull();
		assertThat(session.id()).isNotBlank();
		assertThat(SessionAttributes.principalName(session)).isEqualTo("user-1");
		assertThat(this.service.getEvents(session.id())).isEmpty();
	}

	@Test
	void createAppliesDefaultTimeToLiveWhenRequestHasNone() {
		// Builder default is 60 days.
		Instant before = Instant.now();
		Session session = this.service.create(CreateSessionRequest.builder().metadata(SessionAttributes.PRINCIPAL_NAME, "user-1").build());

		assertThat(session.expiresAt()).isBetween(before.plus(Duration.ofDays(60)).minusSeconds(5),
				Instant.now().plus(Duration.ofDays(60)).plusSeconds(5));
	}

	@Test
	void createHonoursConfiguredDefaultTimeToLive() {
		SessionService customService = DefaultSessionService.builder()
			.sessionRepository(InMemorySessionRepository.builder().build())
			.defaultTimeToLive(Duration.ofHours(2))
			.build();

		Instant before = Instant.now();
		Session session = customService.create(CreateSessionRequest.builder().metadata(SessionAttributes.PRINCIPAL_NAME, "user-1").build());

		assertThat(session.expiresAt()).isBetween(before.plus(Duration.ofHours(2)).minusSeconds(5),
				Instant.now().plus(Duration.ofHours(2)).plusSeconds(5));
	}

	@Test
	void createPrefersRequestTimeToLiveOverDefault() {
		Instant before = Instant.now();
		Session session = this.service
			.create(CreateSessionRequest.builder().metadata(SessionAttributes.PRINCIPAL_NAME, "user-1").timeToLive(Duration.ofMinutes(30)).build());

		assertThat(session.expiresAt()).isBefore(before.plus(Duration.ofHours(1)));
	}

	@Test
	void createWithExistingIdIsRejectedAndDoesNotReassignThePrincipal() {
		Session original = this.service.create(CreateSessionRequest.builder().id("fixed-id").metadata(SessionAttributes.PRINCIPAL_NAME, "alice").build());

		assertThatIllegalStateException().isThrownBy(() -> this.service.create(CreateSessionRequest.builder().id("fixed-id").metadata(SessionAttributes.PRINCIPAL_NAME, "mallory").build()))
			.withMessageContaining("already exists");

		assertThat(SessionAttributes.principalName(this.service.findById(original.id()))).isEqualTo("alice");
	}

	@Test
	void concurrentCreatesOfSameIdHaveExactlyOneWinner() throws Exception {
		int callers = 8;
		ExecutorService executor = Executors.newFixedThreadPool(callers);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<Session>> results = new ArrayList<>();
			for (int i = 0; i < callers; i++) {
				String userId = "user-" + i;
				results.add(executor.submit(() -> {
					start.await();
					return this.service.create(CreateSessionRequest.builder().id("contended").metadata(SessionAttributes.PRINCIPAL_NAME, userId).build());
				}));
			}
			start.countDown();
			List<Session> winners = new ArrayList<>();
			int rejected = 0;
			for (Future<Session> result : results) {
				try {
					winners.add(result.get(30, TimeUnit.SECONDS));
				}
				catch (ExecutionException ex) {
					assertThat(ex.getCause()).isInstanceOf(IllegalStateException.class);
					rejected++;
				}
			}
			assertThat(winners).hasSize(1);
			assertThat(rejected).isEqualTo(callers - 1);
			assertThat(SessionAttributes.principalName(this.service.findById("contended"))).isEqualTo(SessionAttributes.principalName(winners.get(0)));
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	void appendMessageThenGetMessagesReturnsThatMessage() {
		Session session = this.service.create(CreateSessionRequest.builder().metadata(SessionAttributes.PRINCIPAL_NAME, "user-1").build());

		this.service.appendMessage(session.id(), new UserMessage("hello"));

		List<Message> messages = this.service.getMessages(session.id());
		assertThat(messages).hasSize(1);
		assertThat(messages.get(0).getText()).isEqualTo("hello");
	}

	@Test
	void appendMultipleMessagesPreservesOrder() {
		Session session = this.service.create(CreateSessionRequest.builder().metadata(SessionAttributes.PRINCIPAL_NAME, "user-1").build());

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
		Session session = this.service.create(CreateSessionRequest.builder().metadata(SessionAttributes.PRINCIPAL_NAME, "user-1").build());

		this.service.delete(session.id());

		assertThat(this.service.findById(session.id())).isNull();
	}

	@Test
	void theScopeResolverFillsTheAttributesARequestLeftOut() {
		SessionService scoped = DefaultSessionService.builder()
			.sessionRepository(InMemorySessionRepository.builder().build())
			.scopeResolver(SessionScopeResolver.fixed(Map.of(SessionAttributes.PRINCIPAL_NAME, "alice", "tenant", "acme")))
			.build();

		Session resolved = scoped.create(CreateSessionRequest.builder().metadata("agent", "support").build());
		Session explicit = scoped.create(CreateSessionRequest.builder().metadata("tenant", "globex").build());

		assertThat(resolved.metadata()).containsEntry(SessionAttributes.PRINCIPAL_NAME, "alice")
			.containsEntry("tenant", "acme")
			.containsEntry("agent", "support");
		// An explicit request attribute wins over the resolver
		assertThat(explicit.metadata()).containsEntry(SessionAttributes.PRINCIPAL_NAME, "alice")
			.containsEntry("tenant", "globex");
		assertThat(scoped.findByIndex("tenant", "acme")).extracting(Session::id).containsExactly(resolved.id());
	}

	@Test
	void withoutAResolverASessionHasOnlyTheRequestsMetadata() {
		Session session = this.service.create(CreateSessionRequest.builder().build());

		assertThat(session.metadata()).isEmpty();
		assertThat(SessionAttributes.principalName(session)).isNull();
	}

	// --- Session tree ---

	private Session root() {
		return this.service.create(CreateSessionRequest.builder()
			.metadata(SessionAttributes.PRINCIPAL_NAME, "alice")
			.metadata("tenant", "acme")
			.timeToLive(Duration.ofHours(3))
			.build());
	}

	@Test
	void createChildLinksTheChildToItsParentAndRootAndInheritsScopeAndExpiry() {
		Session root = root();

		Session child = this.service.createChild(root.id(), "researcher");

		assertThat(child.parentSessionId()).isEqualTo(root.id());
		assertThat(child.rootSessionId()).isEqualTo(root.id());
		assertThat(SessionAttributes.agentName(child)).isEqualTo("researcher");
		assertThat(child.isChild()).isTrue();
		assertThat(root.isRoot()).isTrue();
		assertThat(root.rootSessionId()).isEqualTo(root.id());
		// The links are fields, not metadata
		assertThat(child.metadata()).doesNotContainKeys("parentSessionId", "rootSessionId");
		assertThat(SessionAttributes.principalName(child)).isEqualTo("alice");
		assertThat(child.expiresAt()).isEqualTo(root.expiresAt());
		// Only the inherited attributes come from the parent
		assertThat(child.metadata()).doesNotContainKey("tenant");
		assertThat(this.service.findById(child.id())).isNotNull();
	}

	@Test
	void createChildHonoursTheRequestsIdAndMetadata() {
		Session root = root();

		Session child = this.service.createChild(root.id(), CreateSessionRequest.builder()
			.id(root.id() + ":researcher")
			.metadata(SessionAttributes.AGENT_NAME, "researcher")
			.metadata("task", "cite sources")
			.metadata(SessionAttributes.PRINCIPAL_NAME, "alice")
			.build());

		assertThat(child.id()).isEqualTo(root.id() + ":researcher");
		assertThat(child.metadata()).containsEntry("task", "cite sources");
		assertThatIllegalStateException().isThrownBy(() -> this.service.createChild(root.id(),
				CreateSessionRequest.builder().id(root.id() + ":researcher").build()));
	}

	@Test
	void createChildRecordsAMarkerOnTheParentsLog() {
		Session root = root();

		Session child = this.service.createChild(root.id(), "researcher");

		List<SessionEvent> markers = this.service.getEvents(root.id(),
				EventFilter.builder().eventTypes(Set.of(EventType.CHILD_CREATED)).build());
		assertThat(markers).hasSize(1);
		assertThat(markers.get(0).getPayload()).containsEntry(SessionTreeEvents.PAYLOAD_SESSION_ID, child.id())
			.containsEntry(SessionTreeEvents.PAYLOAD_AGENT_NAME, "researcher");
		// The marker is not conversation: the active view ignores it
		assertThat(this.service.getActiveMessages(root.id())).isEmpty();
		assertThat(this.service.getEvents(child.id())).isEmpty();
	}

	@Test
	void createChildRejectsADivergingInheritedAttributeOrExpiry() {
		Session root = root();

		assertThatIllegalArgumentException().isThrownBy(() -> this.service.createChild(root.id(),
				CreateSessionRequest.builder().metadata(SessionAttributes.PRINCIPAL_NAME, "mallory").build()))
			.withMessageContaining(SessionAttributes.PRINCIPAL_NAME);
		assertThatIllegalArgumentException().isThrownBy(() -> this.service.createChild(root.id(),
				CreateSessionRequest.builder().timeToLive(Duration.ofMinutes(1)).build()))
			.withMessageContaining("timeToLive");
		assertThat(this.service.findChildren(root.id())).isEmpty();
	}

	@Test
	void createChildRejectsAnUnknownParentAndTheDepthLimit() {
		Session root = root();
		Session child = this.service.createChild(root.id(), "researcher");

		assertThatIllegalArgumentException().isThrownBy(() -> this.service.createChild("missing", "x"))
			.withMessageContaining("not found");
		// Default depth 1: a child of a child is rejected
		assertThatIllegalArgumentException().isThrownBy(() -> this.service.createChild(child.id(), "deeper"))
			.withMessageContaining("depth");

		SessionService deeper = DefaultSessionService.builder()
			.sessionRepository(InMemorySessionRepository.builder().build())
			.maxTreeDepth(2)
			.build();
		Session r = deeper.create(CreateSessionRequest.builder().build());
		Session t1 = deeper.createChild(r.id(), "one");
		Session t2 = deeper.createChild(t1.id(), "two");
		assertThat(t2.parentSessionId()).isEqualTo(t1.id());
		assertThat(t2.rootSessionId()).isEqualTo(r.id());
		assertThat(deeper.findTree(r.id())).extracting(Session::id).containsExactly(r.id(), t1.id(), t2.id());
		assertThatIllegalArgumentException().isThrownBy(() -> deeper.createChild(t2.id(), "three"));
	}

	@Test
	void inheritedAttributesCanBeExtended() {
		SessionService tenantAware = DefaultSessionService.builder()
			.sessionRepository(InMemorySessionRepository.builder().build())
			.inheritedAttributes(Set.of("tenant"))
			.build();
		Session root = tenantAware.create(CreateSessionRequest.builder()
			.metadata(SessionAttributes.PRINCIPAL_NAME, "alice")
			.metadata("tenant", "acme")
			.build());

		Session child = tenantAware.createChild(root.id(), "researcher");

		assertThat(child.metadata()).containsEntry("tenant", "acme")
			.containsEntry(SessionAttributes.PRINCIPAL_NAME, "alice");
	}

	@Test
	void findChildrenListsTheDirectChildren() {
		Session root = root();
		Session first = this.service.createChild(root.id(), "a");
		Session second = this.service.createChild(root.id(), "b");

		// Oldest first is the repository's order (tested in the contract kit with explicit
		// timestamps); two creates may share an instant here
		assertThat(this.service.findChildren(root.id())).extracting(Session::id)
			.containsExactlyInAnyOrder(first.id(), second.id());
		assertThat(this.service.findChildren(first.id())).isEmpty();
		// The tree is the root and its children
		assertThat(this.service.findTree(root.id())).extracting(Session::id)
			.containsExactlyInAnyOrder(root.id(), first.id(), second.id());
		assertThat(this.service.findTree(first.id())).isEmpty();
	}

	@Test
	void deleteCascadesToChildrenButNotToTheParent() {
		Session root = root();
		Session child = this.service.createChild(root.id(), "researcher");
		this.service.appendMessage(child.id(), new UserMessage("child work"));
		Session other = this.service.createChild(root.id(), "writer");

		this.service.delete(child.id());
		assertThat(this.service.findById(child.id())).isNull();
		assertThat(this.service.findById(root.id())).isNotNull();

		this.service.delete(root.id());
		assertThat(this.service.findById(root.id())).isNull();
		assertThat(this.service.findById(other.id())).isNull();
		assertThat(this.service.findTree(root.id())).isEmpty();
	}

	@Test
	void aHandMadeMetadataLinkIsPlainMetadataAndTheSessionStaysARoot() {
		// The convention the 0.10 guide suggested (a "parentSessionId" metadata key) is
		// just metadata now: the links are fields that only createChild sets
		InMemorySessionRepository repository = InMemorySessionRepository.builder().build();
		SessionService service = DefaultSessionService.builder().sessionRepository(repository).build();
		Session parent = service.create(CreateSessionRequest.builder().build());
		Session legacy = repository.save(Session.builder()
			.id("legacy")
			.metadata("parentSessionId", parent.id())
			.metadata(SessionAttributes.PRINCIPAL_NAME, "alice")
			.build());

		assertThat(legacy.isRoot()).isTrue();
		assertThat(legacy.parentSessionId()).isNull();
		assertThat(legacy.rootSessionId()).isEqualTo("legacy");
		assertThat(service.findChildren(parent.id())).isEmpty();
		assertThat(service.findTree(parent.id())).extracting(Session::id).containsExactly(parent.id());
		assertThat(SessionSelector.principal("alice").select(service)).containsExactly("legacy");
		service.delete(parent.id());
		assertThat(service.findById("legacy")).isNotNull();
	}

	@Test
	void aChildOfAParentWithoutAPrincipalMayNotNameOneButTheResolverMay() {
		Session unowned = this.service.create(CreateSessionRequest.builder().build());

		assertThatIllegalArgumentException().isThrownBy(() -> this.service.createChild(unowned.id(),
				CreateSessionRequest.builder().metadata(SessionAttributes.PRINCIPAL_NAME, "mallory").build()))
			.withMessageContaining("has none");
		assertThat(SessionAttributes.principalName(this.service.createChild(unowned.id(), "x"))).isNull();

		SessionService resolving = DefaultSessionService.builder()
			.sessionRepository(InMemorySessionRepository.builder().build())
			.scopeResolver(SessionScopeResolver.fixed(Map.of(SessionAttributes.PRINCIPAL_NAME, "caller")))
			.build();
		Session owned = resolving.create(CreateSessionRequest.builder().metadata(SessionAttributes.PRINCIPAL_NAME, "alice").build());
		Session orphanParent = resolving.create(CreateSessionRequest.builder().metadata(Map.of()).build());
		// The parent's principal wins over the resolver's; a parent without one lets the
		// resolver fill it
		assertThat(SessionAttributes.principalName(resolving.createChild(owned.id(), "a"))).isEqualTo("alice");
		assertThat(SessionAttributes.principalName(orphanParent)).isEqualTo("caller");
	}

	@Test
	void maxChildDepthZeroDisablesChildrenAndAnOrphanChildKeepsItsDepth() {
		SessionService flat = DefaultSessionService.builder()
			.sessionRepository(InMemorySessionRepository.builder().build())
			.maxTreeDepth(0)
			.build();
		Session r = flat.create(CreateSessionRequest.builder().build());
		assertThatIllegalArgumentException().isThrownBy(() -> flat.createChild(r.id(), "x"))
			.withMessageContaining("depth");

		InMemorySessionRepository repository = InMemorySessionRepository.builder().build();
		SessionService service = DefaultSessionService.builder().sessionRepository(repository).build();
		Session root = service.create(CreateSessionRequest.builder().build());
		Session child = service.createChild(root.id(), "researcher");
		repository.delete(root.id()); // out of band: the child is orphaned, still at depth 1
		assertThat(service.findById(child.id())).isNotNull();
		assertThatIllegalArgumentException().isThrownBy(() -> service.createChild(child.id(), "deeper"))
			.withMessageContaining("depth");
		assertThat(service.findChildren(child.id())).isEmpty();
	}

	@Test
	void findByIndexReturnsTheSessionsOfAPrincipal() {
		this.service.create(CreateSessionRequest.builder().metadata(SessionAttributes.PRINCIPAL_NAME, "alice").build());
		this.service.create(CreateSessionRequest.builder().metadata(SessionAttributes.PRINCIPAL_NAME, "alice").build());
		this.service.create(CreateSessionRequest.builder().metadata(SessionAttributes.PRINCIPAL_NAME, "bob").build());

		assertThat(this.service.findByIndex(SessionAttributes.PRINCIPAL_NAME, "alice")).hasSize(2);
		assertThat(this.service.findByIndex(SessionAttributes.PRINCIPAL_NAME, "bob")).hasSize(1);
	}

	@Test
	void compactWithSlidingWindowKeepsOnlyMaxEvents() {
		Session session = this.service.create(CreateSessionRequest.builder().metadata(SessionAttributes.PRINCIPAL_NAME, "user-1").build());

		for (int i = 1; i <= 5; i++) {
			this.service.appendMessage(session.id(), new UserMessage("msg-" + i));
		}

		CompactionResult result = this.service.compact(session.id(), req -> true,
				SlidingWindowCompactionStrategy.builder().maxEvents(2).build());

		assertThat(result.coveredEventCount()).isEqualTo(3);
		assertThat(result.preservedEvents()).hasSize(2);

		// Active window keeps only the last two events
		List<SessionEvent> active = this.service.getEvents(session.id(), EventFilter.active());
		assertThat(active).extracting(e -> e.getMessage().getText()).containsExactly("msg-4", "msg-5");

		// The three covered events stay in the log, followed by the boundary that records
		// the compaction; nothing is deleted or changed.
		List<SessionEvent> all = this.service.getEvents(session.id());
		assertThat(this.service.getMessages(session.id())).extracting(Message::getText)
			.containsExactly("msg-1", "msg-2", "msg-3", "msg-4", "msg-5");
		assertThat(all).hasSize(6);
		assertThat(all.get(5).getType()).isEqualTo(EventType.COMPACTION);
		CompactionBoundary boundary = CompactionBoundary.from(all.get(5));
		assertThat(boundary.coveredEventCount()).isEqualTo(3);
		assertThat(boundary.preservedEventIds()).containsExactly(all.get(3).getId(), all.get(4).getId());
		assertThat(boundary.summaryEventIds()).isEmpty();
		assertThat(boundary.strategy()).isEqualTo("sliding-window");
	}

	@Test
	void compactAppendsTheSummaryEventsAfterTheBoundaryAndTagsThem() {
		Session session = this.service.create(CreateSessionRequest.builder().metadata(SessionAttributes.PRINCIPAL_NAME, "user-1").build());
		for (int i = 1; i <= 4; i++) {
			this.service.appendMessage(session.id(), new UserMessage("msg-" + i));
		}
		// A strategy that summarizes everything but the last event
		CompactionStrategy summarizing = request -> {
			List<SessionEvent> view = request.events();
			return new CompactionResult(view.subList(0, 3), List.of(), view.subList(3, 4),
					List.of(new UserMessage("Summarize."), new AssistantMessage("first three messages")), 10, "custom");
		};

		CompactionResult result = this.service.compact(session.id(), req -> true, summarizing);

		assertThat(result.coveredEventCount()).isEqualTo(3);
		List<SessionEvent> all = this.service.getEvents(session.id());
		assertThat(all).hasSize(7);
		SessionEvent boundary = all.get(4);
		assertThat(boundary.getType()).isEqualTo(EventType.COMPACTION);
		List<SessionEvent> summary = all.subList(5, 7);
		assertThat(summary).allMatch(SessionEvent::isSynthetic)
			.allMatch(e -> boundary.getId().equals(e.getMetadata().get(SessionEvent.METADATA_COMPACTION_ID)))
			.allMatch(e -> "custom".equals(e.getMetadata().get(SessionEvent.METADATA_COMPACTION_SOURCE)));
		assertThat(CompactionBoundary.from(boundary).summaryEventIds())
			.containsExactly(summary.get(0).getId(), summary.get(1).getId());
		assertThat(this.service.getActiveMessages(session.id())).extracting(Message::getText)
			.containsExactly("Summarize.", "first three messages", "msg-4");
	}

	@Test
	void compactedEventsRemainSearchableViaRecallStorage() {
		// Regression for issue #21: compaction must cover (not delete) events so the
		// Recall Storage keyword search can still surface them after the active context
		// window has moved on.
		Session session = this.service.create(CreateSessionRequest.builder().metadata(SessionAttributes.PRINCIPAL_NAME, "user-1").build());
		this.service.appendMessage(session.id(), new UserMessage("the secret code is alpha-7"));
		for (int i = 1; i <= 5; i++) {
			this.service.appendMessage(session.id(), new UserMessage("filler-" + i));
		}

		this.service.compact(session.id(), req -> true, SlidingWindowCompactionStrategy.builder().maxEvents(2).build());

		// The first message is covered, out of the active window...
		assertThat(this.service.getEvents(session.id(), EventFilter.active()))
			.noneMatch(e -> e.getMessage().getText().contains("alpha-7"));
		// ...but a keyword search (the Recall Storage filter) still finds it.
		List<SessionEvent> recalled = this.service.getEvents(session.id(), EventFilter.keywordSearch("alpha-7"));
		assertThat(recalled).hasSize(1);
		assertThat(recalled.get(0).getMessage().getText()).isEqualTo("the secret code is alpha-7");
	}

	@Test
	void compactNoOpWhenTriggerDoesNotFire() {
		Session session = this.service.create(CreateSessionRequest.builder().metadata(SessionAttributes.PRINCIPAL_NAME, "user-1").build());
		this.service.appendMessage(session.id(), new UserMessage("only message"));

		// Trigger never fires — no compaction, no repository write
		CompactionResult result = this.service.compact(session.id(), req -> false,
				SlidingWindowCompactionStrategy.builder().maxEvents(10).build());

		assertThat(result.isUnchanged()).isTrue();
		assertThat(this.service.getEvents(session.id())).hasSize(1);
	}

	@Test
	void compactNoOpWhenStrategyCoversNothing() {
		Session session = this.service.create(CreateSessionRequest.builder().metadata(SessionAttributes.PRINCIPAL_NAME, "user-1").build());
		this.service.appendMessage(session.id(), new UserMessage("only message"));

		// Trigger fires but window is larger than event count — strategy returns no-op
		CompactionResult result = this.service.compact(session.id(), req -> true,
				SlidingWindowCompactionStrategy.builder().maxEvents(10).build());

		assertThat(result.isUnchanged()).isTrue();
		assertThat(this.service.getEvents(session.id())).hasSize(1);
	}

	@Test
	void compactWithPreloadedSessionProducesSameResultAsSessionIdOverload() {
		Session session = this.service.create(CreateSessionRequest.builder().metadata(SessionAttributes.PRINCIPAL_NAME, "user-1").build());

		for (int i = 1; i <= 5; i++) {
			this.service.appendMessage(session.id(), new UserMessage("msg-" + i));
		}

		// Session-object overload skips the internal findById() round-trip but must
		// produce an identical result to the sessionId overload.
		CompactionResult result = this.service.compact(session.id(), req -> true,
				SlidingWindowCompactionStrategy.builder().maxEvents(2).build());

		assertThat(result.coveredEventCount()).isEqualTo(3);
		assertThat(result.preservedEvents()).hasSize(2);

		List<SessionEvent> active = this.service.getEvents(session.id(), EventFilter.active());
		assertThat(active).extracting(e -> e.getMessage().getText()).containsExactly("msg-4", "msg-5");
	}

	@Test
	void getEventsWithFilterAppliesFilter() {
		Session session = this.service.create(CreateSessionRequest.builder().metadata(SessionAttributes.PRINCIPAL_NAME, "user-1").build());

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
		Session session = this.service.create(CreateSessionRequest.builder().metadata(SessionAttributes.PRINCIPAL_NAME, "user-1").build());

		for (int i = 1; i <= 5; i++) {
			this.service.appendMessage(session.id(), new UserMessage("msg-" + i));
		}

		// Two threads race to compact the same session to a window of 2.
		// The CAS in appendEvents guarantees that only the first boundary lands;
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
				results[idx] = this.service.compact(session.id(), req -> true,
						SlidingWindowCompactionStrategy.builder().maxEvents(2).build());
			});
		}

		ready.await();
		go.countDown();
		executor.shutdown();
		assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

		// Exactly one compaction must have covered 3 events; the other is a no-op.
		int totalCovered = results[0].coveredEventCount() + results[1].coveredEventCount();
		assertThat(totalCovered).isEqualTo(3);

		// The surviving active window must be exactly the last 2 messages, and exactly
		// one boundary was appended.
		List<SessionEvent> active = this.service.getEvents(session.id(), EventFilter.active());
		assertThat(active).extracting(e -> e.getMessage().getText()).containsExactly("msg-4", "msg-5");
		assertThat(this.service.getEvents(session.id()).stream().filter(e -> e.getType() == EventType.COMPACTION))
			.hasSize(1);
	}

	// --- deleteExpiredSessions ---

	@Test
	void deleteExpiredSessionsRemovesOnlyExpiredSessions() {
		Session active = this.service.create(CreateSessionRequest.builder()
			.metadata(SessionAttributes.PRINCIPAL_NAME, "u1")
			.timeToLive(java.time.Duration.ofHours(1))
			.build());
		Session expired = this.service.create(CreateSessionRequest.builder()
			.metadata(SessionAttributes.PRINCIPAL_NAME, "u2")
			.timeToLive(java.time.Duration.ofSeconds(-1)) // already in the past
			.build());

		int deleted = this.service.deleteExpiredSessions(Instant.now());

		assertThat(deleted).isEqualTo(1);
		assertThat(this.service.findById(active.id())).isNotNull();
		assertThat(this.service.findById(expired.id())).isNull();
	}

	@Test
	void deleteExpiredSessionsReturnsZeroWhenNoneExpired() {
		this.service.create(CreateSessionRequest.builder()
			.metadata(SessionAttributes.PRINCIPAL_NAME, "u1")
			.timeToLive(java.time.Duration.ofHours(1))
			.build());

		int deleted = this.service.deleteExpiredSessions(Instant.now());

		assertThat(deleted).isZero();
	}

	@Test
	void deleteExpiredSessionsReturnsZeroWhenNoSessions() {
		assertThat(this.service.deleteExpiredSessions(Instant.now())).isZero();
	}

	// --- Status, turns and archive ---

	@Test
	void aTurnRunsBetweenStartAndEndAndIsRecordedInTheLog() {
		Session session = this.service.create(CreateSessionRequest.builder().build());
		assertThat(session.status()).isEqualTo(SessionStatus.IDLE);

		Session running = this.service.startTurn(session.id());
		assertThat(running.status()).isEqualTo(SessionStatus.RUNNING);
		assertThat(running.stopReason()).isNull();

		Session idle = this.service.endTurn(session.id(), StopReason.END_TURN);
		assertThat(idle.status()).isEqualTo(SessionStatus.IDLE);
		assertThat(idle.stopReason()).isEqualTo(StopReason.END_TURN);

		List<SessionEvent> statuses = this.service.getEvents(session.id(),
				EventFilter.builder().eventTypes(Set.of(EventType.STATUS)).build());
		assertThat(statuses).extracting(SessionStatusEvents::status)
			.containsExactly(SessionStatus.RUNNING, SessionStatus.IDLE);
		assertThat(SessionStatusEvents.stopReason(statuses.get(1))).isEqualTo(StopReason.END_TURN);
		// Status events are runtime state, not conversation
		assertThat(this.service.getActiveMessages(session.id())).isEmpty();
	}

	@Test
	void aSecondTurnCannotStartWhileOneIsRunning() {
		Session session = this.service.create(CreateSessionRequest.builder().build());
		this.service.startTurn(session.id());

		assertThatIllegalStateException().isThrownBy(() -> this.service.startTurn(session.id()))
			.withMessageContaining("could not start a turn: it is RUNNING");
		// An abandoned turn is ended by hand
		this.service.endTurn(session.id(), StopReason.INTERRUPTED);
		assertThat(this.service.startTurn(session.id()).status()).isEqualTo(SessionStatus.RUNNING);
		// Ending a turn that is not running, or on an unknown session, is an error
		this.service.endTurn(session.id(), StopReason.END_TURN);
		assertThatIllegalStateException().isThrownBy(() -> this.service.endTurn(session.id(), StopReason.END_TURN))
			.withMessageContaining("not running");
		assertThatIllegalArgumentException().isThrownBy(() -> this.service.startTurn("missing"));
		assertThatIllegalArgumentException().isThrownBy(() -> this.service.endTurn("missing", StopReason.END_TURN));
	}

	@Test
	void archiveTerminatesTheSessionAndItsChildrenAndRejectsEverythingAfter() {
		Session root = root();
		Session child = this.service.createChild(root.id(), "researcher");
		this.service.appendMessage(root.id(), new UserMessage("before"));

		Session archived = this.service.archive(root.id());

		assertThat(archived.isArchived()).isTrue();
		assertThat(archived.archivedAt()).isNotNull();
		assertThat(this.service.findById(child.id()).isArchived()).isTrue();
		assertThat(this.service.getEvents(root.id(), EventFilter.builder().eventTypes(Set.of(EventType.STATUS)).build()))
			.extracting(SessionStatusEvents::status)
			.containsExactly(SessionStatus.TERMINATED);
		// Read-only from now on: history stays, nothing new lands
		assertThat(this.service.getActiveMessages(root.id())).extracting(m -> m.getText()).containsExactly("before");
		assertThatIllegalStateException()
			.isThrownBy(() -> this.service.appendMessage(root.id(), new UserMessage("after")))
			.withMessageContaining("archived");
		assertThatIllegalStateException().isThrownBy(() -> this.service.startTurn(root.id()))
			.withMessageContaining("archived");
		assertThatIllegalStateException().isThrownBy(() -> this.service.createChild(root.id(), "late"))
			.withMessageContaining("archived");
		assertThatIllegalStateException().isThrownBy(() -> this.service.compact(root.id(), request -> true,
				org.springframework.ai.session.compaction.SlidingWindowCompactionStrategy.builder().maxEvents(1).build()));
		// Idempotent, and a child can be archived on its own without touching the parent
		assertThat(this.service.archive(root.id()).archivedAt()).isEqualTo(archived.archivedAt());
		Session other = root();
		Session otherChild = this.service.createChild(other.id(), "writer");
		this.service.archive(otherChild.id());
		assertThat(this.service.findById(other.id()).isArchived()).isFalse();
		assertThat(this.service.findById(otherChild.id()).isArchived()).isTrue();
	}

	@Test
	void archiveRefusesARunningTreeAndAggregateStatusFollowsTheChildren() {
		Session root = root();
		Session child = this.service.createChild(root.id(), "researcher");
		assertThat(this.service.aggregateStatus(root.id())).isEqualTo(SessionStatus.IDLE);

		this.service.startTurn(child.id());
		assertThat(this.service.findById(root.id()).status()).isEqualTo(SessionStatus.IDLE);
		assertThat(this.service.aggregateStatus(root.id())).isEqualTo(SessionStatus.RUNNING);
		assertThatIllegalStateException().isThrownBy(() -> this.service.archive(root.id()))
			.withMessageContaining("running");
		assertThat(this.service.findById(root.id()).isArchived()).isFalse();
		assertThat(this.service.findById(child.id()).isArchived()).isFalse();

		this.service.endTurn(child.id(), StopReason.END_TURN);
		this.service.startTurn(root.id());
		assertThatIllegalStateException().isThrownBy(() -> this.service.archive(root.id()));
		this.service.endTurn(root.id(), StopReason.END_TURN);
		this.service.archive(root.id());
		assertThat(this.service.aggregateStatus(root.id())).isEqualTo(SessionStatus.TERMINATED);
		assertThatIllegalArgumentException().isThrownBy(() -> this.service.aggregateStatus("missing"));
	}

	@Test
	void aParkedTurnRecordsTheEventsItWaitsOn() {
		Session session = this.service.create(CreateSessionRequest.builder().build());
		this.service.startTurn(session.id());

		Session parked = this.service.endTurn(session.id(), StopReason.REQUIRES_ACTION, List.of("evt-1"));

		assertThat(parked.status()).isEqualTo(SessionStatus.IDLE);
		assertThat(parked.stopReason()).isEqualTo(StopReason.REQUIRES_ACTION);
		List<SessionEvent> statuses = this.service.getEvents(session.id(),
				EventFilter.builder().eventTypes(Set.of(EventType.STATUS)).build());
		assertThat(SessionStatusEvents.eventIds(statuses.get(1))).containsExactly("evt-1");
	}

	@Test
	void usageIsTheSumOfTheUsageEventsAndATreeAddsItsChildren() {
		Session root = this.service.create(CreateSessionRequest.builder().build());
		Session child = this.service.createChild(root.id(), "worker");
		assertThat(this.service.usage(root.id())).isEqualTo(TokenUsage.ZERO);
		this.service.appendEvent(UsageEvents.of(root.id(), new TokenUsage(100, 10, 110, 0, 0), "m"));
		this.service.appendEvent(UsageEvents.of(root.id(), new TokenUsage(200, 20, 220, 50, 0), "m"));
		this.service.appendEvent(UsageEvents.of(child.id(), new TokenUsage(1, 1, 2, 0, 0), "m"));
		this.service.appendMessage(root.id(), new UserMessage("not usage"));

		assertThat(this.service.usage(root.id())).isEqualTo(new TokenUsage(300, 30, 330, 50, 0));
		assertThat(this.service.usage(child.id())).isEqualTo(new TokenUsage(1, 1, 2, 0, 0));
		assertThat(this.service.aggregateUsage(root.id())).isEqualTo(new TokenUsage(301, 31, 332, 50, 0));
		assertThatIllegalArgumentException().isThrownBy(() -> this.service.usage("missing"));
	}

	@Test
	void aPatternIsAppliedByTheServiceAfterTheRead() {
		Session session = this.service.create(CreateSessionRequest.builder().build());
		for (String text : List.of("msg-1", "msg-2", "msg-13", "msg-14", "msg-5")) {
			this.service.appendMessage(session.id(), new UserMessage(text));
		}
		List<SessionEvent> log = this.service.getEvents(session.id());
		Pattern ones = Pattern.compile("^msg-1\\d?$");

		assertThat(this.service.getEvents(session.id(), EventFilter.patternSearch(ones)))
			.extracting(e -> e.getMessage().getText())
			.containsExactly("msg-1", "msg-13", "msg-14");
		// The window applies to the pattern's matches, not to the store's read
		assertThat(this.service.getEvents(session.id(), EventFilter.builder().pattern(ones).lastN(2).build()))
			.extracting(e -> e.getMessage().getText())
			.containsExactly("msg-13", "msg-14");
		assertThat(this.service.getEvents(session.id(), EventFilter.builder().pattern(ones).page(1, 2).build()))
			.extracting(e -> e.getMessage().getText())
			.containsExactly("msg-14");
		assertThat(this.service.getEvents(session.id(),
				EventFilter.builder().pattern(ones).after(log.get(0).getId(), 1).order(EventFilter.Order.DESC).build()))
			.extracting(e -> e.getMessage().getText())
			.containsExactly("msg-13");
		assertThat(this.service.findEvents(List.of(session.id()), EventFilter.builder().pattern(ones).lastN(1).build()))
			.extracting(e -> e.getMessage().getText())
			.containsExactly("msg-14");
	}

	@Test
	void aSubscriberSeesTheEventsAppendedFromThenOn() {
		Session session = this.service.create(CreateSessionRequest.builder().build());
		Session other = this.service.create(CreateSessionRequest.builder().build());
		this.service.appendMessage(session.id(), new UserMessage("before"));
		List<SessionEvent> seen = new ArrayList<>();
		Disposable subscription = this.service.subscribe(session.id()).subscribe(seen::add);

		this.service.appendMessage(session.id(), new UserMessage("live"));
		this.service.appendMessage(other.id(), new UserMessage("elsewhere"));
		this.service.startTurn(session.id());
		this.service.endTurn(session.id(), StopReason.END_TURN);
		subscription.dispose();
		this.service.appendMessage(session.id(), new UserMessage("after"));

		assertThat(seen).extracting(SessionEvent::getType)
			.containsExactly(EventType.MESSAGE, EventType.STATUS, EventType.STATUS);
		assertThat(seen.get(0).getMessage().getText()).isEqualTo("live");
		// The reconnect pattern: a cursor read after the last seen event fills the gap
		assertThat(this.service.getEvents(session.id(), EventFilter.after(seen.get(2).getId())))
			.extracting(e -> e.getMessage().getText())
			.containsExactly("after");
	}

	@Test
	void aSubscriptionNeedsAnExistingSession() {
		assertThatIllegalArgumentException().isThrownBy(() -> this.service.subscribe("not-yet"));
	}

	@Test
	void aFailingSubscriberDoesNotFailTheWriter() {
		Session session = this.service.create(CreateSessionRequest.builder().build());
		Disposable subscription = this.service.subscribe(session.id()).subscribe(event -> {
			throw new IllegalStateException("boom");
		});

		this.service.appendMessage(session.id(), new UserMessage("still stored"));
		subscription.dispose();

		assertThat(this.service.getMessages(session.id())).hasSize(1);
	}

	@Test
	void aChildCreatedWithASnapshotCarriesItUnchanged() {
		Session root = this.service.create(CreateSessionRequest.builder().agent(AgentSnapshot.of("coordinator", "1", "m-big")).build());
		Session child = this.service.createChild(root.id(), AgentSnapshot.of("worker", "7", "m-small"));

		assertThat(SessionAttributes.agent(root)).isEqualTo(AgentSnapshot.of("coordinator", "1", "m-big"));
		assertThat(SessionAttributes.agent(child)).isEqualTo(AgentSnapshot.of("worker", "7", "m-small"));
		// The child's agent is its own, not inherited from the root
		assertThat(SessionAttributes.agent(this.service.createChild(root.id(), CreateSessionRequest.builder().build())))
			.isNull();
		assertThat(this.service.findByIndex(SessionAttributes.AGENT_NAME, "worker")).extracting(Session::id)
			.containsExactly(child.id());
	}

	@Test
	void aTurnNamesItsRunOnBothStatusEvents() {
		Session session = this.service.create(CreateSessionRequest.builder().build());
		this.service.startTurn(session.id(), "run-7");
		this.service.endTurn(session.id(), StopReason.END_TURN);
		this.service.startTurn(session.id());
		this.service.endTurn(session.id(), StopReason.END_TURN);

		List<SessionEvent> statuses = this.service.getEvents(session.id(),
				EventFilter.builder().eventTypes(Set.of(EventType.STATUS)).build());
		assertThat(statuses).extracting(SessionStatusEvents::runId).containsExactly("run-7", "run-7", null, null);
	}

	@Test
	void aCallerInterruptsARunningTurn() {
		Session session = this.service.create(CreateSessionRequest.builder().build());
		this.service.startTurn(session.id(), "run-3");

		Session idle = this.service.interrupt(session.id(), "operator stop");

		assertThat(idle.stopReason()).isEqualTo(StopReason.INTERRUPTED);
		List<SessionEvent> log = this.service.getEvents(session.id());
		assertThat(log).extracting(SessionEvent::getType)
			.containsExactly(EventType.STATUS, EventType.INTERRUPT, EventType.STATUS);
		assertThat(InterruptEvents.reason(log.get(1))).isEqualTo("operator stop");
		assertThat(SessionStatusEvents.runId(log.get(2))).isEqualTo("run-3");
		assertThatIllegalStateException().isThrownBy(() -> this.service.interrupt(session.id()));
		// A refused interrupt writes nothing
		assertThat(this.service.getEvents(session.id())).hasSize(3);
		assertThatIllegalArgumentException().isThrownBy(() -> this.service.interrupt("missing"));
	}

	@Test
	void aTurnParksOnAQuestionAndResumesWithItsAnswer() {
		Session session = this.service.create(CreateSessionRequest.builder().build());
		this.service.startTurn(session.id(), "run-1");
		this.service.appendMessage(session.id(), new UserMessage("hi"));
		String replyId = this.service.getEvents(session.id(), EventFilter.active()).get(0).getId();

		SessionEvent question = this.service.park(session.id(),
				new Interaction(Interaction.KIND_INTERRUPT, "approve/", "Publish?", null), List.of(replyId));

		Session parked = this.service.findById(session.id());
		assertThat(parked.stopReason()).isEqualTo(StopReason.REQUIRES_ACTION);
		List<SessionEvent> log = this.service.getEvents(session.id());
		assertThat(SessionStatusEvents.eventIds(log.get(log.size() - 1))).containsExactly(replyId, question.getId());
		assertThat(this.service.pendingInteraction(session.id())).isNotNull();
		assertThat(this.service.pendingInteraction(session.id()).getId()).isEqualTo(question.getId());
		// Parking needs a running turn and writes nothing otherwise
		assertThatIllegalStateException().isThrownBy(() -> this.service.park(session.id(),
				new Interaction(Interaction.KIND_INTERRUPT, "", "again?", null), List.of()));
		assertThat(this.service.getEvents(session.id())).hasSameSizeAs(log);

		Session resumed = this.service.resume(session.id(), "run-1", Map.of("approved", true));

		assertThat(resumed.status()).isEqualTo(SessionStatus.RUNNING);
		assertThat(this.service.pendingInteraction(session.id())).isNull();
		List<SessionEvent> interactions = this.service.getEvents(session.id(),
				EventFilter.builder().eventTypes(Set.of(EventType.INTERACTION)).build());
		assertThat(InteractionEvents.answer(interactions.get(1))).isEqualTo(Map.of("approved", true));
		// A session that is not parked resumes as a plain turn, recording nothing
		this.service.endTurn(session.id(), StopReason.END_TURN);
		this.service.resume(session.id(), "run-2", "ignored");
		assertThat(this.service.getEvents(session.id(),
				EventFilter.builder().eventTypes(Set.of(EventType.INTERACTION)).build())).hasSize(2);
	}

}
