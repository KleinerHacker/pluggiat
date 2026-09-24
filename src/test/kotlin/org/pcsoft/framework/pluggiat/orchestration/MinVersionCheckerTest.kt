package org.pcsoft.framework.pluggiat.orchestration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.pcsoft.framework.pluggiat.manifest.PluginManifest
import org.pcsoft.framework.pluggiat.scanner.PluginLocation
import org.pcsoft.framework.pluggiat.scanner.PluginLocationType
import org.pcsoft.framework.pluggiat.scanner.PluginScanResult
import org.pcsoft.framework.pluggiat.scanner.PluginScanStatus
import org.pcsoft.framework.pluggiat.scanner.SingleJarScanStrategy
import java.nio.file.Path

/**
 * Verifies [MinVersionChecker]'s comparison of a candidate's `manifest.minVersion` against the
 * configured host version.
 */
class MinVersionCheckerTest {

    private fun resultWithMinVersion(path: Path, minVersion: String): PluginScanResult = PluginScanResult(
        location = PluginLocation(path, PluginLocationType.EXTERNAL, SingleJarScanStrategy()),
        path = path,
        manifest = PluginManifest(id = "plugin-a", name = "plugin-a", version = "1.0.0", minVersion = minVersion, icon = "aWNvbg=="),
        status = PluginScanStatus.LOADED,
    )

    /**
     * Use case: a plugin requiring a newer host version than configured is rejected with
     * `MIN_VERSION_VIOLATION`.
     */
    @Test
    fun `plugin requiring a newer host version is rejected`(@TempDir tempDir: Path) {
        val result = resultWithMinVersion(tempDir.resolve("a"), minVersion = "2.0.0")

        val checked = MinVersionChecker.check(result, hostVersion = "1.0.0")

        assertEquals(PluginScanStatus.MIN_VERSION_VIOLATION, checked.status)
    }

    /**
     * Use case: a `null` host version (the feature is optional) skips the check entirely, the
     * result is returned unchanged.
     */
    @Test
    fun `null host version skips the check`(@TempDir tempDir: Path) {
        val result = resultWithMinVersion(tempDir.resolve("a"), minVersion = "99.0.0")

        val checked = MinVersionChecker.check(result, hostVersion = null)

        assertSame(result, checked)
    }

    /**
     * Use case: a host version equal to or newer than `minVersion` passes the check unchanged.
     */
    @Test
    fun `host version equal to or newer than minVersion passes`(@TempDir tempDir: Path) {
        val equal = resultWithMinVersion(tempDir.resolve("a"), minVersion = "1.0.0")
        val newer = resultWithMinVersion(tempDir.resolve("b"), minVersion = "1.0.0")

        assertEquals(PluginScanStatus.LOADED, MinVersionChecker.check(equal, hostVersion = "1.0.0").status)
        assertEquals(PluginScanStatus.LOADED, MinVersionChecker.check(newer, hostVersion = "2.0.0").status)
    }

    /**
     * Use case: `check` requires a result with a resolved manifest (i.e. `status == LOADED`);
     * calling it for any other status is a programming error.
     */
    @Test
    fun `check throws for a result without a manifest`(@TempDir tempDir: Path) {
        val result = PluginScanResult(
            location = PluginLocation(tempDir, PluginLocationType.EXTERNAL, SingleJarScanStrategy()),
            path = tempDir.resolve("missing"),
            manifest = null,
            status = PluginScanStatus.MANIFEST_NOT_FOUND,
            errorMessage = "not found",
        )

        assertThrows(IllegalArgumentException::class.java) {
            MinVersionChecker.check(result, hostVersion = "1.0.0")
        }
    }
}
