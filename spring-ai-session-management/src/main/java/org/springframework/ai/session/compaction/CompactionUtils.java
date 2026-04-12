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

import java.util.List;

import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.session.SessionEvent;

/**
 * Internal utilities shared by compaction strategies.
 *
 * @author Christian Tzolov
 * @since 2.0.0
 */
final class CompactionUtils {

	private CompactionUtils() {
	}

	/**
	 * Advances {@code rawCutIndex} forward until it points to a {@link MessageType#USER}
	 * event, or to {@code real.size()} if no such event exists.
	 *
	 * <p>
	 * Compaction strategies compute a raw cut point (the index into the real-event list
	 * where the kept window would start) based on event counts or token budgets. That raw
	 * cut can land in the middle of a turn — for example at an assistant reply whose user
	 * message would be archived. Snapping to the nearest turn start guarantees that the
	 * kept window always begins at a complete turn, preserving conversation semantics.
	 * @param real the list of non-synthetic session events
	 * @param rawCutIndex the initial cut point; must be in {@code [0, real.size()]}
	 * @return the adjusted index pointing to the first USER event at or after
	 * {@code rawCutIndex}, or {@code real.size()} if none exists
	 */
	static int snapToTurnStart(List<SessionEvent> real, int rawCutIndex) {
		int idx = rawCutIndex;
		while (idx < real.size() && real.get(idx).getMessageType() != MessageType.USER) {
			idx++;
		}
		return idx;
	}

}
