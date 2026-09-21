package org.pcsoft.framework.pluggiat.scanner

import org.pcsoft.framework.pluggiat.manifest.PluginManifest
import java.nio.file.Path

/**
 * Outcome of scanning a single plugin candidate found at a [PluginLocation].
 */
enum class PluginScanStatus {
    /** The candidate's manifest was found and successfully validated/mapped. */
    LOADED,

    /** No manifest file could be found for this candidate. */
    MANIFEST_NOT_FOUND,

    /** A manifest file was found but failed schema validation or could not be mapped. */
    MANIFEST_INVALID,
}

/**
 * A single plugin candidate found while scanning a [PluginLocation].
 *
 * @property location the location this candidate was found at
 * @property path the candidate's own location on disk (a single JAR, a plugin's own folder, or an
 * unpacked ZIP's temporary folder, depending on the location's [PluginLocation.scanStrategy])
 * @property manifest the candidate's parsed manifest, `null` unless [status] is [PluginScanStatus.LOADED]
 * @property status the outcome of scanning this candidate
 * @property errorMessage human-readable reason, `null` unless [status] is not [PluginScanStatus.LOADED]
 */
data class PluginScanResult(
    val location: PluginLocation,
    val path: Path,
    val manifest: PluginManifest?,
    val status: PluginScanStatus,
    val errorMessage: String? = null,
)
