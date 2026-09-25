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
 * [PluginDependencyStrategy] that, in addition to a plugin location always seeing itself, allows
 * visibility onto the explicitly configured [allowedLocations].
 *
 * @property allowedLocations locations a plugin location may additionally see, besides itself
 */
class LocationPluginDependencyStrategy(
    private val allowedLocations: Set<Path>,
) : PluginDependencyStrategy {
    override fun isVisible(from: Path, to: Path): Boolean = from == to || to in allowedLocations
}
