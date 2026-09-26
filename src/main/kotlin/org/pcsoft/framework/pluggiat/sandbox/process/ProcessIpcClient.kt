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
 * Host-side IPC client for one process-isolated plugin's subprocess (see
 * [ProcessIsolationStrategy]/[PluginProcessManager]), connecting to the loopback [port] its
 * [ProcessIpcServer] listens on.
 *
 * Opens a fresh loopback [Socket] per [call] rather than keeping one long-lived connection: it keeps
 * request/response correlation trivial (exactly one [org.pcsoft.framework.pluggiat.sandbox.process.ber.ProcessCall]/
 * [org.pcsoft.framework.pluggiat.sandbox.process.ber.ProcessResponse] pair per connection, no
 * multiplexing needed) at the cost of a per-call TCP handshake, which is negligible next to a JVM
 * subprocess round trip.
 *
 * @property port loopback TCP port the subprocess's [ProcessIpcServer] listens on
 * @property callTimeout upper bound for a single call's socket I/O, mirrored from
 * [org.pcsoft.framework.pluggiat.sandbox.PluginSandboxPolicy.callTimeout] - `null` means no
 * socket-level bound (the call is still bounded by
 * [org.pcsoft.framework.pluggiat.sandbox.PluginSandbox.runGoverned]'s own [org.pcsoft.framework.pluggiat.sandbox.ThreadWatchdog]
 * whenever the proxy call runs under it, see [ProcessIsolationStrategy])
 */
class ProcessIpcClient(private val pluginId: String, private val port: Int, private val callTimeout: Duration? = null) {
    /**
     * Sends [call] to the subprocess and returns its decoded [ProcessResponse].
     *
     * @throws java.io.IOException if the connection could not be established or failed mid-call
     * (e.g. the subprocess crashed) - see [ProcessIsolationStrategy] for how this is turned into a
     * reported [org.pcsoft.framework.pluggiat.sandbox.SandboxViolation]
     * @throws org.pcsoft.framework.pluggiat.sandbox.SandboxTimeoutException if [callTimeout] is set
     * and the subprocess does not answer within it
     */
    fun call(call: ProcessCall): ProcessResponse {
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            callTimeout?.let { socket.soTimeout = it.toMillis().toInt().coerceAtLeast(1) }
            try {
                BerCodec.writeCall(call, socket.getOutputStream())
                return BerCodec.readResponse(socket.getInputStream())
            } catch (_: SocketTimeoutException) {
                throw org.pcsoft.framework.pluggiat.sandbox.SandboxTimeoutException(pluginId, callTimeout ?: Duration.ZERO)
            }
        }
    }
}
