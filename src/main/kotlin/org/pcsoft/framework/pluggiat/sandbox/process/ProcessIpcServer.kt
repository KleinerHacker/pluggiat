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

import org.pcsoft.framework.pluggiat.sandbox.process.der.DerCodec
import org.pcsoft.framework.pluggiat.sandbox.process.der.ProcessCall
import org.pcsoft.framework.pluggiat.sandbox.process.der.ProcessResponse
import org.pcsoft.framework.pluggiat.sandbox.process.der.SandboxValue
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The subprocess side of IP-04's process-isolation IPC: serves one [ProcessCall] per accepted
 * loopback connection by instantiating (once, then cached) and invoking the named extension
 * implementation inside this subprocess, and answers with a [ProcessResponse].
 *
 * The listening socket is bound to the loopback address only, but a loopback port is reachable by
 * *every* process of the machine, not only by the host that started this subprocess - a port is not a
 * credential. Each call therefore has to present [token], the secret the host generated for exactly
 * this subprocess, and a call that does not is answered with a plain failure and never dispatched:
 * otherwise any local process could drive a process-isolated plugin's extension methods, with
 * arbitrary arguments, through this server.
 *
 * @property classLoader the class loader the plugin's extension implementations are loaded from - a
 * [org.pcsoft.framework.pluggiat.classloader.PluginClassLoader] built by [SubprocessBootstrapMain],
 * so the subprocess enforces the same class isolation (and instrumentation) as the host would
 * @property token the shared secret every accepted call must present as its first field
 */
class ProcessIpcServer(
    private val classLoader: ClassLoader,
    private val token: String,
) : AutoCloseable {
    private val instances = ConcurrentHashMap<String, Any>()
    // SECURITY: bound to the loopback address only, so the plugin's IPC endpoint is never reachable from
    // SECURITY: the network - port 0 lets the OS pick a port nobody can predict in advance.
    private val serverSocket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())

    val port: Int get() = serverSocket.localPort

    fun serveForever() {
        while (!serverSocket.isClosed) {
            val socket = try {
                serverSocket.accept()
            } catch (_: Exception) {
                break
            }
            socket.use {
                try {
                    // Bounds how long one connection can occupy this single-threaded accept loop: a
                    // peer that connects and then never sends anything would otherwise block every
                    // further call of the legitimate host indefinitely.
                    it.soTimeout = ACCEPTED_SOCKET_READ_TIMEOUT_MILLIS
                    val authenticated = DerCodec.readCall(it.getInputStream())
                    // SECURITY: the token is checked before the call is dispatched, so an unauthenticated peer
                    // SECURITY: never gets a class resolved or a method invoked on its behalf.
                    val response = if (isAuthentic(authenticated.token)) {
                        handle(authenticated.call)
                    } else {
                        ProcessResponse.Failure(UNAUTHENTICATED_FAILURE_MESSAGE)
                    }
                    DerCodec.writeResponse(response, it.getOutputStream())
                } catch (e: Exception) {
                    runCatching { DerCodec.writeResponse(ProcessResponse.Failure(e.message ?: e::class.java.name), it.getOutputStream()) }
                }
            }
        }
    }

    /**
     * Whether [presented] is this subprocess's [token]. Compared with [MessageDigest.isEqual] rather
     * than with `==`: a length-independent, non-short-circuiting comparison denies an attacker the
     * timing signal that would let them recover the token byte by byte instead of guessing all of it
     * at once.
     */
    private fun isAuthentic(presented: String): Boolean =
        // SECURITY: constant-time comparison - a short-circuiting `==` would leak, through timing, how much of
        // SECURITY: a guessed token was right and turn guessing into a per-character search.
        MessageDigest.isEqual(
            presented.toByteArray(StandardCharsets.UTF_8),
            token.toByteArray(StandardCharsets.UTF_8),
        )

    private fun handle(call: ProcessCall): ProcessResponse {
        return try {
            // SECURITY: resolved through the subprocess's PluginClassLoader, so the class comes from the
            // SECURITY: plugin's own (pinned) JARs and is subject to this subprocess's sandbox policy.
            val instance = instances.getOrPut(call.implementationClassName) {
                classLoader.loadClass(call.implementationClassName).getDeclaredConstructor().newInstance()
            }
            val method = instance::class.java.methods.firstOrNull {
                it.name == call.methodName && it.parameterCount == call.arguments.size
            } ?: error("No method '${call.methodName}' with ${call.arguments.size} parameter(s) found on ${call.implementationClassName}")

            val args = call.arguments.mapIndexed { index, value ->
                SandboxTypeSupport.decode(value, method.genericParameterTypes[index])
            }.toTypedArray()

            val result = method.invoke(instance, *args)
            val returnValue = if (method.returnType == Void.TYPE) SandboxValue.UnitValue else SandboxTypeSupport.encode(result)
            ProcessResponse.Success(returnValue)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            ProcessResponse.Failure((e.targetException ?: e).let { it.message ?: it::class.java.name })
        } catch (e: Exception) {
            ProcessResponse.Failure(e.message ?: e::class.java.name)
        }
    }

    override fun close() {
        serverSocket.close()
    }

    companion object {
        /**
         * Read timeout applied to every accepted connection, see [serveForever]. Generous enough that
         * no legitimate call (which writes its message immediately after connecting) can hit it.
         */
        const val ACCEPTED_SOCKET_READ_TIMEOUT_MILLIS: Int = 30_000

        /**
         * Failure message returned for a call whose token does not match. Deliberately says nothing
         * about *why* it was rejected beyond the fact itself.
         */
        const val UNAUTHENTICATED_FAILURE_MESSAGE: String = "Rejected: the call did not present this subprocess's IPC token"
    }
}
