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

package org.pcsoft.framework.pluggiat.extension

/**
 * A single extension entry that has been resolved and instantiated for one plugin.
 *
 * @property key the extension point key this entry was contributed to
 * @property pluginId id of the plugin that contributed this entry
 * @property configuration the fully mapped, host-defined configuration object
 * @property instance the instantiated plugin implementation, implementing the extension point's host plugin API interface
 */
data class ResolvedExtension(
    val key: String,
    val pluginId: String,
    val configuration: ExtensionConfiguration<*>,
    val instance: Any,
)
