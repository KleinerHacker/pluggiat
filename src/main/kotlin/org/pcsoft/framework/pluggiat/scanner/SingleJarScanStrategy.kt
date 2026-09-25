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
