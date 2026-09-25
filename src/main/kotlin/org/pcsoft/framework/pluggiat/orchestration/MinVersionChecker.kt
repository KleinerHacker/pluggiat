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

package org.pcsoft.framework.pluggiat.orchestration

import org.apache.maven.artifact.versioning.ComparableVersion
import org.pcsoft.framework.pluggiat.scanner.PluginScanResult
import org.pcsoft.framework.pluggiat.scanner.PluginScanStatus

/**
 * Checks a scanned plugin candidate's `manifest.minVersion` against the host's own version (Maven
 * version scheme), as configured via `org.pcsoft.framework.pluggiat.PluginManagerConfiguration.hostVersion`.
 */
internal object MinVersionChecker {

    /**
     * Returns [result] unchanged if [hostVersion] is `null` (the check is skipped, `minVersion` is
     * an optional feature) or satisfies `result.manifest.minVersion`; otherwise returns a copy
     * carrying [PluginScanStatus.MIN_VERSION_VIOLATION].
     */
    fun check(result: PluginScanResult, hostVersion: String?): PluginScanResult {
        if (hostVersion == null) return result
        val manifest = requireNotNull(result.manifest) { "check() must only be called for LOADED results" }

        val required = ComparableVersion(manifest.minVersion)
        val actual = ComparableVersion(hostVersion)
        if (actual >= required) return result

        return result.copy(
            status = PluginScanStatus.MIN_VERSION_VIOLATION,
            errorMessage = "Plugin '${manifest.id}' requires host version >= ${manifest.minVersion}, host is $hostVersion",
        )
    }
}
