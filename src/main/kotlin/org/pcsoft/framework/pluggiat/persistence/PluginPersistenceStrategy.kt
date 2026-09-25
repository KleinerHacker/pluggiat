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
 * Generic key-value persistence for per-plugin framework and host state (e.g. the accepted
 * checksum of a plugin, or its enabled/disabled status).
 *
 * Exactly one instance is configured for the whole framework, via
 * `org.pcsoft.framework.pluggiat.PluginManagerConfiguration.persistenceStrategy`. Implementations are
 * free to share storage across all plugin ids and keys however they like; from the framework's
 * point of view, each `(pluginId, key)` pair addresses one independent value.
 */
interface PluginPersistenceStrategy {

    /**
     * Reads the value stored for `(pluginId, key)`, or `null` if none is stored.
     */
    fun read(pluginId: String, key: String): String?

    /**
     * Writes [value] for `(pluginId, key)`, replacing any previously stored value.
     */
    fun write(pluginId: String, key: String, value: String)
}
