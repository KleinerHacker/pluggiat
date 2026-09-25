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

package org.pcsoft.framework.pluggiat.scanner

/**
 * Scans a single [PluginLocation] for plugin candidates.
 *
 * A load mode is not represented as a separate enum; it is fully expressed by the concrete
 * [PluginScanStrategy] implementation used by a [PluginLocation].
 */
interface PluginScanStrategy {
    /**
     * Scans [location] and returns one [PluginScanResult] per plugin candidate found, both valid
     * and invalid ones.
     */
    fun scan(location: PluginLocation): List<PluginScanResult>
}
