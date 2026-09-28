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

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.pcsoft.framework.pluggiat.sandbox.PluginSandboxPolicy
import org.pcsoft.framework.pluggiat.sandbox.SandboxTimeoutException
import org.pcsoft.framework.pluggiat.sandbox.process.fixture.FixturePerson
import org.pcsoft.framework.pluggiat.sandbox.process.fixture.ProcessIsolationFixtureApi
import org.pcsoft.framework.pluggiat.sandbox.process.fixture.ProcessIsolationFixtureImpl
import org.pcsoft.framework.pluggiat.sandbox.process.fixture.ProcessIsolationFixturePackaging
import java.nio.file.Path
import java.time.Duration

/**
 * Verifies [ProcessIsolationStrategy]'s cross-process extension proxy end to end, against a real
 * subprocess JVM (see [SubprocessBootstrapMain]/[ProcessIpcServer]) - the slowest developer tests in
 * this suite (real JVM startup per test), but the only way to genuinely exercise the socket/DER wire
 * format and process lifecycle rather than just their pieces in isolation.
 */
class ProcessIsolationStrategyTest {
    private val pluginId = "process-isolated-fixture"
    private var strategy: ProcessIsolationStrategy? = null

    @AfterEach
    fun tearDown() {
        strategy?.stop(pluginId)
    }

    private fun newStrategy(): ProcessIsolationStrategy = ProcessIsolationStrategy(startupTimeout = Duration.ofSeconds(20)).also { strategy = it }

    /**
     * Packages the already-compiled [ProcessIsolationFixtureApi]/[ProcessIsolationFixtureImpl] `.class`
     * files (found via the test classpath, exactly as Gradle compiled them) together with a copy of the
     * Kotlin standard library JAR into a temporary ZIP (the fixture classes' compiler-generated
     * null-check intrinsics need it at runtime, and the subprocess deliberately has no access to the
     * host's own application classpath) - usable as a process-isolated "plugin" ZIP candidate for
     * [ProcessIsolationStrategy.createExtensionProxy], read once from its path because it is not pinned.
     */
    private fun buildFixtureJar(): Path = ProcessIsolationFixturePackaging.writeZip()

    /**
     * Use case: activating a process-isolated policy that restricts at least one API category fails with
     * [org.pcsoft.framework.pluggiat.sandbox.agent.SandboxAgentNotActiveException] when no agent JAR is
     * available to hand to the subprocess - as here, where the framework runs from an exploded class
     * directory. Without the agent the subprocess would run the plugin entirely unmediated, which would
     * make process isolation a weaker sandbox than in-VM mediation; the positive case (agent JAR present)
     * is covered by `ProcessIsolationPolicyEnforcementTest` in the `sandboxAgentTest` source set.
     */
    @Test
    fun `activate refuses a restricted policy without an agent jar`() {
        val classLoader = org.pcsoft.framework.pluggiat.classloader.PluginClassLoader(
            emptyArray(), javaClass.classLoader, emptyList(), emptyList(),
        )
        val plugin = org.pcsoft.framework.pluggiat.classloader.LoadedPlugin(
            pluginId,
            org.pcsoft.framework.pluggiat.manifest.PluginManifest(
                id = pluginId, name = pluginId, version = "1.0.0", minVersion = "1.0.0", icon = "aWNvbg==",
            ),
            classLoader,
        )
        val policy = PluginSandboxPolicy(
            allowedApiCategories = setOf(org.pcsoft.framework.pluggiat.sandbox.SandboxApiCategory.FILESYSTEM),
            isolationLevel = org.pcsoft.framework.pluggiat.sandbox.SandboxIsolationLevel.PROCESS,
        )

        assertThrows(org.pcsoft.framework.pluggiat.sandbox.agent.SandboxAgentNotActiveException::class.java) {
            newStrategy().activate(plugin, policy)
        }
        classLoader.close()
    }

    /**
     * Use case: an unrestricted process-isolated policy needs no agent at all (there is nothing to
     * mediate), so activation succeeds even from an exploded class directory.
     */
    @Test
    fun `activate accepts an unrestricted process isolated policy without an agent jar`() {
        val classLoader = org.pcsoft.framework.pluggiat.classloader.PluginClassLoader(
            emptyArray(), javaClass.classLoader, emptyList(), emptyList(),
        )
        val plugin = org.pcsoft.framework.pluggiat.classloader.LoadedPlugin(
            pluginId,
            org.pcsoft.framework.pluggiat.manifest.PluginManifest(
                id = pluginId, name = pluginId, version = "1.0.0", minVersion = "1.0.0", icon = "aWNvbg==",
            ),
            classLoader,
        )
        val policy = PluginSandboxPolicy(isolationLevel = org.pcsoft.framework.pluggiat.sandbox.SandboxIsolationLevel.PROCESS)

        assertEquals(
            org.pcsoft.framework.pluggiat.sandbox.SandboxCheckResult.Success,
            newStrategy().activate(plugin, policy),
        )
        classLoader.close()
    }

    /**
     * Use case: a supported extension method call is encoded, sent across the process boundary,
     * executed against the real (subprocess-instantiated) implementation, and its decoded result is
     * returned unchanged to the caller.
     */
    @Test
    fun `successful extension call is proxied across the process boundary`() {
        val jar = buildFixtureJar()
        val proxy = newStrategy().createExtensionProxy(
            pluginId, jar, null, ProcessIsolationFixtureApi::class.java,
            ProcessIsolationFixtureImpl::class.java.name, PluginSandboxPolicy.UNRESTRICTED,
        ) as ProcessIsolationFixtureApi

        assertEquals("hello", proxy.echo("hello"))
        assertEquals(5L, proxy.add(2, 3))
    }

    /**
     * Use case: a complex-object (`FixturePerson` data class) parameter/return value is proxied across
     * the process boundary as a `SandboxValue.ObjectValue` (ASN.1 SET/SEQUENCE) and reconstructed on
     * both ends without loss - including its nested `List<String>` field.
     */
    @Test
    fun `complex object call is proxied across the process boundary`() {
        val jar = buildFixtureJar()
        val proxy = newStrategy().createExtensionProxy(
            pluginId, jar, null, ProcessIsolationFixtureApi::class.java,
            ProcessIsolationFixtureImpl::class.java.name, PluginSandboxPolicy.UNRESTRICTED,
        ) as ProcessIsolationFixtureApi

        val result = proxy.birthday(FixturePerson(name = "Ada", age = 36, nicknames = listOf("Countess")))

        assertEquals(FixturePerson(name = "Ada", age = 37, nicknames = listOf("Countess")), result)
    }

    /**
     * Use case: an unsupported parameter type (`Map`) throws [UnsupportedSandboxTypeException]
     * immediately at the proxy call site, without the subprocess ever being started/contacted -
     * verified by checking the subprocess is still not running afterward.
     */
    @Test
    fun `unsupported signature throws immediately without contacting the subprocess`() {
        val jar = buildFixtureJar()
        val isolationStrategy = newStrategy()
        val proxy = isolationStrategy.createExtensionProxy(
            pluginId, jar, null, ProcessIsolationFixtureApi::class.java,
            ProcessIsolationFixtureImpl::class.java.name, PluginSandboxPolicy.UNRESTRICTED,
        ) as ProcessIsolationFixtureApi

        assertThrows(UnsupportedSandboxTypeException::class.java) {
            proxy.unsupported(mapOf("a" to "b"))
        }
    }

    /**
     * Use case: a subprocess that crashes (halts) mid-call surfaces as a [ProcessIsolationIoException]
     * (wrapping the underlying transport-level `IOException` as its cause) to the caller, instead of
     * silently hanging, corrupting a later call, or being wrapped in an opaque
     * `UndeclaredThrowableException` by the JDK dynamic proxy.
     */
    @Test
    fun `subprocess crash during a call surfaces as an IOException`() {
        val jar = buildFixtureJar()
        val proxy = newStrategy().createExtensionProxy(
            pluginId, jar, null, ProcessIsolationFixtureApi::class.java,
            ProcessIsolationFixtureImpl::class.java.name, PluginSandboxPolicy.UNRESTRICTED,
        ) as ProcessIsolationFixtureApi

        val ex = assertThrows(ProcessIsolationIoException::class.java) { proxy.crash() }
        assertTrue(ex.cause is java.io.IOException)
    }

    /**
     * Use case: a hanging subprocess call is aborted once [PluginSandboxPolicy.callTimeout] elapses,
     * reusing the same [SandboxTimeoutException] IP-03's [org.pcsoft.framework.pluggiat.sandbox.ThreadWatchdog] throws.
     */
    @Test
    fun `hanging subprocess call is aborted by the sandbox timeout`() {
        val jar = buildFixtureJar()
        val policy = PluginSandboxPolicy(callTimeout = Duration.ofMillis(500))
        val proxy = newStrategy().createExtensionProxy(
            pluginId, jar, null, ProcessIsolationFixtureApi::class.java,
            ProcessIsolationFixtureImpl::class.java.name, policy,
        ) as ProcessIsolationFixtureApi

        val ex = assertThrows(SandboxTimeoutException::class.java) { proxy.hang() }
        assertTrue(ex.message?.contains(pluginId) == true)
    }
}
