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

import org.pcsoft.framework.pluggiat.sandbox.process.ber.BerCodec
import org.pcsoft.framework.pluggiat.sandbox.process.ber.ProcessResponse
import org.pcsoft.framework.pluggiat.sandbox.process.ber.SandboxValue
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs *inside* a plugin subprocess (see [SubprocessBootstrapMain]): a loopback [ServerSocket]
 * accepting one [org.pcsoft.framework.pluggiat.sandbox.process.ber.ProcessCall]/
 * [ProcessResponse] pair per connection (mirroring [ProcessIpcClient]'s per-call connection model),
 * dispatching each call to the target extension implementation via plain reflection.
 *
 * An implementation instance is instantiated (via its no-arg constructor) lazily on first use and
 * then cached for [implementationClassName], so a stateful extension implementation keeps its state
 * across calls within the subprocess's lifetime, and `PluginLifecycle.onLoad`/`onEnable` (if the
 * caller routes those through IPC too) run against the same instance later calls use.
 *
 * @property classLoader the subprocess's own class loader, built from the plugin's JAR(s) - see
 * [SubprocessBootstrapMain]
 */
class ProcessIpcServer(private val classLoader: ClassLoader) : AutoCloseable {
    private val instances = ConcurrentHashMap<String, Any>()
    private val serverSocket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())

    /** The loopback port this server ended up listening on - passed back to the host process. */
    val port: Int get() = serverSocket.localPort

    /**
     * Accepts and serves connections until [close] is called (from another thread) or the socket is
     * otherwise closed - intended to be run on its own thread by [SubprocessBootstrapMain].
     */
    fun serveForever() {
        while (!serverSocket.isClosed) {
            val socket = try {
                serverSocket.accept()
            } catch (_: Exception) {
                break
            }
            socket.use {
                try {
                    val call = BerCodec.readCall(it.getInputStream())
                    val response = handle(call)
                    BerCodec.writeResponse(response, it.getOutputStream())
                } catch (e: Exception) {
                    runCatching { BerCodec.writeResponse(ProcessResponse.Failure(e.message ?: e::class.java.name), it.getOutputStream()) }
                }
            }
        }
    }

    private fun handle(call: org.pcsoft.framework.pluggiat.sandbox.process.ber.ProcessCall): ProcessResponse {
        return try {
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
}
