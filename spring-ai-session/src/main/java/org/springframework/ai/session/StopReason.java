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

/**
 * Why the last turn of a {@link Session} ended, recorded when the session goes
 * {@link SessionStatus#IDLE}.
 *
 * @author Christian Tzolov
 * @since 0.11.0
 */
public enum StopReason {

	/** The model finished its reply. */
	END_TURN,

	/**
	 * The turn is parked until a caller acts, for example on a tool call that awaits
	 * confirmation; the events that need the action are named in the status event.
	 */
	REQUIRES_ACTION,

	/** A budget guard stopped the turn. */
	BUDGET_REACHED,

	/** A caller interrupted the turn, or ended one that had been abandoned. */
	INTERRUPTED,

	/** The turn failed with an error. */
	ERROR

}
