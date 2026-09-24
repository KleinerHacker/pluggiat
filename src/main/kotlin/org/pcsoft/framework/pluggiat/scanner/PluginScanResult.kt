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

    /**
     * The candidate's manifest was valid, but it failed every strategy of its location's security
     * fallback chain (see `org.pcsoft.framework.pluggiat.security.PluginSecurity`).
     */
    SECURITY_PROBLEM,

    /**
     * The candidate had a valid manifest and (if checked) passed security, but lost against another
     * candidate with the same plugin id from a different location, or was rejected together with it
     * because both had the same version but could not be told apart
     * (see `org.pcsoft.framework.pluggiat.orchestration.IdCollisionResolver`).
     */
    ID_COLLISION,

    /**
     * The candidate's manifest declares a `minVersion` newer than the host's configured version
     * (see `org.pcsoft.framework.pluggiat.orchestration.MinVersionChecker`).
     */
    MIN_VERSION_VIOLATION,

    /**
     * The candidate passed scanning, security, id collision and `minVersion` checks, but
     * `org.pcsoft.framework.pluggiat.classloader.PluginLoader.load` still failed (e.g. a missing
     * required dependency or a class loading error).
     */
    LOAD_FAILED,
}

/**
 * A single plugin candidate found while scanning a [PluginLocation].
 *
 * @property location the location this candidate was found at
 * @property path the candidate's own location on disk (a single JAR, a plugin's own folder, or the
 * ZIP archive itself, depending on the location's [PluginLocation.scanStrategy])
 * @property manifest the candidate's parsed manifest; `null` only for [PluginScanStatus.MANIFEST_NOT_FOUND]
 * and [PluginScanStatus.MANIFEST_INVALID], present for every other status including the
 * orchestration-level ones ([PluginScanStatus.ID_COLLISION], [PluginScanStatus.MIN_VERSION_VIOLATION],
 * [PluginScanStatus.LOAD_FAILED])
 * @property status the outcome of scanning (and, once assigned by `PluginManager.scan`, orchestrating) this candidate
 * @property errorMessage human-readable reason, `null` unless [status] is not [PluginScanStatus.LOADED]
 */
data class PluginScanResult(
    val location: PluginLocation,
    val path: Path,
    val manifest: PluginManifest?,
    val status: PluginScanStatus,
    val errorMessage: String? = null,
)
