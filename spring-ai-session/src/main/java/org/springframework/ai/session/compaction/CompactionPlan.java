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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.session.SessionEvent;
import org.springframework.util.Assert;

/**
 * The write operations a compaction pass needs, computed by the session service from the
 * active event log and a {@link CompactionResult}, and applied by a
 * {@link org.springframework.ai.session.SessionRepository} in one atomic step.
 *
 * <p>
 * A repository never has to interpret a compaction: it archives the {@link #archiveIds()}
 * in place, removes the {@link #deleteIds()} (e.g. a superseded synthetic summary), and
 * inserts each {@link Insert} group immediately before its anchor event, or at the end of
 * the log when the anchor is {@code null}. Existing events never move.
 *
 * <p>
 * {@link #applyTo(List)} is the reference implementation of those rules for a log held as
 * a list, which in-memory and list-based stores can use directly.
 *
 * @param archiveIds ids of active events to flag archived, in place
 * @param deleteIds ids of active events to remove
 * @param inserts new events to insert, grouped by the event they must precede
 * @author Christian Tzolov
 * @since 0.10.0
 */
public record CompactionPlan(Set<String> archiveIds, Set<String> deleteIds, List<Insert> inserts) {

	public CompactionPlan {
		Assert.notNull(archiveIds, "archiveIds must not be null");
		Assert.notNull(deleteIds, "deleteIds must not be null");
		Assert.notNull(inserts, "inserts must not be null");
		archiveIds = Set.copyOf(archiveIds);
		deleteIds = Set.copyOf(deleteIds);
		inserts = List.copyOf(inserts);
	}

	/**
	 * New events to insert before an existing event.
	 * @param beforeEventId the id of the existing event the group goes in front of, or
	 * {@code null} to append the group at the end of the log
	 * @param events the new events, in order
	 */
	public record Insert(@Nullable String beforeEventId, List<SessionEvent> events) {

		public Insert {
			Assert.notEmpty(events, "events must not be empty");
			events = List.copyOf(events);
		}

	}

	/** Returns {@code true} when the plan changes nothing. */
	public boolean isEmpty() {
		return this.archiveIds.isEmpty() && this.deleteIds.isEmpty() && this.inserts.isEmpty();
	}

	/** Returns every new event of every insert group, in plan order. */
	public List<SessionEvent> insertedEvents() {
		return this.inserts.stream().flatMap(insert -> insert.events().stream()).toList();
	}

	/**
	 * Computes the plan that turns the active log the strategy saw into the strategy's
	 * result. Archived events come from the result; active events that the result keeps
	 * neither active nor archived are deleted; events in the result that are not in the
	 * active log are new and are inserted before the next existing event that follows
	 * them in the result.
	 * @param sessionId the session being compacted; every new event must belong to it
	 * @param activeEvents the active log the strategy was given, in log order
	 * @param result the strategy's result
	 * @return the plan
	 * @throws IllegalArgumentException if a new event belongs to another session
	 */
	public static CompactionPlan of(String sessionId, List<SessionEvent> activeEvents, CompactionResult result) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		Assert.notNull(activeEvents, "activeEvents must not be null");
		Assert.notNull(result, "result must not be null");

		Set<String> activeIds = activeEvents.stream().map(SessionEvent::getId).collect(Collectors.toSet());
		Set<String> archiveIds = result.archivedEvents()
			.stream()
			.map(SessionEvent::getId)
			.collect(Collectors.toCollection(LinkedHashSet::new));
		Set<String> retainedIds = result.compactedEvents()
			.stream()
			.map(SessionEvent::getId)
			.collect(Collectors.toSet());
		Set<String> deleteIds = new LinkedHashSet<>();
		for (String id : activeIds) {
			if (!retainedIds.contains(id) && !archiveIds.contains(id)) {
				deleteIds.add(id);
			}
		}

		List<Insert> inserts = new ArrayList<>();
		List<SessionEvent> pending = new ArrayList<>();
		for (SessionEvent event : result.compactedEvents()) {
			if (activeIds.contains(event.getId())) {
				if (!pending.isEmpty()) {
					inserts.add(new Insert(event.getId(), pending));
					pending.clear();
				}
			}
			else {
				if (!sessionId.equals(event.getSessionId())) {
					throw new IllegalArgumentException("compaction result contains a new event of session '"
							+ event.getSessionId() + "', not of session " + sessionId);
				}
				pending.add(event);
			}
		}
		if (!pending.isEmpty()) {
			inserts.add(new Insert(null, pending));
		}
		return new CompactionPlan(archiveIds, deleteIds, inserts);
	}

	/**
	 * Applies this plan to a log held as a list and returns the new log: events in
	 * {@link #archiveIds()} are flagged archived in place, events in {@link #deleteIds()}
	 * are dropped, each insert group goes immediately before its anchor, and a group
	 * without an anchor is appended. The input is not modified.
	 * @param log the current log, oldest first
	 * @return the compacted log
	 * @throws IllegalArgumentException if an archive id or an anchor id is not in the log
	 */
	public List<SessionEvent> applyTo(List<SessionEvent> log) {
		Assert.notNull(log, "log must not be null");
		Set<String> logIds = log.stream().map(SessionEvent::getId).collect(Collectors.toSet());
		for (String id : this.archiveIds) {
			if (!logIds.contains(id)) {
				throw new IllegalArgumentException("archiveIds contains an event that is not in the log: " + id);
			}
		}
		Map<String, List<SessionEvent>> insertBefore = new HashMap<>();
		List<SessionEvent> append = new ArrayList<>();
		for (Insert insert : this.inserts) {
			if (insert.beforeEventId() == null) {
				append.addAll(insert.events());
			}
			else if (!logIds.contains(insert.beforeEventId())) {
				throw new IllegalArgumentException(
						"inserts refers to an anchor event that is not in the log: " + insert.beforeEventId());
			}
			else {
				insertBefore.computeIfAbsent(insert.beforeEventId(), id -> new ArrayList<>()).addAll(insert.events());
			}
		}

		Set<String> dropped = new HashSet<>(this.deleteIds);
		List<SessionEvent> result = new ArrayList<>(log.size() + append.size());
		for (SessionEvent event : log) {
			result.addAll(insertBefore.getOrDefault(event.getId(), List.of()));
			if (dropped.contains(event.getId())) {
				continue;
			}
			result.add(this.archiveIds.contains(event.getId()) ? event.asArchived() : event);
		}
		result.addAll(append);
		return List.copyOf(result);
	}

}
