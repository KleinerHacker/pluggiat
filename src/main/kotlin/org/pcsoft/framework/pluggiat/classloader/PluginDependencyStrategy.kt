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

import java.nio.file.Path

/**
 * Decides whether plugins loaded from one location may declare visible dependencies on plugins
 * loaded from another location, as part of a location's dependency-visibility configuration
 * (global default, optionally overridden per location).
 *
 * A plugin location is always allowed to see other plugins within itself ([from] == [to]),
 * regardless of the configured strategy - the only way to suppress even same-location
 * dependencies is [DisallowPluginDependencyStrategy].
 */
interface PluginDependencyStrategy {
    /**
     * Whether a plugin loaded from location [from] may see (declare a dependency on) a plugin
     * loaded from location [to].
     */
    fun isVisible(from: Path, to: Path): Boolean
}
