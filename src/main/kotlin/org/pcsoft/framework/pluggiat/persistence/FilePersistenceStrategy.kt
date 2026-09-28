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
import org.slf4j.LoggerFactory
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
 * The file is written atomically and owner-only (see [OwnerOnlyFiles]). What is stored here decides
 * whether a plugin is enabled and which checksum was once accepted for it, so a reader must never see
 * a half-written state, a crash must never leave a truncated one, and another local user must not be
 * able to edit it.
 *
 * For the flat formats ([PersistenceFileFormat.PROPERTIES]/[PersistenceFileFormat.XML]) a
 * `(pluginId, key)` pair is flattened into one property name, separated by [FLAT_KEY_SEPARATOR] - see
 * [toFlatProperties] for why that separator and not `.`.
 *
 * @property path the single file this strategy reads from and writes to
 * @property format file format the state is stored in, see [PersistenceFileFormat]
 */
class FilePersistenceStrategy(
    private val path: Path,
    private val format: PersistenceFileFormat = PersistenceFileFormat.PROPERTIES,
) : PluginPersistenceStrategy {
    private val logger = LoggerFactory.getLogger(FilePersistenceStrategy::class.java)
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
        // SECURITY: complete-or-not-at-all, and unreadable to other users: see OwnerOnlyFiles. This file
        // SECURITY: decides enabled flags and accepted checksums, so a torn write or a foreign writer matters.
        OwnerOnlyFiles.writeAtomically(path, bytes)
    }

    /**
     * Splits each flattened property name back into its `(pluginId, key)` pair at the **first**
     * [FLAT_KEY_SEPARATOR]: the separator cannot occur in a plugin id (the manifest schema forbids it),
     * while a key may well contain it, so the first occurrence is unambiguously the boundary.
     *
     * A name without the separator cannot have been produced by [toFlatProperties] - it is either state
     * from an older version or something edited in by hand - and is skipped with a warning rather than
     * guessed at: mis-splitting a name would attribute one plugin's state to another.
     */
    private fun fromFlatProperties(properties: Properties): MutableMap<String, MutableMap<String, String>> {
        val state = mutableMapOf<String, MutableMap<String, String>>()
        for (name in properties.stringPropertyNames()) {
            // SECURITY: an entry that cannot have been written by toFlatProperties is skipped, not guessed at:
            // SECURITY: mis-splitting a name would attribute one plugin's stored state to another.
            if (!name.contains(FLAT_KEY_SEPARATOR)) {
                logger.warn(
                    "Ignoring persisted entry '{}' in '{}': it carries no '{}' separator between plugin id and key",
                    name, path, FLAT_KEY_SEPARATOR,
                )
                continue
            }
            // SECURITY: split at the *first* separator - a plugin id cannot contain it (schema-enforced),
            // SECURITY: while a key may, so the first occurrence is unambiguously the boundary.
            val pluginId = name.substringBefore(FLAT_KEY_SEPARATOR)
            val key = name.substringAfter(FLAT_KEY_SEPARATOR)
            state.getOrPut(pluginId) { mutableMapOf() }[key] = properties.getProperty(name)
        }
        return state
    }

    /**
     * Flattens `(pluginId, key)` into one property name, joined by [FLAT_KEY_SEPARATOR].
     *
     * A `.` used to serve as that separator, but `.` is a legal character *inside* a plugin id (ids are
     * commonly reverse-DNS, e.g. `com.example.tools`), so `com.example.tools` + `enabled` and
     * `com.example` + `tools.enabled` flattened to the very same name. On reload, state could therefore
     * be attributed to a plugin that never wrote it - a plugin able to choose its own id could aim for
     * exactly that and inherit another plugin's accepted checksum or enabled flag.
     * [FLAT_KEY_SEPARATOR] is a character the manifest schema forbids in an id, which removes the
     * ambiguity at the source.
     */
    private fun toFlatProperties(state: Map<String, Map<String, String>>): Properties {
        val properties = Properties()
        for ((pluginId, values) in state) {
            for ((key, value) in values) {
                properties.setProperty("$pluginId$FLAT_KEY_SEPARATOR$key", value)
            }
        }
        return properties
    }

    companion object {
        /**
         * Separator between plugin id and key in the flat file formats. A `|` cannot appear in a plugin
         * id (see the manifest schema's `id` pattern), which is exactly what makes the flattening
         * reversible - see [toFlatProperties].
         */
        const val FLAT_KEY_SEPARATOR: String = "|"

        private val jsonMapper: ObjectMapper = ObjectMapper().registerModule(kotlinModule())
        private val yamlMapper: ObjectMapper = YAMLMapper.builder().addModule(kotlinModule()).build()
    }
}
