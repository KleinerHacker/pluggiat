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
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.pcsoft.framework.pluggiat.classloader.LoadedPlugin
import org.pcsoft.framework.pluggiat.classloader.PluginClassLoader
import org.pcsoft.framework.pluggiat.manifest.PluginManifest
import org.pcsoft.framework.pluggiat.scanner.PluginLocation
import org.pcsoft.framework.pluggiat.scanner.PluginLocationType
import org.pcsoft.framework.pluggiat.scanner.ZipJarScanStrategy
import java.nio.file.Path

/**
 * Verifies [PluginSandbox]'s [PluginSandbox.effectivePolicy] resolution rule (analogous to
 * [org.pcsoft.framework.pluggiat.security.PluginSecurity]'s `effectiveChain` for the security chain)
 * and, with an explicit [NoOpSandboxStrategy], its [PluginSandbox.runGoverned]/[PluginSandbox.deactivate]
 * pass-through behavior and [PluginSandbox.reportViolation]'s [PluginSandbox.violationListener]
 * forwarding. [AgentInstrumentationStrategy] (the real default since IP-02) has its own dedicated
 * `AgentInstrumentationStrategyTest`.
 */
class PluginSandboxTest {

    private fun loadedPlugin(): LoadedPlugin = LoadedPlugin(
        pluginId = "example",
        manifest = PluginManifest(id = "example", name = "example", version = "1.0.0", minVersion = "1.0.0", icon = "aWNvbg=="),
        classLoader = PluginClassLoader(emptyArray(), javaClass.classLoader, emptyList(), emptyList()),
    )

    private fun location(
        type: PluginLocationType = PluginLocationType.EXTERNAL,
        sandboxOverride: PluginSandboxPolicy? = null,
    ): PluginLocation = PluginLocation(
        path = Path.of("."),
        type = type,
        scanStrategy = ZipJarScanStrategy(),
        sandboxOverride = sandboxOverride,
    )

    /**
     * Use case: [PluginSandbox.activate], backed by an explicit [NoOpSandboxStrategy], always
     * reports [SandboxCheckResult.Success] for a freshly loaded plugin, without any real enforcement.
     */
    @Test
    fun `activate is a no-op and always succeeds`() {
        val sandbox = PluginSandbox(NoOpSandboxStrategy())

        val result = sandbox.activate(loadedPlugin(), PluginSandboxPolicy.UNRESTRICTED)

        assertEquals(SandboxCheckResult.Success, result)
    }

    /**
     * Use case: [PluginSandbox.runGoverned] executes the given block directly and returns its result
     * unchanged - no thread/time-limit governance is applied yet (IP-03).
     */
    @Test
    fun `runGoverned executes the block directly and returns its result`() {
        val sandbox = PluginSandbox(NoOpSandboxStrategy())

        val result = sandbox.runGoverned("example", PluginSandboxPolicy.UNRESTRICTED) { 42 }

        assertEquals(42, result)
    }

    /**
     * Use case: [PluginSandbox.reportViolation] and [PluginSandbox.deactivate] can be called without
     * throwing when no [PluginSandbox.violationListener] is set - the default (matching IP-01's
     * behavior) is to only log.
     */
    @Test
    fun `reportViolation and deactivate do not throw without a violationListener`() {
        val sandbox = PluginSandbox(NoOpSandboxStrategy())

        sandbox.reportViolation("example", SandboxViolation("example", SandboxApiCategory.NETWORK, "blocked"))
        sandbox.deactivate("example")
    }

    /**
     * Use case: [PluginSandbox.reportViolation] forwards the plugin id and violation unchanged to
     * [PluginSandbox.violationListener], if one is set - the seam
     * [org.pcsoft.framework.pluggiat.PluginManager] uses to actually react to a violation.
     */
    @Test
    fun `reportViolation forwards to violationListener when set`() {
        val sandbox = PluginSandbox(NoOpSandboxStrategy())
        var receivedPluginId: String? = null
        var receivedViolation: SandboxViolation? = null
        sandbox.violationListener = { pluginId, violation -> receivedPluginId = pluginId; receivedViolation = violation }
        val violation = SandboxViolation("example", SandboxApiCategory.REFLECTION, "blocked reflection")

        sandbox.reportViolation("example", violation)

        assertEquals("example", receivedPluginId)
        assertEquals(violation, receivedViolation)
    }

    /**
     * Use case: a location's own [PluginLocation.sandboxOverride] takes precedence over any default
     * policy configured for its [PluginLocationType].
     */
    @Test
    fun `effectivePolicy prefers the location's own sandboxOverride`() {
        val override = PluginSandboxPolicy(isolationLevel = SandboxIsolationLevel.PROCESS)
        val default = PluginSandboxPolicy(isolationLevel = SandboxIsolationLevel.IN_VM)

        val effective = PluginSandbox.effectivePolicy(
            location(type = PluginLocationType.EXTERNAL, sandboxOverride = override),
            sandboxPolicies = mapOf(PluginLocationType.EXTERNAL to default),
        )

        assertSame(override, effective)
    }

    /**
     * Use case: without a location-specific override, the default policy configured for the
     * location's [PluginLocationType] applies.
     */
    @Test
    fun `effectivePolicy falls back to the type's default policy`() {
        val default = PluginSandboxPolicy(isolationLevel = SandboxIsolationLevel.PROCESS)

        val effective = PluginSandbox.effectivePolicy(
            location(type = PluginLocationType.BUILTIN),
            sandboxPolicies = mapOf(PluginLocationType.BUILTIN to default),
        )

        assertSame(default, effective)
    }

    /**
     * Use case: without any override or configured default for the location's type,
     * [PluginSandboxPolicy.UNRESTRICTED] applies - unlike the security chain, an unconfigured sandbox
     * never fails a load.
     */
    @Test
    fun `effectivePolicy defaults to UNRESTRICTED when nothing is configured`() {
        val effective = PluginSandbox.effectivePolicy(location(), sandboxPolicies = emptyMap())

        assertSame(PluginSandboxPolicy.UNRESTRICTED, effective)
    }
}
