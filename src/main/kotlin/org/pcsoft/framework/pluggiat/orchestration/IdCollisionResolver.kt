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
import org.slf4j.LoggerFactory

/**
 * Resolves plugin id collisions between [PluginScanResult]s from different locations (candidates
 * from the same location never collide on id - that is already handled by the respective
 * `org.pcsoft.framework.pluggiat.scanner.PluginScanStrategy`).
 *
 * Only candidates with status [PluginScanStatus.LOADED] (i.e. that already passed the security
 * chain) compete for a colliding plugin id. A candidate with any other status (e.g.
 * [PluginScanStatus.SECURITY_PROBLEM]) is returned unchanged - it cannot displace a [PluginScanStatus.LOADED]
 * candidate of the same id by declaring a higher `manifest.version`, since it never entered the
 * competing group in the first place.
 *
 * Rule among the competing [PluginScanStatus.LOADED] candidates: the one with the strictly higher
 * `manifest.version` (Maven version scheme) wins, the rest of that subgroup is rejected with
 * [PluginScanStatus.ID_COLLISION]. If the highest version is tied between two or more of them, the
 * whole subgroup is rejected immediately with [PluginScanStatus.ID_COLLISION] and a security
 * warning - there is no further tie-breaking (e.g. by checksum).
 */
internal class IdCollisionResolver {
    private val logger = LoggerFactory.getLogger(IdCollisionResolver::class.java)

    /**
     * Returns [results] with every losing/tied [PluginScanStatus.LOADED] candidate of a colliding
     * plugin id group replaced by a copy carrying [PluginScanStatus.ID_COLLISION]; candidates
     * without a manifest, candidates with a non-[PluginScanStatus.LOADED] status, or the sole
     * [PluginScanStatus.LOADED] candidate for their plugin id, are returned unchanged.
     */
    fun resolve(results: List<PluginScanResult>): List<PluginScanResult> {
        val withManifest = results.filter { it.manifest != null }
        val withoutManifest = results.filter { it.manifest == null }
        val loaded = withManifest.filter { it.status == PluginScanStatus.LOADED }
        val notLoaded = withManifest.filter { it.status != PluginScanStatus.LOADED }
        val byId = loaded.groupBy { it.manifest!!.id }

        val resolved = byId.values.flatMap { candidates ->
            if (candidates.size == 1) candidates else resolveGroup(candidates)
        }

        return resolved + notLoaded + withoutManifest
    }

    private fun resolveGroup(candidates: List<PluginScanResult>): List<PluginScanResult> {
        val id = candidates.first().manifest!!.id
        val sortedDescending = candidates.sortedByDescending { ComparableVersion(it.manifest!!.version) }
        val best = sortedDescending[0]
        val runnerUp = sortedDescending[1]

        if (ComparableVersion(best.manifest!!.version) == ComparableVersion(runnerUp.manifest!!.version)) {
            logger.warn(
                "SECURITY WARNING: plugin id '{}' found at the same version {} across {} locations; rejecting all of them",
                id, best.manifest.version, candidates.size,
            )
            return candidates.map { it.copy(status = PluginScanStatus.ID_COLLISION, errorMessage = idCollisionMessage(id, candidates)) }
        }

        logger.warn(
            "Plugin id '{}' found at {} locations; keeping higher version {} at '{}', rejecting the rest",
            id, candidates.size, best.manifest.version, best.path,
        )
        return candidates.map { candidate ->
            if (candidate === best) {
                candidate
            } else {
                candidate.copy(
                    status = PluginScanStatus.ID_COLLISION,
                    errorMessage = "Rejected in favor of higher version ${best.manifest.version} at '${best.path}'",
                )
            }
        }
    }

    private fun idCollisionMessage(id: String, candidates: List<PluginScanResult>): String =
        "Plugin id '$id' found at the same version across multiple locations (${candidates.joinToString { it.path.toString() }}); " +
            "all candidates rejected"
}
