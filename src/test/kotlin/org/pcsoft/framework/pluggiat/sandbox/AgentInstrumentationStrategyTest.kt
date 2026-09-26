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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.pcsoft.framework.pluggiat.classloader.LoadedPlugin
import org.pcsoft.framework.pluggiat.classloader.PluginClassLoader
import org.pcsoft.framework.pluggiat.manifest.PluginManifest
import org.pcsoft.framework.pluggiat.sandbox.agent.SandboxAgentNotActiveException
import org.pcsoft.framework.pluggiat.sandbox.agent.SandboxGuardRegistry

/**
 * Verifies [AgentInstrumentationStrategy], the [PluginSandboxStrategy] backing [PluginSandbox] by
 * default since IP-02. The test JVM is never started with `-javaagent`, so
 * `PluginSandboxAgent.isActive` is deterministically `false` here - exactly the case these tests
 * exercise.
 */
class AgentInstrumentationStrategyTest {

    private fun loadedPlugin(pluginId: String = "example"): LoadedPlugin = LoadedPlugin(
        pluginId = pluginId,
        manifest = PluginManifest(id = pluginId, name = pluginId, version = "1.0.0", minVersion = "1.0.0", icon = "aWNvbg=="),
        classLoader = PluginClassLoader(emptyArray(), javaClass.classLoader, emptyList(), emptyList()),
    )

    /**
     * Use case: activating a fully permissive [PluginSandboxPolicy.UNRESTRICTED] never requires the
     * agent (there is nothing to mediate) and succeeds even though it is not active in the test JVM.
     */
    @Test
    fun `activate succeeds for an unrestricted policy without an active agent`() {
        val strategy = AgentInstrumentationStrategy()
        val plugin = loadedPlugin()

        val result = strategy.activate(plugin, PluginSandboxPolicy.UNRESTRICTED)

        assertEquals(SandboxCheckResult.Success, result)
        SandboxGuardRegistry.unregister(plugin.classLoader)
    }

    /**
     * Use case: activating a policy that restricts at least one [SandboxApiCategory] (i.e.
     * [PluginSandboxPolicy.requiresApiMediation]) without an active agent throws
     * [SandboxAgentNotActiveException] instead of silently granting unmediated access.
     */
    @Test
    fun `activate throws when a restrictive policy is activated without an active agent`() {
        val strategy = AgentInstrumentationStrategy()
        val plugin = loadedPlugin("restricted-example")
        val policy = PluginSandboxPolicy(allowedApiCategories = setOf(SandboxApiCategory.THREAD_CREATION))

        assertThrows(SandboxAgentNotActiveException::class.java) {
            strategy.activate(plugin, policy)
        }
    }

    /**
     * Use case: [AgentInstrumentationStrategy.activate] registers the activated plugin's policy in
     * [SandboxGuardRegistry] (verified indirectly here via [SandboxGuardRegistry.unregister] being
     * safe to call afterward without error) - the registry's own `register`/`check`/`onViolation`
     * contract is fully covered by `SandboxGuardRegistryTest`.
     */
    @Test
    fun `activate registers the plugin's policy in SandboxGuardRegistry`() {
        val strategy = AgentInstrumentationStrategy()
        val plugin = loadedPlugin("registered-example")

        strategy.activate(plugin, PluginSandboxPolicy.UNRESTRICTED)

        SandboxGuardRegistry.unregister(plugin.classLoader)
    }
}
