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

import org.pcsoft.framework.pluggiat.scanner.PinnedPluginContent
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
     *
     * Re-reads [result]'s candidate bytes from disk itself; prefer the [PinnedPluginContent]
     * overload wherever the candidate's bytes were already pinned (see [PluginSecurity.evaluate]),
     * to close the TOCTOU window between this check and the later
     * `org.pcsoft.framework.pluggiat.classloader.PluginLoader.load` call.
     */
    fun check(result: PluginScanResult): PluginSecurityCheckResult

    /**
     * Checks [result] against its already-pinned [pinnedContent] instead of re-reading the
     * candidate from disk. Defaults to delegating to [check] for a strategy that does not need
     * pinned bytes (e.g. one that only inspects [result.manifest][PluginScanResult.manifest]).
     */
    fun check(result: PluginScanResult, pinnedContent: PinnedPluginContent): PluginSecurityCheckResult = check(result)
}
