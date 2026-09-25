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

package org.pcsoft.framework.pluggiat.persistence

/**
 * Host-provided lookup for [CustomPersistenceStrategy.read].
 *
 * Must be a cheap, synchronous call.
 */
fun interface PersistenceReadCallback {
    fun read(pluginId: String, key: String): String?
}

/**
 * Host-provided storage for [CustomPersistenceStrategy.write].
 *
 * Must be a cheap, synchronous call.
 */
fun interface PersistenceWriteCallback {
    fun write(pluginId: String, key: String, value: String)
}

/**
 * A [PluginPersistenceStrategy] delegating to host-provided function interfaces, for hosts that
 * already have their own storage mechanism.
 *
 * @property readCallback host-provided lookup invoked from [read]
 * @property writeCallback host-provided storage invoked from [write]
 */
class CustomPersistenceStrategy(
    private val readCallback: PersistenceReadCallback,
    private val writeCallback: PersistenceWriteCallback,
) : PluginPersistenceStrategy {
    override fun read(pluginId: String, key: String): String? = readCallback.read(pluginId, key)

    override fun write(pluginId: String, key: String, value: String) {
        writeCallback.write(pluginId, key, value)
    }
}
