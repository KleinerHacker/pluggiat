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

import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * A single plugin's live subprocess: the OS [process] itself plus the [ipcClient] connected to its
 * [ProcessIpcServer].
 */
internal data class ManagedProcess(val process: Process, val ipcClient: ProcessIpcClient, val workingDirectory: Path)

/**
 * Starts, tracks and tears down one JVM subprocess per process-isolated plugin id, on behalf of
 * [ProcessIsolationStrategy] - IP-04's subprocess lifecycle management (task 4 of its
 * implementation plan).
 *
 * A subprocess is a plain `java` JVM (resolved from `java.home`, see [javaExecutable]) running
 * [SubprocessBootstrapMain] as its main class, its own classpath set to the *current* JVM's own
 * classpath (`java.class.path`) so it always finds [SubprocessBootstrapMain]/[ProcessIpcServer]/the
 * `bcprov-jdk18on` classes regardless of whether the host runs from a built JAR or straight from
 * Gradle's test classpath; the plugin's own JAR(s) are passed as [SubprocessBootstrapMain]'s single
 * program argument and loaded there into their own, separate `URLClassLoader` - the plugin's classes
 * are therefore never on the subprocess JVM's own classpath, exactly mirroring the in-VM isolation
 * [org.pcsoft.framework.pluggiat.classloader.PluginClassLoader] provides.
 *
 * Each subprocess gets its own fresh, empty temporary working directory (no access to the host's
 * current working directory), is expected to print exactly one `PLUGGIAT-PORT:<port>` handshake line
 * to stdout, and is watched for an unexpected exit via [Process.onExit] - see [onCrash].
 *
 * @property onCrash invoked with a plugin id whenever its subprocess exits without a prior [stop] -
 * wired by [ProcessIsolationStrategy] to report a [org.pcsoft.framework.pluggiat.sandbox.SandboxViolation]
 * exactly like an IP-03 timeout (category `null`, not [org.pcsoft.framework.pluggiat.sandbox.SandboxApiCategory] -
 * see the FP-002 feature plan's IP-05 scope note)
 */
class PluginProcessManager(private val onCrash: (pluginId: String) -> Unit = {}) {
    private val logger = LoggerFactory.getLogger(PluginProcessManager::class.java)
    private val processes = ConcurrentHashMap<String, ManagedProcess>()

    /** Whether [pluginId] currently has a live, started subprocess. */
    fun isRunning(pluginId: String): Boolean = processes.containsKey(pluginId)

    /**
     * Starts a fresh subprocess for [pluginId] loading [jarPaths], unless one is already running (in
     * which case the existing one is reused). Blocks until the subprocess's `PLUGGIAT-PORT:` handshake
     * line was read or [startupTimeout] elapses.
     *
     * @throws ProcessIsolationStartupException if the subprocess could not be started, or did not
     * complete its handshake within [startupTimeout]
     */
    fun start(pluginId: String, jarPaths: List<Path>, startupTimeout: Duration, callTimeout: Duration?): ProcessIpcClient {
        processes[pluginId]?.let { return it.ipcClient }

        val workingDirectory = Files.createTempDirectory("pluggiat-plugin-$pluginId-")
        val classpath = jarPaths.joinToString(File.pathSeparator) { it.toAbsolutePath().toString() }
        val command = listOf(
            javaExecutable(),
            "-cp", System.getProperty("java.class.path"),
            SubprocessBootstrapMain::class.java.name,
            classpath,
        )
        val process = try {
            ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(false)
                .start()
        } catch (e: Exception) {
            throw ProcessIsolationStartupException(pluginId, "Failed to start subprocess: ${e.message}", e)
        }

        drainStderrInBackground(pluginId, process)

        val port = try {
            readHandshakePort(process, startupTimeout)
        } catch (e: Exception) {
            process.destroyForcibly()
            throw ProcessIsolationStartupException(pluginId, e.message ?: "handshake failed", e)
        }

        val ipcClient = ProcessIpcClient(pluginId, port, callTimeout)
        val managed = ManagedProcess(process, ipcClient, workingDirectory)
        processes[pluginId] = managed

        process.onExit().thenAccept {
            val stillTracked = processes.remove(pluginId, managed)
            if (stillTracked) {
                logger.warn("Subprocess of process-isolated plugin '{}' exited unexpectedly (exit code {})", pluginId, it.exitValue())
                onCrash(pluginId)
            }
            runCatching { workingDirectory.toFile().deleteRecursively() }
        }

        return ipcClient
    }

    /**
     * Orderly shuts down [pluginId]'s subprocess, if any: `destroy()`, escalating to
     * `destroyForcibly()` if it has not exited within [gracePeriod].
     */
    fun stop(pluginId: String, gracePeriod: Duration = Duration.ofSeconds(3)) {
        val managed = processes.remove(pluginId) ?: return
        val process = managed.process
        process.destroy()
        if (!process.waitFor(gracePeriod.toMillis().coerceAtLeast(1), TimeUnit.MILLISECONDS)) {
            logger.warn("Subprocess of plugin '{}' did not exit within {}, escalating to destroyForcibly()", pluginId, gracePeriod)
            process.destroyForcibly()
        }
        runCatching { managed.workingDirectory.toFile().deleteRecursively() }
    }

    private fun readHandshakePort(process: Process, startupTimeout: Duration): Int {
        val reader: BufferedReader = process.inputStream.bufferedReader()
        val deadline = System.nanoTime() + startupTimeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) error("Subprocess exited before completing its handshake (exit code ${process.exitValue()})")
            if (reader.ready()) {
                val line = reader.readLine() ?: error("Subprocess closed stdout before completing its handshake")
                val prefix = "PLUGGIAT-PORT:"
                if (line.startsWith(prefix)) {
                    drainStdoutInBackground(reader)
                    return line.removePrefix(prefix).trim().toInt()
                }
                logger.debug("Ignoring unexpected subprocess stdout line before handshake: {}", line)
            } else {
                Thread.sleep(10)
            }
        }
        error("Subprocess did not complete its handshake within $startupTimeout")
    }

    private fun drainStdoutInBackground(reader: BufferedReader) {
        Thread {
            runCatching { reader.lineSequence().forEach { logger.debug("[subprocess stdout] {}", it) } }
        }.apply { isDaemon = true; name = "pluggiat-process-stdout" }.start()
    }

    private fun drainStderrInBackground(pluginId: String, process: Process) {
        Thread {
            runCatching {
                process.errorStream.bufferedReader().lineSequence().forEach { logger.warn("[subprocess:{} stderr] {}", pluginId, it) }
            }
        }.apply { isDaemon = true; name = "pluggiat-process-stderr-$pluginId" }.start()
    }

    private fun javaExecutable(): String {
        val javaHome = System.getProperty("java.home")
        val exe = if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
        return Path.of(javaHome, "bin", exe).toString()
    }
}

/**
 * Thrown by [PluginProcessManager.start] when a process-isolated plugin's subprocess could not be
 * started or failed to complete its handshake in time.
 */
class ProcessIsolationStartupException(pluginId: String, reason: String, cause: Throwable? = null) :
    RuntimeException("Process isolation subprocess for plugin '$pluginId' could not be started: $reason", cause)
