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

import org.pcsoft.framework.pluggiat.PluginResourceLimits
import org.pcsoft.framework.pluggiat.sandbox.PluginSandboxPolicy
import org.pcsoft.framework.pluggiat.sandbox.agent.PluginSandboxAgent
import org.pcsoft.framework.pluggiat.scanner.PinnedPluginContent
import org.pcsoft.framework.pluggiat.scanner.PinnedPluginContentReader
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * One running subprocess of a process-isolated plugin, with everything needed to talk to it.
 */
internal data class ManagedProcess(val process: Process, val ipcClient: ProcessIpcClient)

/**
 * Starts, tracks and stops the JVM subprocesses of process-isolated plugins (IP-04) and hands out one
 * [ProcessIpcClient] per plugin id.
 *
 * Each subprocess is started with:
 * * the sandbox Java agent (`-javaagent:<this module's own JAR>`), so the plugin's classes are
 *   instrumented for API mediation inside the subprocess exactly as they would be in the host - process
 *   isolation alone bounds *what a plugin can reach through the host*, not what it does with the JDK,
 *   and a subprocess without the agent would be a strictly weaker sandbox than an in-VM one;
 * * a freshly generated, per-subprocess IPC token, which every call has to present (see
 *   [ProcessIpcServer]);
 * * the plugin's effective set of allowed [org.pcsoft.framework.pluggiat.sandbox.SandboxApiCategory]
 *   values, which the subprocess registers as its own policy;
 * * the plugin's security-checked bytes (a single JAR, or a ZIP of JARs) on its standard input, which the
 *   subprocess loads entirely in memory - nothing of the plugin is ever written to disk.
 *
 * @property onCrash invoked with the plugin id whenever a tracked subprocess exits without having been
 * [stop]ped
 */
class PluginProcessManager(private val onCrash: (pluginId: String) -> Unit = {}) {
    private val logger = LoggerFactory.getLogger(PluginProcessManager::class.java)
    private val processes = ConcurrentHashMap<String, ManagedProcess>()

    fun isRunning(pluginId: String): Boolean = processes.containsKey(pluginId)

    /**
     * Starts (or returns the already-running) subprocess for [pluginId] under [policy].
     *
     * The plugin's bytes reach the subprocess through its standard input (an 8-byte length followed by
     * the bytes), written by a dedicated daemon thread: a subprocess that never reads them can therefore
     * not block the calling thread, and [startupTimeout] (together with the forced termination of a
     * subprocess that misses it) bounds the whole start.
     *
     * @param pluginPath the plugin's own location, read once when [pinnedContent] is `null`
     * @param pinnedContent the plugin's security-checked bytes, handed to the subprocess as they are;
     * `null` falls back to reading [pluginPath] from disk, which keeps the check-to-load window open
     * @param startupTimeout upper bound for the subprocess's port handshake
     * @throws ProcessIsolationStartupException if the plugin's bytes could not be read, the subprocess
     * could not be started or did not complete its handshake
     */
    fun start(
        pluginId: String,
        pluginPath: Path,
        pinnedContent: PinnedPluginContent?,
        policy: PluginSandboxPolicy,
        startupTimeout: Duration,
    ): ProcessIpcClient {
        processes[pluginId]?.let { return it.ipcClient }

        val content = pinnedContent ?: run {
            logger.warn(
                "Starting the subprocess of process-isolated plugin '{}' from its path instead of its " +
                    "security-checked bytes - the candidate was never pinned, so it could have changed on disk since the check",
                pluginId,
            )
            try {
                PinnedPluginContentReader.read(pluginPath)
            } catch (e: Exception) {
                throw ProcessIsolationStartupException(pluginId, "Failed to read the plugin at '$pluginPath': ${e.message}", e)
            }
        }
        val pluginBytes = (content as PinnedPluginContent.Single).bytes
        if (pluginBytes.isEmpty() || pluginBytes.size > PluginResourceLimits.MAX_CANDIDATE_FILE_SIZE_BYTES) {
            throw ProcessIsolationStartupException(pluginId, "The plugin's size (${pluginBytes.size} bytes) is outside the supported range")
        }

        // A fresh 256-bit secret per subprocess: it is the only thing separating the host's calls from
        // those of any other local process that finds the subprocess's loopback port.
        val token = newIpcToken()
        val command = buildList {
            // SECURITY: the same JVM that runs the host, so the subprocess cannot be steered to a different
            // SECURITY: (older, unpatched) runtime through PATH.
            add(javaExecutable())
            // SECURITY: the agent makes the subprocess mediate guarded APIs at all; ProcessIsolationStrategy
            // SECURITY: refuses a restrictive policy when there is no agent JAR to pass here.
            sandboxAgentJar()?.let { add("-javaagent:$it") }
            add("-cp")
            add(System.getProperty("java.class.path"))
            add(SubprocessBootstrapMain::class.java.name)
            add(pluginId)
            add(token)
            // SECURITY: the effective allow-list travels with the subprocess, so it enforces the same policy
            // SECURITY: the host would - an empty list means nothing is allowed, not everything.
            add(policy.allowedApiCategories.joinToString(",") { it.name })
        }
        val process = try {
            ProcessBuilder(command)
                .directory(File(System.getProperty("java.io.tmpdir")))
                .redirectErrorStream(false)
                .start()
        } catch (e: Exception) {
            throw ProcessIsolationStartupException(pluginId, "Failed to start subprocess: ${e.message}", e)
        }

        drainStderrInBackground(pluginId, process)
        writePluginBytesInBackground(pluginId, process, pluginBytes)

        val port = try {
            readHandshakePort(process, startupTimeout)
        } catch (e: Exception) {
            // Also breaks the pipe the writer thread may still be blocked on.
            process.destroyForcibly()
            throw ProcessIsolationStartupException(pluginId, e.message ?: "handshake failed", e)
        }

        // SECURITY: only this client knows the token, so only its calls are accepted by the subprocess.
        val ipcClient = ProcessIpcClient(pluginId, port, policy.callTimeout, token)
        val managed = ManagedProcess(process, ipcClient)
        processes[pluginId] = managed

        process.onExit().thenAccept {
            val stillTracked = processes.remove(pluginId, managed)
            if (stillTracked) {
                logger.warn("Subprocess of process-isolated plugin '{}' exited unexpectedly (exit code {})", pluginId, it.exitValue())
                onCrash(pluginId)
            }
        }

        return ipcClient
    }

    fun stop(pluginId: String, gracePeriod: Duration = Duration.ofSeconds(3)) {
        val managed = processes.remove(pluginId) ?: return
        val process = managed.process
        process.destroy()
        if (!process.waitFor(gracePeriod.toMillis().coerceAtLeast(1), TimeUnit.MILLISECONDS)) {
            logger.warn("Subprocess of plugin '{}' did not exit within {}, escalating to destroyForcibly()", pluginId, gracePeriod)
            process.destroyForcibly()
        }
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

    /**
     * Writes [bytes] (preceded by their length as an 8-byte big-endian number) to the subprocess's
     * standard input and closes it. Runs on its own daemon thread because a pipe write blocks for as
     * long as the subprocess does not read: a failure here (typically a subprocess that already died)
     * is only logged, since the handshake wait in [start] is what reports the failed start.
     */
    private fun writePluginBytesInBackground(pluginId: String, process: Process, bytes: ByteArray) {
        Thread {
            try {
                DataOutputStream(process.outputStream).use { output ->
                    output.writeLong(bytes.size.toLong())
                    output.write(bytes)
                    output.flush()
                }
            } catch (e: Exception) {
                logger.debug("Could not hand the plugin bytes to the subprocess of plugin '{}': {}", pluginId, e.message)
            }
        }.apply { isDaemon = true; name = "pluggiat-process-stdin-$pluginId" }.start()
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

    companion object {
        private val secureRandom = SecureRandom()

        /** Length of a generated IPC token before Base64 encoding. */
        private const val IPC_TOKEN_BYTES = 32

        /**
         * A fresh IPC token: [IPC_TOKEN_BYTES] bytes from [SecureRandom] (never
         * [java.util.Random]/`Math.random`, whose output is predictable from a few observed values),
         * Base64-URL encoded so it survives being passed as a plain program argument.
         *
         * Known limitation: a program argument is visible to other processes of the same machine (e.g.
         * through `ps`), so the token authenticates the host against *unrelated* local processes, not
         * against a local attacker who can already enumerate this user's process arguments.
         */
        private fun newIpcToken(): String {
            val bytes = ByteArray(IPC_TOKEN_BYTES)
            // SECURITY: SecureRandom, never java.util.Random - a predictable token is no token at all.
            secureRandom.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        /**
         * This module's own JAR, which doubles as the sandbox Java agent (see `build.gradle.kts`), or
         * `null` when the framework runs from an exploded class directory (a plain `./gradlew test`, an
         * IDE run) where there is no agent JAR to pass.
         *
         * Resolved from [PluginSandboxAgent]'s own code source rather than from a configured path, so
         * the subprocess is always instrumented by the very same build of the agent that is running in
         * the host.
         */
        internal fun sandboxAgentJar(): Path? {
            // SECURITY: derived from the agent class's own code source, not from configuration - the subprocess
            // SECURITY: is always instrumented by the exact build running in the host.
            val location = runCatching { PluginSandboxAgent::class.java.protectionDomain?.codeSource?.location }.getOrNull() ?: return null
            val path = runCatching { Path.of(location.toURI()) }.getOrNull() ?: return null
            return path.takeIf { it.toString().endsWith(".jar", ignoreCase = true) && Files.isRegularFile(it) }
        }
    }
}

class ProcessIsolationStartupException(pluginId: String, reason: String, cause: Throwable? = null) :
    RuntimeException("Process isolation subprocess for plugin '$pluginId' could not be started: $reason", cause)
