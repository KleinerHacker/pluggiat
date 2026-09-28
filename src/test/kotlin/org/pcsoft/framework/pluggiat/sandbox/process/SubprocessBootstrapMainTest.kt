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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.pcsoft.framework.pluggiat.PluginResourceLimits
import org.pcsoft.framework.pluggiat.sandbox.process.der.ProcessCall
import org.pcsoft.framework.pluggiat.sandbox.process.der.ProcessResponse
import org.pcsoft.framework.pluggiat.sandbox.process.der.SandboxValue
import org.pcsoft.framework.pluggiat.sandbox.process.fixture.ProcessIsolationFixtureImpl
import org.pcsoft.framework.pluggiat.sandbox.process.fixture.ProcessIsolationFixturePackaging
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Verifies how [SubprocessBootstrapMain] reacts to a malformed plugin hand-over on its standard input,
 * by starting the class in a real JVM exactly the way [PluginProcessManager] does and feeding it bytes
 * the manager itself would never send. The same paths are additionally driven in-process (by calling
 * [SubprocessBootstrapMain.main] directly with a replaced standard input), which is what makes them
 * visible to the coverage measurement of this test JVM.
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

    /** Builds the stdin hand-over: an 8-byte big-endian [announced] length followed by [payload]. */
    private fun handOver(announced: Long, payload: ByteArray = ByteArray(0)): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use {
            it.writeLong(announced)
            it.write(payload)
        }
        return buffer.toByteArray()
    }

    /** Runs [block] with [bytes] installed as the JVM's standard input and restores the original afterwards. */
    private fun <T> withStdin(bytes: ByteArray, block: () -> T): T {
        val original = System.`in`
        System.setIn(ByteArrayInputStream(bytes))
        try {
            return block()
        } finally {
            System.setIn(original)
        }
    }

    private val inProcessArgs = arrayOf("bootstrap-in-process", "in-process-token", "")

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

    /**
     * Use case (in-process): calling [SubprocessBootstrapMain.main] with fewer than three arguments
     * throws an [IllegalArgumentException] carrying the usage text.
     */
    @Test
    fun `in-process main rejects missing program arguments`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            SubprocessBootstrapMain.main(arrayOf("only-a-plugin-id"))
        }

        assertTrue(exception.message!!.contains("Usage: SubprocessBootstrapMain"))
    }

    /**
     * Use case (in-process): an announced size of zero is rejected with an [IllegalArgumentException]
     * before any plugin byte is read.
     */
    @Test
    fun `in-process main rejects a zero length announcement`() {
        val exception = withStdin(handOver(0)) {
            assertThrows(IllegalArgumentException::class.java) { SubprocessBootstrapMain.main(inProcessArgs) }
        }

        assertTrue(exception.message!!.contains("Announced plugin size"))
    }

    /**
     * Use case (in-process): an announced size above the candidate size limit is rejected with an
     * [IllegalArgumentException] without reading or allocating that much.
     */
    @Test
    fun `in-process main rejects an oversized length announcement`() {
        val exception = withStdin(handOver(PluginResourceLimits.MAX_CANDIDATE_FILE_SIZE_BYTES + 1)) {
            assertThrows(IllegalArgumentException::class.java) { SubprocessBootstrapMain.main(inProcessArgs) }
        }

        assertTrue(exception.message!!.contains("Announced plugin size"))
    }

    /**
     * Use case (in-process): fewer plugin bytes than announced fail with an [IllegalArgumentException]
     * naming the expected and the received count instead of starting with a truncated plugin.
     */
    @Test
    fun `in-process main rejects a truncated hand-over`() {
        val exception = withStdin(handOver(100, ByteArray(10))) {
            assertThrows(IllegalArgumentException::class.java) { SubprocessBootstrapMain.main(inProcessArgs) }
        }

        assertTrue(exception.message!!.contains("incomplete"))
    }

    /**
     * Use case (in-process): an unknown API category name in the allow-list argument fails the start
     * instead of being skipped, because a policy that silently lost a restriction is worse than a plugin
     * that does not start.
     */
    @Test
    fun `in-process main rejects an unknown api category`() {
        assertThrows(IllegalArgumentException::class.java) {
            SubprocessBootstrapMain.main(arrayOf("bootstrap-in-process", "in-process-token", "FILESYSTEM,NO_SUCH_CATEGORY"))
        }
    }

    /**
     * Use case (in-process): with valid arguments (a comma-separated category list with blanks) and a
     * valid plugin ZIP on standard input, the bootstrap registers the policy, prints its port handshake
     * line and then serves authenticated calls - the successful start path of a process-isolated plugin.
     */
    @Test
    fun `in-process main starts the server and answers an authenticated call`() {
        val zipBytes = Files.readAllBytes(ProcessIsolationFixturePackaging.writeZip())
        val originalIn = System.`in`
        val originalOut = System.out
        val captured = ByteArrayOutputStream()
        System.setIn(ByteArrayInputStream(handOver(zipBytes.size.toLong(), zipBytes)))
        System.setOut(PrintStream(captured, true))
        val port: Int
        try {
            Thread {
                SubprocessBootstrapMain.main(arrayOf("bootstrap-in-process", "in-process-token", "FILESYSTEM, NETWORK,"))
            }.apply { isDaemon = true; name = "test-bootstrap-main"; start() }

            val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
            while (!captured.toString().contains("PLUGGIAT-PORT:") && System.nanoTime() < deadline) {
                Thread.sleep(20)
            }
            val line = captured.toString().lines().firstOrNull { it.startsWith("PLUGGIAT-PORT:") }
            assertTrue(line != null, "the handshake line must be printed")
            port = line!!.removePrefix("PLUGGIAT-PORT:").trim().toInt()
        } finally {
            System.setOut(originalOut)
            System.setIn(originalIn)
        }

        val response = ProcessIpcClient("bootstrap-in-process", port, Duration.ofSeconds(10), "in-process-token").call(
            ProcessCall(
                implementationClassName = ProcessIsolationFixtureImpl::class.java.name,
                methodName = "echo",
                arguments = listOf(SandboxValue.StringValue("hello")),
            ),
        )

        assertEquals(SandboxValue.StringValue("hello"), (response as ProcessResponse.Success).value)
    }
}
