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
 * A [PluginPersistenceStrategy] that delegates every read/write to a pair of host-provided
 * functions, without prescribing how or where the host actually stores the value - e.g. against an
 * in-memory map, a host's own settings object, or any other backing store the host already owns.
 *
 * Both functions receive the full `(pluginId, key)` pair, so the host is free to choose its own
 * storage shape (a single `Map<Pair<String, String>, String>`, one field per key, a nested map keyed
 * by plugin id, etc.) - this strategy itself holds no state of its own.
 *
 * @property getter reads the value stored for `(pluginId, key)`, or `null` if none is stored; called once per [read]
 * @property setter stores a value for `(pluginId, key)`, replacing any previously stored value; called once per [write]
 */
class ObjectPersistenceStrategy(
    private val getter: (pluginId: String, key: String) -> String?,
    private val setter: (pluginId: String, key: String, value: String) -> Unit,
) : PluginPersistenceStrategy {

    /**
     * Delegates to [getter].
     *
     * @param pluginId the plugin the value is stored for
     * @param key the key the value is stored under
     * @return the value [getter] returns for `(pluginId, key)`
     */
    override fun read(pluginId: String, key: String): String? = getter(pluginId, key)

    /**
     * Delegates to [setter].
     *
     * @param pluginId the plugin the value is stored for
     * @param key the key the value is stored under
     * @param value the value to store
     */
    override fun write(pluginId: String, key: String, value: String) = setter(pluginId, key, value)
}
