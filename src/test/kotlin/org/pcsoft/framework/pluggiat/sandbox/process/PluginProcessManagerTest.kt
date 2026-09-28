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
import org.pcsoft.framework.pluggiat.sandbox.process.der.ProcessCall
import org.pcsoft.framework.pluggiat.sandbox.process.der.ProcessResponse
import org.pcsoft.framework.pluggiat.sandbox.process.der.SandboxValue
import org.pcsoft.framework.pluggiat.sandbox.process.fixture.ProcessIsolationFixtureImpl
import org.pcsoft.framework.pluggiat.sandbox.process.fixture.ProcessIsolationFixturePackaging
import org.pcsoft.framework.pluggiat.scanner.PinnedPluginContent
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Verifies [PluginProcessManager]'s own lifecycle (start/stop/isRunning/onCrash), as distinct from
 * `ProcessIsolationStrategyTest`, which only exercises it indirectly through the proxy call path.
 */
class PluginProcessManagerTest {

    private val pluginId = "process-manager-fixture"

    /**
     * Names of the entries of the JVM's temporary directory that look like the working directories the
     * subprocess start used to create for every plugin (`pluggiat-plugin-<id>-...`).
     */
    private fun legacyWorkingDirectories(): Set<String> =
        File(System.getProperty("java.io.tmpdir")).list().orEmpty().filter { it.startsWith("pluggiat-plugin-") }.toSet()

    private fun echoCall(): ProcessCall = ProcessCall(
        implementationClassName = ProcessIsolationFixtureImpl::class.java.name,
        methodName = "echo",
        arguments = listOf(SandboxValue.StringValue("hello")),
    )

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
     * Use case: [PluginProcessManager.start] launches a real subprocess for a ZIP candidate that was never
     * pinned (so it is read once from its path) and hands back a working [ProcessIpcClient];
     * [PluginProcessManager.isRunning] then reports it as running, and calling
     * [PluginProcessManager.start] again for the same plugin id returns the very same client instead of
     * starting a second subprocess.
     */
    @Test
    fun `start launches a subprocess from an unpinned zip and start is idempotent`() {
        val manager = PluginProcessManager()
        val zip = ProcessIsolationFixturePackaging.writeZip()
        try {
            val client = manager.start(pluginId, zip, null, PluginSandboxPolicy.UNRESTRICTED, Duration.ofSeconds(20))

            assertTrue(manager.isRunning(pluginId))

            val response = client.call(echoCall())
            assertEquals(SandboxValue.StringValue("hello"), (response as ProcessResponse.Success).value)

            val sameClient = manager.start(pluginId, zip, null, PluginSandboxPolicy.UNRESTRICTED, Duration.ofSeconds(20))
            assertEquals(client, sameClient)
        } finally {
            manager.stop(pluginId)
        }
    }

    /**
     * Use case: a single JAR whose security-checked bytes are handed over as [PinnedPluginContent.Single]
     * is started from those bytes alone - the given path is never touched - and no working directory of
     * the earlier `pluggiat-plugin-...` kind is created in the temporary directory, because the plugin
     * reaches the subprocess through its standard input and stays in memory.
     */
    @Test
    fun `start launches a subprocess from pinned jar bytes without creating a working directory`() {
        val manager = PluginProcessManager()
        val jarBytes = Files.readAllBytes(ProcessIsolationFixturePackaging.writeSingleJar())
        val before = legacyWorkingDirectories()
        try {
            val client = manager.start(
                pluginId, Path.of("does-not-exist.jar"), PinnedPluginContent.Single(jarBytes),
                PluginSandboxPolicy.UNRESTRICTED, Duration.ofSeconds(20),
            )

            val response = client.call(echoCall())
            assertEquals(SandboxValue.StringValue("hello"), (response as ProcessResponse.Success).value)
            assertEquals(before, legacyWorkingDirectories())
        } finally {
            manager.stop(pluginId)
        }
    }

    /**
     * Use case: a ZIP of JARs whose bytes are handed over as [PinnedPluginContent.Single] is resolved in
     * memory inside the subprocess (the nested JARs are merged, exactly as in the host), so the plugin
     * classes from one inner JAR and the Kotlin standard library from the other are both available.
     */
    @Test
    fun `start launches a subprocess from pinned zip bytes`() {
        val manager = PluginProcessManager()
        val zipBytes = Files.readAllBytes(ProcessIsolationFixturePackaging.writeZip())
        try {
            val client = manager.start(
                pluginId, Path.of("does-not-exist.zip"), PinnedPluginContent.Single(zipBytes),
                PluginSandboxPolicy.UNRESTRICTED, Duration.ofSeconds(20),
            )

            val response = client.call(echoCall())
            assertEquals(SandboxValue.StringValue("hello"), (response as ProcessResponse.Success).value)
        } finally {
            manager.stop(pluginId)
        }
    }

    /**
     * Use case: an unpinned plugin whose path cannot be read (here: it does not exist) fails
     * [PluginProcessManager.start] with a [ProcessIsolationStartupException] naming the plugin, before any
     * subprocess is started.
     */
    @Test
    fun `start throws ProcessIsolationStartupException when an unpinned plugin cannot be read`() {
        val manager = PluginProcessManager()

        val exception = assertThrows(ProcessIsolationStartupException::class.java) {
            manager.start(
                pluginId, Path.of("missing-plugin-file.jar"), null,
                PluginSandboxPolicy.UNRESTRICTED, Duration.ofSeconds(20),
            )
        }

        assertTrue(exception.message!!.contains(pluginId))
        assertFalse(manager.isRunning(pluginId))
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
        val zip = ProcessIsolationFixturePackaging.writeZip()
        manager.start(pluginId, zip, null, PluginSandboxPolicy.UNRESTRICTED, Duration.ofSeconds(20))

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
        val zip = ProcessIsolationFixturePackaging.writeZip()
        val client = manager.start(pluginId, zip, null, PluginSandboxPolicy.UNRESTRICTED, Duration.ofSeconds(20))

        assertThrows(Exception::class.java) {
            client.call(
                ProcessCall(
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
     * never announced its port - even though the plugin bytes are still being handed to it.
     */
    @Test
    fun `start throws ProcessIsolationStartupException when the handshake does not complete in time`() {
        val manager = PluginProcessManager()
        val zip = ProcessIsolationFixturePackaging.writeZip()

        val exception = assertThrows(ProcessIsolationStartupException::class.java) {
            manager.start(pluginId, zip, null, PluginSandboxPolicy.UNRESTRICTED, Duration.ofNanos(1))
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
