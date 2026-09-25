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
 * A single security check applied to a scanned plugin candidate (e.g. no check, signature,
 * checksum) as part of a location's ordered fallback chain.
 *
 * Custom, framework-external implementations can be added to a location's fallback chain without
 * changing any framework code.
 */
interface PluginSecurityStrategy {

    /**
     * Checks [result], which is guaranteed to have [org.pcsoft.framework.pluggiat.scanner.PluginScanStatus.LOADED]
     * as its status (i.e. its manifest was already successfully resolved).
     */
    fun check(result: PluginScanResult): PluginSecurityCheckResult
}
