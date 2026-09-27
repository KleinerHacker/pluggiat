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

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.pcsoft.framework.pluggiat.sandbox.SandboxTimeoutException
import org.pcsoft.framework.pluggiat.sandbox.process.der.ProcessCall
import org.pcsoft.framework.pluggiat.sandbox.process.der.SandboxValue
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration

/**
 * Verifies [ProcessIpcClient.call]'s transport-level timeout handling. [ProcessIpcClient]'s
 * successful and rejected-call paths are already exercised end-to-end against a real
 * [ProcessIpcServer] in `ProcessIpcServerTest`.
 */
class ProcessIpcClientTest {

    /**
     * Use case: a subprocess that accepts the connection but never answers causes the client's socket
     * read to time out, which is translated into a [SandboxTimeoutException] naming the plugin - not
     * left as a raw [java.net.SocketTimeoutException] the caller would not expect.
     */
    @Test
    fun `a call that never receives a response times out with a SandboxTimeoutException`() {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { silentServer ->
            val acceptThread = Thread {
                runCatching { silentServer.accept()?.use { Thread.sleep(Duration.ofSeconds(5).toMillis()) } }
            }.apply { isDaemon = true; start() }

            val client = ProcessIpcClient(
                pluginId = "example-plugin",
                port = silentServer.localPort,
                callTimeout = Duration.ofMillis(200),
                token = "irrelevant-token",
            )
            val call = ProcessCall(
                implementationClassName = "unused.Class",
                methodName = "unused",
                arguments = listOf(SandboxValue.StringValue("unused")),
            )

            val exception = assertThrows(SandboxTimeoutException::class.java) { client.call(call) }

            assert(exception.message!!.contains("example-plugin"))
            acceptThread.interrupt()
        }
    }
}
