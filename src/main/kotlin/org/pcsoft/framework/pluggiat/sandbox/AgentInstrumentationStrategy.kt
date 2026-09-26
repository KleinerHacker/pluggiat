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

package org.pcsoft.framework.pluggiat.sandbox

import org.pcsoft.framework.pluggiat.classloader.LoadedPlugin
import org.pcsoft.framework.pluggiat.sandbox.agent.PluginSandboxAgent
import org.pcsoft.framework.pluggiat.sandbox.agent.SandboxAgentNotActiveException
import org.pcsoft.framework.pluggiat.sandbox.agent.SandboxGuardRegistry

/**
 * The [PluginSandboxStrategy] that mediates a loaded plugin's access to risk-bearing JDK APIs via
 * the `pluggiat` Java agent ([PluginSandboxAgent]) - the default strategy backing [PluginSandbox]
 * since IP-02 (replacing [NoOpSandboxStrategy]).
 *
 * [activate] itself never touches bytecode - the actual instrumentation is installed once, globally,
 * by [PluginSandboxAgent] for every class loaded by a [org.pcsoft.framework.pluggiat.classloader.PluginClassLoader].
 * [activate] only registers/refreshes the plugin's effective policy in [SandboxGuardRegistry], so the
 * guard calls already baked into the plugin's bytecode resolve against the right policy at call time.
 *
 * [onViolation] is forwarded to [SandboxGuardRegistry.register] for every activated plugin; wired
 * by [PluginSandbox]'s `init` block to its own [PluginSandbox.reportViolation] right after
 * construction (a constructor parameter cannot reference an outer `this` that is not yet fully
 * constructed).
 */
class AgentInstrumentationStrategy : PluginSandboxStrategy {

    /** Set once by [PluginSandbox] to its own [PluginSandbox.reportViolation]; a no-op until then. */
    var onViolation: (pluginId: String, violation: SandboxViolation) -> Unit = { _, _ -> }

    override fun activate(loadedPlugin: LoadedPlugin, policy: PluginSandboxPolicy): SandboxCheckResult {
        if (policy.requiresApiMediation && !PluginSandboxAgent.isActive) {
            throw SandboxAgentNotActiveException(loadedPlugin.pluginId)
        }

        SandboxGuardRegistry.register(loadedPlugin.classLoader, loadedPlugin.pluginId, policy, onViolation)
        return SandboxCheckResult.Success
    }
}
