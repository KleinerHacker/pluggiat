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

package org.pcsoft.framework.pluggiat.classloader

/**
 * Outcome of [PluginLoader.load].
 */
sealed interface PluginLoadResult {
    /** The plugin was loaded successfully. */
    data class Loaded(val plugin: LoadedPlugin) : PluginLoadResult

    /**
     * The plugin could not be loaded, e.g. due to a missing `required` dependency.
     *
     * @property pluginId id of the invalid plugin, `null` if it could not be determined
     * @property reason human-readable reason
     */
    data class Invalid(val pluginId: String?, val reason: String) : PluginLoadResult
}
