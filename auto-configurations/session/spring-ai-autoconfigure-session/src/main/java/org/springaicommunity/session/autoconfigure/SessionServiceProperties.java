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

package org.springaicommunity.session.autoconfigure;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @author Christian Tzolov
 * @since 0.1.0
 */
@ConfigurationProperties(SessionServiceProperties.CONFIG_PREFIX)
public class SessionServiceProperties {

	public static final String CONFIG_PREFIX = "spring.ai.session";

	/**
	 * Default time-to-live of sessions created by the service.
	 */
	private Duration timeToLive = Duration.ofDays(60);

	/**
	 * Whether system messages may be stored in a session. Disabled by default: system
	 * prompts are configuration and are best supplied on every request.
	 */
	private boolean allowSystemMessages = false;

	/**
	 * How deep a session tree may go: 1 (the default) allows child sessions of a root and
	 * no children of children; 0 disables createChild.
	 */
	private int maxTreeDepth = 1;

	/**
	 * The metadata keys a child session takes from its parent and may not diverge from,
	 * besides the principal name, which is always inherited.
	 */
	private Set<String> inheritedAttributes = new LinkedHashSet<>();

	public Duration getTimeToLive() {
		return timeToLive;
	}

	public void setTimeToLive(Duration timeToLive) {
		this.timeToLive = timeToLive;
	}

	public boolean isAllowSystemMessages() {
		return this.allowSystemMessages;
	}

	public void setAllowSystemMessages(boolean allowSystemMessages) {
		this.allowSystemMessages = allowSystemMessages;
	}

	public int getMaxTreeDepth() {
		return this.maxTreeDepth;
	}

	public void setMaxTreeDepth(int maxTreeDepth) {
		this.maxTreeDepth = maxTreeDepth;
	}

	public Set<String> getInheritedAttributes() {
		return this.inheritedAttributes;
	}

	public void setInheritedAttributes(Set<String> inheritedAttributes) {
		this.inheritedAttributes = inheritedAttributes;
	}

}
