package org.pcsoft.framework.pluggiat.scanner

import java.nio.file.Files

/**
 * Scans a location's directory for standalone JAR files, treating each JAR as its own plugin
 * candidate with its manifest read directly from that JAR's `META-INF`.
 */
class SingleJarScanStrategy : PluginScanStrategy {
    override fun scan(location: PluginLocation): List<PluginScanResult> {
        val jarPaths = Files.newDirectoryStream(location.path, "*.jar").use { it.toList() }
        return jarPaths.map { jarPath -> PluginManifestLookup.scanSingleJar(location, jarPath) }
    }
}
