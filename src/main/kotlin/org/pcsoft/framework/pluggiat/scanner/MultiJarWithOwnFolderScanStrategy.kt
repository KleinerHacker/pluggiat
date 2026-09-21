package org.pcsoft.framework.pluggiat.scanner

import java.nio.file.Files

/**
 * Scans a location's directory for plugin subfolders, treating each subfolder's collection of
 * JARs as one plugin candidate. The manifest JAR among them is identified by the presence of a
 * manifest entry (`META-INF/plugin.yml`/`plugin.yaml`).
 */
class MultiJarWithOwnFolderScanStrategy : PluginScanStrategy {
    override fun scan(location: PluginLocation): List<PluginScanResult> {
        val subfolders = Files.newDirectoryStream(location.path) { Files.isDirectory(it) }.use { it.toList() }
        return subfolders.map { folder -> PluginManifestLookup.scanFolder(location, folder) }
    }
}
