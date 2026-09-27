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
import org.pcsoft.framework.pluggiat.sandbox.process.ber.ProcessCall
import org.pcsoft.framework.pluggiat.sandbox.process.ber.ProcessResponse
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.time.Duration

/**
 * The host side of IP-04's process-isolation IPC: opens one loopback connection per call to the
 * subprocess's [ProcessIpcServer] and exchanges one [ProcessCall]/[ProcessResponse] pair over it.
 *
 * @property pluginId the process-isolated plugin this client talks to, for error reporting
 * @property port the loopback port the subprocess's server announced during its startup handshake
 * @property callTimeout socket read timeout per call, from the plugin's effective
 * [org.pcsoft.framework.pluggiat.sandbox.PluginSandboxPolicy]; `null` waits indefinitely
 * @property token the shared secret [PluginProcessManager] generated for this subprocess and passed
 * to it at startup; sent as the first field of every call so the subprocess can tell this client's
 * calls apart from those of any other local process that guessed its port
 */
class ProcessIpcClient(
    private val pluginId: String,
    private val port: Int,
    private val callTimeout: Duration? = null,
    private val token: String,
) {
    fun call(call: ProcessCall): ProcessResponse {
        // SECURITY: loopback only - the subprocess is a local collaborator, never a network peer.
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            // SECURITY: the policy's call timeout also bounds the *transport*: a subprocess that stops
            // SECURITY: answering cannot pin the calling host thread indefinitely.
            callTimeout?.let { socket.soTimeout = it.toMillis().toInt().coerceAtLeast(1) }
            try {
                // SECURITY: the token goes out with every single call - the connection itself proves nothing.
                BerCodec.writeCall(call, token, socket.getOutputStream())
                return BerCodec.readResponse(socket.getInputStream())
            } catch (_: SocketTimeoutException) {
                throw org.pcsoft.framework.pluggiat.sandbox.SandboxTimeoutException(pluginId, callTimeout ?: Duration.ZERO)
            }
        }
    }
}
