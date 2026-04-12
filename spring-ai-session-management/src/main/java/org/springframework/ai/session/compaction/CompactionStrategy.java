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

/**
 * Strategy for compacting a session's event history to manage context window size.
 *
 * @author Christian Tzolov
 * @since 2.0.0
 */
@FunctionalInterface
public interface CompactionStrategy {

	/**
	 * Compacts the given list of events, returning a result with the retained events and
	 * those that were archived/removed.
	 * @param request contextual information including token estimates
	 */
	CompactionResult compact(CompactionRequest request);

}
