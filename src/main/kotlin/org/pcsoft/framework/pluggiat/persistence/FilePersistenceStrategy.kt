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

package org.pcsoft.framework.pluggiat.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A [PluginPersistenceStrategy] backed by a single file at [path], in the given [format].
 *
 * The whole file is read into memory on first access and rewritten in full on every [write] -
 * appropriate for the small amounts of per-plugin state (checksums, enabled/disabled flags, ...)
 * this framework itself stores, not for large or high-frequency data.
 *
 * @property path the single file this strategy reads from and writes to
 * @property format file format the state is stored in, see [PersistenceFileFormat]
 */
class FilePersistenceStrategy(
    private val path: Path,
    private val format: PersistenceFileFormat = PersistenceFileFormat.PROPERTIES,
) : PluginPersistenceStrategy {
    private val lock = ReentrantLock()
    private var cache: MutableMap<String, MutableMap<String, String>>? = null

    override fun read(pluginId: String, key: String): String? = lock.withLock {
        loadIfNeeded()[pluginId]?.get(key)
    }

    override fun write(pluginId: String, key: String, value: String) = lock.withLock {
        val state = loadIfNeeded()
        state.getOrPut(pluginId) { mutableMapOf() }[key] = value
        save(state)
    }

    private fun loadIfNeeded(): MutableMap<String, MutableMap<String, String>> {
        var state = cache
        if (state == null) {
            state = if (Files.exists(path)) load() else mutableMapOf()
            cache = state
        }
        return state
    }

    private fun load(): MutableMap<String, MutableMap<String, String>> {
        val bytes = Files.readAllBytes(path)
        return when (format) {
            PersistenceFileFormat.PROPERTIES -> fromFlatProperties(Properties().apply { load(ByteArrayInputStream(bytes)) })
            PersistenceFileFormat.XML -> fromFlatProperties(Properties().apply { loadFromXML(ByteArrayInputStream(bytes)) })
            PersistenceFileFormat.JSON -> jsonMapper.readValue(bytes)
            PersistenceFileFormat.YAML -> yamlMapper.readValue(bytes)
        }
    }

    private fun save(state: Map<String, Map<String, String>>) {
        path.parent?.let { Files.createDirectories(it) }
        val bytes = when (format) {
            PersistenceFileFormat.PROPERTIES -> toFlatProperties(state).let {
                val out = ByteArrayOutputStream()
                it.store(out, "pluggiat plugin state")
                out.toByteArray()
            }

            PersistenceFileFormat.XML -> toFlatProperties(state).let {
                val out = ByteArrayOutputStream()
                it.storeToXML(out, "pluggiat plugin state")
                out.toByteArray()
            }

            PersistenceFileFormat.JSON -> jsonMapper.writeValueAsBytes(state)
            PersistenceFileFormat.YAML -> yamlMapper.writeValueAsBytes(state)
        }
        Files.write(path, bytes)
    }

    private fun fromFlatProperties(properties: Properties): MutableMap<String, MutableMap<String, String>> {
        val state = mutableMapOf<String, MutableMap<String, String>>()
        for (name in properties.stringPropertyNames()) {
            val pluginId = name.substringBeforeLast('.')
            val key = name.substringAfterLast('.')
            state.getOrPut(pluginId) { mutableMapOf() }[key] = properties.getProperty(name)
        }
        return state
    }

    private fun toFlatProperties(state: Map<String, Map<String, String>>): Properties {
        val properties = Properties()
        for ((pluginId, values) in state) {
            for ((key, value) in values) {
                properties.setProperty("$pluginId.$key", value)
            }
        }
        return properties
    }

    private companion object {
        val jsonMapper: ObjectMapper = ObjectMapper().registerModule(kotlinModule())
        val yamlMapper: ObjectMapper = YAMLMapper.builder().addModule(kotlinModule()).build()
    }
}
