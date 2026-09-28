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
 * A repository never has to interpret a compaction: it flags the {@link #archiveIds()}
 * archived in place and inserts each {@link Insert} group immediately before its anchor
 * event, or at the end of the log when the group has no anchor. Existing events never move
 * and are never removed.
 *
 * <p>
 * Worked example, for a recursive-summarization pass that replaces an earlier summary
 * ({@code Σold}) and folds two more turns into the new one ({@code Σnew}):
 *
 * <pre>
 * active log:      Σold U1 A1 U2 A2 U3 A3
 * strategy result: compactedEvents = [Σnew U3 A3]      archivedEvents = [Σold U1 A1 U2 A2]
 *
 * plan:            archiveIds = {Σold U1 A1 U2 A2}   every active event the result does not keep
 *                  inserts    = [Σnew before U3]      in the result, but not in the active log
 *
 * applyTo:         Σold* U1* A1* U2* A2* Σnew U3 A3   (* = flagged archived)
 * </pre>
 *
 * {@link #applyTo(List)} is the reference implementation of those rules for a log held as
 * a list, which in-memory and list-based stores can use directly.
 *
 * @param archiveIds ids of active events to flag archived, in place
 * @param inserts new events to insert, grouped by the event they must precede
 * @author Christian Tzolov
 * @since 0.10.0
 */
public record CompactionPlan(Set<String> archiveIds, List<Insert> inserts) {

	public CompactionPlan {
		Assert.notNull(archiveIds, "archiveIds must not be null");
		Assert.notNull(inserts, "inserts must not be null");
		archiveIds = Set.copyOf(archiveIds);
		inserts = List.copyOf(inserts);
	}

	/**
	 * A group of new events and where they go: immediately before an existing event (the
	 * anchor), or at the end of the log when there is no anchor.
	 *
	 * @param beforeEventId the id of the existing event the group goes in front of, or
	 * {@code null} to append the group at the end of the log
	 * @param events the new events, in order
	 */
	public record Insert(@Nullable String beforeEventId, List<SessionEvent> events) {

		public Insert {
			Assert.notEmpty(events, "events must not be empty");
			events = List.copyOf(events);
		}

		/** A group that goes immediately before the existing event with the given id. */
		public static Insert before(String anchorEventId, List<SessionEvent> events) {
			Assert.hasText(anchorEventId, "anchorEventId must not be null or empty");
			return new Insert(anchorEventId, events);
		}

		/** A group that goes at the end of the log. */
		public static Insert atEnd(List<SessionEvent> events) {
			return new Insert(null, events);
		}

		/** Returns {@code true} when the group has no anchor and goes at the end of the log. */
		public boolean isAppend() {
			return this.beforeEventId == null;
		}

	}

	/** Returns {@code true} when the plan changes nothing. */
	public boolean isEmpty() {
		return this.archiveIds.isEmpty() && this.inserts.isEmpty();
	}

	/** Returns every new event of every insert group, in plan order. */
	public List<SessionEvent> insertedEvents() {
		return this.inserts.stream().flatMap(insert -> insert.events().stream()).toList();
	}

	/**
	 * Computes the plan that turns the active log the strategy saw into the strategy's
	 * result, by comparing the two:
	 * <ul>
	 * <li>every active event that the result does not keep is archived: the result's
	 * {@link CompactionResult#archivedEvents() archivedEvents}, plus anything else the
	 * strategy left out (a superseded summary);</li>
	 * <li>an event of the result that is not in the active log is new, and is inserted
	 * before the first existing event that follows it in the result, or at the end when
	 * none follows.</li>
	 * </ul>
	 * See the class Javadoc for a worked example.
	 * @param sessionId the session being compacted; every new event must belong to it
	 * @param activeEvents the active log the strategy was given, in log order
	 * @param compactionResult the strategy's result
	 * @return the plan
	 * @throws IllegalArgumentException if a new event belongs to another session
	 */
	public static CompactionPlan of(String sessionId, List<SessionEvent> activeEvents,
			CompactionResult compactionResult) {
		Assert.hasText(sessionId, "sessionId must not be null or empty");
		Assert.notNull(activeEvents, "activeEvents must not be null");
		Assert.notNull(compactionResult, "compactionResult must not be null");

		Set<String> activeIds = idsOf(activeEvents);
		Set<String> archiveIds = archivedIds(activeIds, compactionResult);
		List<Insert> inserts = insertGroups(sessionId, activeIds, compactionResult.compactedEvents());
		return new CompactionPlan(archiveIds, inserts);
	}

	/** What the strategy reported archived, plus every other active event it did not keep. */
	private static Set<String> archivedIds(Set<String> activeIds, CompactionResult compactionResult) {
		Set<String> archiveIds = idsOf(compactionResult.archivedEvents());
		Set<String> keptIds = idsOf(compactionResult.compactedEvents());
		for (String id : activeIds) {
			if (!keptIds.contains(id)) {
				archiveIds.add(id);
			}
		}
		return archiveIds;
	}

	/**
	 * Walks the compacted events in order. Every run of events that are not in the active
	 * log is one group, anchored on the first existing event that follows the run; a run
	 * at the very end has nothing to anchor on and is appended.
	 */
	private static List<Insert> insertGroups(String sessionId, Set<String> activeIds,
			List<SessionEvent> compactedEvents) {
		List<Insert> groups = new ArrayList<>();
		List<SessionEvent> newEventsBeforeNextExisting = new ArrayList<>();
		for (SessionEvent event : compactedEvents) {
			boolean isExisting = activeIds.contains(event.getId());
			if (isExisting) {
				if (!newEventsBeforeNextExisting.isEmpty()) {
					groups.add(Insert.before(event.getId(), newEventsBeforeNextExisting));
					newEventsBeforeNextExisting.clear();
				}
			}
			else {
				// A strategy may only add events to the session it is compacting.
				if (!sessionId.equals(event.getSessionId())) {
					throw new IllegalArgumentException("compaction result contains a new event of session '"
							+ event.getSessionId() + "', not of session " + sessionId);
				}
				newEventsBeforeNextExisting.add(event);
			}
		}
		if (!newEventsBeforeNextExisting.isEmpty()) {
			groups.add(Insert.atEnd(newEventsBeforeNextExisting));
		}
		return groups;
	}

	/**
	 * Applies this plan to a log held as a list and returns the new log: events in
	 * {@link #archiveIds()} are flagged archived in place, each insert group goes
	 * immediately before its anchor, and a group without an anchor is appended. The input
	 * is not modified.
	 * @param log the current log, oldest first
	 * @return the compacted log
	 * @throws IllegalArgumentException if an archive id or an anchor id is not in the log
	 */
	public List<SessionEvent> applyTo(List<SessionEvent> log) {
		Assert.notNull(log, "log must not be null");
		Set<String> logIds = idsOf(log);
		requireInLog(logIds, this.archiveIds, "archiveIds contains an event that is not in the log: ");

		Map<String, List<SessionEvent>> groupsByAnchor = new HashMap<>();
		List<SessionEvent> trailingGroups = new ArrayList<>();
		for (Insert insert : this.inserts) {
			String anchorId = insert.beforeEventId();
			if (anchorId == null) {
				trailingGroups.addAll(insert.events());
			}
			else {
				requireInLog(logIds, Set.of(anchorId), "inserts refers to an anchor event that is not in the log: ");
				groupsByAnchor.computeIfAbsent(anchorId, id -> new ArrayList<>()).addAll(insert.events());
			}
		}

		List<SessionEvent> compacted = new ArrayList<>(log.size() + trailingGroups.size());
		for (SessionEvent event : log) {
			// 1. New events anchored on this event go right before it.
			compacted.addAll(groupsByAnchor.getOrDefault(event.getId(), List.of()));
			// 2. An archived event stays in place, flagged; everything else is untouched.
			compacted.add(this.archiveIds.contains(event.getId()) ? event.asArchived() : event);
		}
		// 3. Groups without an anchor go at the end.
		compacted.addAll(trailingGroups);
		return List.copyOf(compacted);
	}

	private static Set<String> idsOf(List<SessionEvent> events) {
		return events.stream().map(SessionEvent::getId).collect(Collectors.toCollection(LinkedHashSet::new));
	}

	private static void requireInLog(Set<String> logIds, Set<String> ids, String message) {
		for (String id : ids) {
			if (!logIds.contains(id)) {
				throw new IllegalArgumentException(message + id);
			}
		}
	}

}
