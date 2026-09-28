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

package org.pcsoft.framework.pluggiat.sandbox.process

import com.example.sandboxfixture.SandboxAgentFixtureApi
import com.example.sandboxfixture.SandboxAgentFixtureJar
import java.time.Duration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.pcsoft.framework.pluggiat.classloader.LoadedPlugin
import org.pcsoft.framework.pluggiat.classloader.PluginClassLoader
import org.pcsoft.framework.pluggiat.manifest.PluginManifest
import org.pcsoft.framework.pluggiat.sandbox.PluginSandboxPolicy
import org.pcsoft.framework.pluggiat.sandbox.SandboxApiCategory
import org.pcsoft.framework.pluggiat.sandbox.SandboxCheckResult
import org.pcsoft.framework.pluggiat.sandbox.SandboxIsolationLevel

/**
 * Verifies that a process-isolated plugin is mediated *inside its own subprocess* as well, not just
 * separated from the host: [PluginProcessManager] passes this module's JAR as the subprocess's
 * `-javaagent` and its policy as a program argument, and [SubprocessBootstrapMain] registers that
 * policy there.
 *
 * Belongs to the `sandboxAgentTest` source set because both halves need the real agent JAR: it is what
 * the subprocess is instrumented with, and it only exists once the `jar` task has run.
 */
class ProcessIsolationPolicyEnforcementTest {
    private val pluginId = "agent-process-fixture"
    private var strategy: ProcessIsolationStrategy? = null

    @AfterEach
    fun tearDown() {
        strategy?.stop(pluginId)
    }

    private fun newStrategy(): ProcessIsolationStrategy =
        ProcessIsolationStrategy(startupTimeout = Duration.ofSeconds(30)).also { strategy = it }

    private fun manifest(): PluginManifest =
        PluginManifest(id = pluginId, name = pluginId, version = "1.0.0", minVersion = "1.0.0", icon = "aWNvbg==")

    /**
     * Use case: a guarded call made by a process-isolated plugin *inside its subprocess* under a policy
     * that allows no category is blocked there, and surfaces in the host as a
     * [ProcessIsolatedCallException]; an unguarded call of the same plugin keeps working over the same
     * subprocess afterwards, proving the subprocess was mediated rather than merely broken.
     */
    @Test
    fun `subprocess blocks a category its policy does not allow`() {
        val zip = SandboxAgentFixtureJar.writeZipWithKotlinStdlib()
        val policy = PluginSandboxPolicy(
            allowedApiCategories = emptySet(),
            isolationLevel = SandboxIsolationLevel.PROCESS,
            callTimeout = Duration.ofSeconds(30),
        )
        val proxy = newStrategy().createExtensionProxy(
            pluginId, zip, null, SandboxAgentFixtureApi::class.java,
            SandboxAgentFixtureJar.IMPLEMENTATION_CLASS_NAME, policy,
        ) as SandboxAgentFixtureApi

        val error = org.junit.jupiter.api.Assertions.assertThrows(ProcessIsolatedCallException::class.java) {
            proxy.touchFilesystem()
        }

        assertTrue(error.message?.contains(pluginId) == true)
        assertEquals("OK", proxy.harmlessEcho("ok"))
    }

    /**
     * Use case: activating a restricted process-isolated policy succeeds when the agent JAR is available
     * (as it is here), so the subprocess can actually be instrumented - the positive counterpart to
     * `ProcessIsolationStrategyTest`, which asserts the refusal when the framework runs from an exploded
     * class directory and has no agent JAR to hand over.
     */
    @Test
    fun `activate accepts a restricted process isolated policy when the agent jar exists`() {
        val classLoader = PluginClassLoader(emptyArray(), javaClass.classLoader, emptyList(), emptyList())
        val policy = PluginSandboxPolicy(
            allowedApiCategories = setOf(SandboxApiCategory.FILESYSTEM),
            isolationLevel = SandboxIsolationLevel.PROCESS,
        )

        val result = newStrategy().activate(LoadedPlugin(pluginId, manifest(), classLoader), policy)

        assertEquals(SandboxCheckResult.Success, result)
        classLoader.close()
    }
}
