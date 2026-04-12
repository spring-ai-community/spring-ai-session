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

import org.springframework.ai.session.SessionEvent;
import org.springframework.util.Assert;

/**
 * The outcome of a compaction operation.
 *
 * @author Christian Tzolov
 * @since 2.0.0
 */
public record CompactionResult(List<SessionEvent> compactedEvents, List<SessionEvent> archivedEvents,
		int tokensEstimatedSaved) {

	public CompactionResult {
		Assert.notNull(compactedEvents, "compactedEvents must not be null");
		Assert.notNull(archivedEvents, "archivedEvents must not be null");
		compactedEvents = List.copyOf(compactedEvents);
		archivedEvents = List.copyOf(archivedEvents);
	}

	/** Returns the number of events removed, derived from {@link #archivedEvents()}. */
	public int eventsRemoved() {
		return this.archivedEvents.size();
	}

}
