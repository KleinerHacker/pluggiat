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

package org.pcsoft.framework.pluggiat.security

import org.pcsoft.framework.pluggiat.scanner.PluginScanResult

/**
 * A [PluginSecurityStrategy] that can persist state derived from a checked candidate, so a later
 * check succeeds through the regular chain instead of requiring a repeated force-load.
 *
 * Used by `org.pcsoft.framework.pluggiat.PluginManager.write` to let a host persist a strategy's
 * accepted state (e.g. [ChecksumSecurityStrategy] accepting a candidate's current checksum) without
 * needing to know that strategy's persistence key or how to recompute its value itself.
 */
interface PersistableSecurityStrategy : PluginSecurityStrategy {

    /**
     * Persists whatever this strategy needs from [result] so a future [PluginSecurityStrategy.check]
     * for [pluginId] succeeds.
     */
    fun persist(pluginId: String, result: PluginScanResult)
}
