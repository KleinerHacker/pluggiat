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

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.pcsoft.framework.pluggiat.PluginResourceLimits
import java.io.DataOutputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Verifies how [SubprocessBootstrapMain] reacts to a malformed plugin hand-over on its standard input,
 * by starting the class in a real JVM exactly the way [PluginProcessManager] does and feeding it bytes
 * the manager itself would never send.
 */
class SubprocessBootstrapMainTest {

    private class Outcome(val exitCode: Int, val stdout: String, val stderr: String)

    /**
     * Starts the bootstrap main class in its own JVM, lets [feed] write to its standard input (which is
     * closed afterwards) and returns how it ended.
     */
    private fun runBootstrap(feed: (DataOutputStream) -> Unit): Outcome {
        val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java")
        val process = ProcessBuilder(
            java.toString(), "-cp", System.getProperty("java.class.path"),
            SubprocessBootstrapMain::class.java.name, "bootstrap-fixture", "test-token", "",
        ).start()
        DataOutputStream(process.outputStream).use(feed)
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the subprocess must end instead of waiting for more input")
        return Outcome(process.exitValue(), process.inputStream.bufferedReader().readText(), process.errorStream.bufferedReader().readText())
    }

    /**
     * Use case: an announced plugin size of zero is rejected before anything is read, the subprocess ends
     * with a failure exit code and never prints its port handshake line.
     */
    @Test
    fun `a zero length announcement fails the subprocess before the handshake`() {
        val outcome = runBootstrap { it.writeLong(0) }

        assertNotEquals(0, outcome.exitCode)
        assertFalse(outcome.stdout.contains("PLUGGIAT-PORT"))
        assertTrue(outcome.stderr.contains("Announced plugin size"))
    }

    /**
     * Use case: an announced plugin size above [PluginResourceLimits.MAX_CANDIDATE_FILE_SIZE_BYTES] is
     * rejected without the subprocess trying to read or allocate that much, and no handshake line is
     * printed.
     */
    @Test
    fun `an oversized length announcement fails the subprocess before the handshake`() {
        val outcome = runBootstrap { it.writeLong(PluginResourceLimits.MAX_CANDIDATE_FILE_SIZE_BYTES + 1) }

        assertNotEquals(0, outcome.exitCode)
        assertFalse(outcome.stdout.contains("PLUGGIAT-PORT"))
        assertTrue(outcome.stderr.contains("Announced plugin size"))
    }

    /**
     * Use case: fewer bytes than announced (the host died mid-transfer) fail the subprocess instead of
     * letting it start with a truncated plugin, and no handshake line is printed.
     */
    @Test
    fun `a truncated hand-over fails the subprocess before the handshake`() {
        val outcome = runBootstrap {
            it.writeLong(100)
            it.write(ByteArray(10))
        }

        assertNotEquals(0, outcome.exitCode)
        assertFalse(outcome.stdout.contains("PLUGGIAT-PORT"))
        assertTrue(outcome.stderr.contains("incomplete"))
    }

    /**
     * Use case: a subprocess started with fewer than the three mandatory program arguments fails at once
     * instead of falling back to "no authentication" or "everything allowed".
     */
    @Test
    fun `missing program arguments fail the subprocess`() {
        val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java")
        val process = ProcessBuilder(
            java.toString(), "-cp", System.getProperty("java.class.path"),
            SubprocessBootstrapMain::class.java.name, "only-a-plugin-id",
        ).start()
        process.outputStream.close()

        assertTrue(process.waitFor(60, TimeUnit.SECONDS))
        assertNotEquals(0, process.exitValue())
        assertTrue(process.errorStream.bufferedReader().readText().contains("Usage: SubprocessBootstrapMain"))
    }
}
