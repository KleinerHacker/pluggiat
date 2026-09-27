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
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.pcsoft.framework.pluggiat.sandbox.process.ber.ProcessCall
import org.pcsoft.framework.pluggiat.sandbox.process.ber.ProcessResponse
import org.pcsoft.framework.pluggiat.sandbox.process.ber.SandboxValue
import java.time.Duration

/**
 * Verifies [ProcessIpcServer]'s authentication of incoming calls. The server's port is a loopback port
 * and therefore reachable by every process on the machine, so the IPC token - not the connection - is
 * what distinguishes the host's calls from anyone else's.
 */
class ProcessIpcServerTest {

    private val serverToken = "expected-ipc-token"

    /**
     * The call target the server would instantiate and invoke. Records whether it was ever constructed,
     * which is how a test can tell "rejected before dispatch" from "rejected afterwards".
     */
    class EchoTarget {
        init {
            instantiations++
        }

        fun echo(input: String): String = input

        companion object {
            @Volatile
            var instantiations: Int = 0
        }
    }

    private fun echoCall(): ProcessCall = ProcessCall(
        implementationClassName = EchoTarget::class.java.name,
        methodName = "echo",
        arguments = listOf(SandboxValue.StringValue("hello")),
    )

    private fun <T> withServer(block: (ProcessIpcServer) -> T): T {
        EchoTarget.instantiations = 0
        val server = ProcessIpcServer(javaClass.classLoader, serverToken)
        val serverThread = Thread { server.serveForever() }.apply { isDaemon = true; name = "test-ipc-server" }
        serverThread.start()
        try {
            return block(server)
        } finally {
            server.close()
            serverThread.join(Duration.ofSeconds(5).toMillis())
        }
    }

    /**
     * Use case: a call presenting a token other than the server's is answered with a plain failure and -
     * crucially - never dispatched: the named class is not even instantiated, so a foreign local process
     * cannot use this server to construct or invoke anything inside the subprocess.
     */
    @Test
    fun `a call with a wrong token is rejected without being dispatched`() {
        withServer { server ->
            val client = ProcessIpcClient("example", server.port, Duration.ofSeconds(10), "wrong-ipc-token")

            val response = client.call(echoCall())

            val failure = assertInstanceOf(ProcessResponse.Failure::class.java, response)
            assertEquals(ProcessIpcServer.UNAUTHENTICATED_FAILURE_MESSAGE, failure.message)
            assertEquals(0, EchoTarget.instantiations)
        }
    }

    /**
     * Use case: an empty token is treated like any other wrong token - a caller that simply omits the
     * secret gains nothing.
     */
    @Test
    fun `a call with an empty token is rejected`() {
        withServer { server ->
            val client = ProcessIpcClient("example", server.port, Duration.ofSeconds(10), "")

            val response = client.call(echoCall())

            val failure = assertInstanceOf(ProcessResponse.Failure::class.java, response)
            assertEquals(ProcessIpcServer.UNAUTHENTICATED_FAILURE_MESSAGE, failure.message)
            assertEquals(0, EchoTarget.instantiations)
        }
    }

    /**
     * Use case: a call presenting the server's own token is dispatched normally - the authentication
     * rejects foreign callers without breaking the legitimate one.
     */
    @Test
    fun `a call with the servers token is dispatched`() {
        withServer { server ->
            val client = ProcessIpcClient("example", server.port, Duration.ofSeconds(10), serverToken)

            val response = client.call(echoCall())

            val success = assertInstanceOf(ProcessResponse.Success::class.java, response)
            assertEquals(SandboxValue.StringValue("hello"), success.value)
            assertEquals(1, EchoTarget.instantiations)
        }
    }

    /**
     * Use case: a rejected call leaves the server usable - it keeps serving the legitimate host instead
     * of shutting down or wedging on the unauthenticated connection.
     */
    @Test
    fun `the server keeps serving after a rejected call`() {
        withServer { server ->
            ProcessIpcClient("example", server.port, Duration.ofSeconds(10), "wrong-ipc-token").call(echoCall())

            val response = ProcessIpcClient("example", server.port, Duration.ofSeconds(10), serverToken).call(echoCall())

            assertInstanceOf(ProcessResponse.Success::class.java, response)
            assertFalse(response is ProcessResponse.Failure)
        }
    }
}
