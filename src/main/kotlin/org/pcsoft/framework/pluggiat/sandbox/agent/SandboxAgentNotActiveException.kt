/*
 * Copyright (c) KleinerHacker alias Pfeiffer C Soft 2026.
 * This work is licensed under the Apache License, Version 2.0.
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, this software is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations.
 */

package org.pcsoft.framework.pluggiat.sandbox.agent

/**
 * Thrown by [org.pcsoft.framework.pluggiat.sandbox.AgentInstrumentationStrategy] when a
 * [org.pcsoft.framework.pluggiat.sandbox.PluginSandboxPolicy] requiring API mediation is activated
 * while [PluginSandboxAgent.isActive] is `false` - deliberately fatal: a host that configured a
 * restrictive sandbox policy but forgot the `-javaagent` JVM start parameter must find out
 * immediately, not learn later that plugins ran completely unmediated.
 */
class SandboxAgentNotActiveException(pluginId: String) : RuntimeException(
    "Plugin '$pluginId' has a sandbox policy that requires API mediation, but the pluggiat sandbox " +
        "agent is not active. Start the host JVM with '-javaagent:<path-to-pluggiat-jar>' " +
        "(see the host integration documentation).",
)
