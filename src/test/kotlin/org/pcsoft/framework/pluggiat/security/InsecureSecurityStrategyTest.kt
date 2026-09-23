package org.pcsoft.framework.pluggiat.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.pcsoft.framework.pluggiat.manifest.PluginManifest
import org.pcsoft.framework.pluggiat.scanner.PluginLocation
import org.pcsoft.framework.pluggiat.scanner.PluginLocationType
import org.pcsoft.framework.pluggiat.scanner.PluginScanResult
import org.pcsoft.framework.pluggiat.scanner.PluginScanStatus
import org.pcsoft.framework.pluggiat.scanner.SingleJarScanStrategy
import java.nio.file.Path

class InsecureSecurityStrategyTest {

    /**
     * Use case: the "insecure" strategy performs no check at all and passes any candidate through.
     */
    @Test
    fun `always accepts the candidate without performing any check`() {
        val location = PluginLocation(Path.of("."), PluginLocationType.EXTERNAL, SingleJarScanStrategy())
        val manifest = PluginManifest(id = "plugin-a", name = "plugin-a", version = "1.0.0", minVersion = "1.0.0", icon = "aWNvbg==")
        val result = PluginScanResult(location, Path.of("plugin-a.jar"), manifest, PluginScanStatus.LOADED)

        val checkResult = InsecureSecurityStrategy().check(result)

        assertEquals(PluginSecurityCheckResult.Success, checkResult)
    }
}
