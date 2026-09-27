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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.pcsoft.framework.pluggiat.sandbox.PluginSandboxPolicy
import org.pcsoft.framework.pluggiat.sandbox.process.fixture.FixturePerson
import org.pcsoft.framework.pluggiat.sandbox.process.fixture.ProcessIsolationFixtureApi
import org.pcsoft.framework.pluggiat.sandbox.process.fixture.ProcessIsolationFixtureImpl
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * Verifies [PluginProcessManager]'s own lifecycle (start/stop/isRunning/onCrash), as distinct from
 * `ProcessIsolationStrategyTest`, which only exercises it indirectly through the proxy call path.
 */
class PluginProcessManagerTest {

    private val pluginId = "process-manager-fixture"

    /**
     * Packages the already-compiled fixture classes into a temporary folder location, exactly like
     * `ProcessIsolationStrategyTest.buildFixtureJar` - a real, startable process-isolated "plugin".
     */
    private fun buildFixtureJar(): Path {
        val folder = Files.createTempDirectory("plugin-process-manager-fixture-")
        val jarPath = folder.resolve("fixture.jar")
        JarOutputStream(Files.newOutputStream(jarPath)).use { jar ->
            for (fixtureClass in listOf(ProcessIsolationFixtureApi::class.java, ProcessIsolationFixtureImpl::class.java, FixturePerson::class.java)) {
                val resourceName = fixtureClass.name.replace('.', '/') + ".class"
                val bytes = requireNotNull(fixtureClass.classLoader.getResourceAsStream(resourceName)) {
                    "Compiled class resource not found: $resourceName"
                }.use { it.readBytes() }
                jar.putNextEntry(JarEntry(resourceName))
                jar.write(bytes)
                jar.closeEntry()
            }
        }
        val kotlinStdlibJar = System.getProperty("java.class.path")
            .split(File.pathSeparatorChar)
            .map { Path.of(it) }
            .first { it.toString().contains("kotlin-stdlib") }
        Files.copy(kotlinStdlibJar, folder.resolve(kotlinStdlibJar.fileName), StandardCopyOption.REPLACE_EXISTING)
        return folder
    }

    /**
     * Use case: before a plugin's subprocess was ever started, [PluginProcessManager.isRunning]
     * reports it as not running.
     */
    @Test
    fun `isRunning is false for a plugin that was never started`() {
        val manager = PluginProcessManager()

        assertFalse(manager.isRunning(pluginId))
    }

    /**
     * Use case: [PluginProcessManager.start] launches a real subprocess and hands back a working
     * [ProcessIpcClient]; [PluginProcessManager.isRunning] then reports it as running, and calling
     * [PluginProcessManager.start] again for the same plugin id returns the very same client instead of
     * starting a second subprocess.
     */
    @Test
    fun `start launches a subprocess and start is idempotent`() {
        val manager = PluginProcessManager()
        val jar = buildFixtureJar()
        try {
            val client = manager.start(pluginId, jar, null, PluginSandboxPolicy.UNRESTRICTED, Duration.ofSeconds(20))

            assertTrue(manager.isRunning(pluginId))

            val response = client.call(
                org.pcsoft.framework.pluggiat.sandbox.process.der.ProcessCall(
                    implementationClassName = ProcessIsolationFixtureImpl::class.java.name,
                    methodName = "echo",
                    arguments = listOf(org.pcsoft.framework.pluggiat.sandbox.process.der.SandboxValue.StringValue("hello")),
                ),
            )
            assertEquals(
                org.pcsoft.framework.pluggiat.sandbox.process.der.SandboxValue.StringValue("hello"),
                (response as org.pcsoft.framework.pluggiat.sandbox.process.der.ProcessResponse.Success).value,
            )

            val sameClient = manager.start(pluginId, jar, null, PluginSandboxPolicy.UNRESTRICTED, Duration.ofSeconds(20))
            assertEquals(client, sameClient)
        } finally {
            manager.stop(pluginId)
        }
    }

    /**
     * Use case: [PluginProcessManager.stop] terminates the subprocess and [PluginProcessManager.isRunning]
     * then reports it as no longer running, without invoking [PluginProcessManager]'s crash callback (a
     * deliberate stop is not a crash).
     */
    @Test
    fun `stop terminates the subprocess without reporting a crash`() {
        var crashReported = false
        val manager = PluginProcessManager(onCrash = { crashReported = true })
        val jar = buildFixtureJar()
        manager.start(pluginId, jar, null, PluginSandboxPolicy.UNRESTRICTED, Duration.ofSeconds(20))

        manager.stop(pluginId)

        assertFalse(manager.isRunning(pluginId))
        Thread.sleep(300)
        assertFalse(crashReported)
    }

    /**
     * Use case: a subprocess that exits on its own (crashes) without having been [PluginProcessManager.stop]ped
     * is reported through [PluginProcessManager]'s `onCrash` callback, and is no longer tracked as running
     * afterward.
     */
    @Test
    fun `a subprocess that crashes on its own is reported through onCrash`() {
        val crashLatch = CountDownLatch(1)
        val manager = PluginProcessManager(onCrash = { crashLatch.countDown() })
        val jar = buildFixtureJar()
        val client = manager.start(pluginId, jar, null, PluginSandboxPolicy.UNRESTRICTED, Duration.ofSeconds(20))

        assertThrows(Exception::class.java) {
            client.call(
                org.pcsoft.framework.pluggiat.sandbox.process.der.ProcessCall(
                    implementationClassName = ProcessIsolationFixtureImpl::class.java.name,
                    methodName = "crash",
                    arguments = emptyList(),
                ),
            )
        }

        assertTrue(crashLatch.await(10, TimeUnit.SECONDS))
        assertFalse(manager.isRunning(pluginId))
    }

    /**
     * Use case: when the subprocess does not complete its port handshake within the given
     * `startupTimeout` (here: effectively zero), [PluginProcessManager.start] throws
     * [ProcessIsolationStartupException] instead of hanging or returning a client for a subprocess that
     * never announced its port.
     */
    @Test
    fun `start throws ProcessIsolationStartupException when the handshake does not complete in time`() {
        val manager = PluginProcessManager()
        val jar = buildFixtureJar()

        val exception = assertThrows(ProcessIsolationStartupException::class.java) {
            manager.start(pluginId, jar, null, PluginSandboxPolicy.UNRESTRICTED, Duration.ofNanos(1))
        }

        assertTrue(exception.message!!.contains(pluginId))
        assertFalse(manager.isRunning(pluginId))
    }

    /**
     * Use case: [PluginProcessManager.stop] for a plugin id that was never started is a harmless no-op.
     */
    @Test
    fun `stop for a plugin that was never started is a no-op`() {
        val manager = PluginProcessManager()

        manager.stop("never-started-plugin")

        assertFalse(manager.isRunning("never-started-plugin"))
    }

    /**
     * Use case: [PluginProcessManager.sandboxAgentJar] returns `null` when the framework runs from an
     * exploded class directory (as in this developer test), since there is no agent JAR to hand to a
     * subprocess.
     */
    @Test
    fun `sandboxAgentJar is either absent or a real jar file`() {
        val agentJar = PluginProcessManager.sandboxAgentJar()

        // Running via `./gradlew test` (no -javaagent, exploded classes) this is expected to be null;
        // if the suite ever runs against the packaged JAR itself, a real, existing jar path is equally valid.
        assertTrue(agentJar == null || (agentJar.toString().endsWith(".jar") && Files.isRegularFile(agentJar)))
    }
}
