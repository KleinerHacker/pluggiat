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

package org.pcsoft.framework.pluggiat.manifest

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.pcsoft.framework.pluggiat.PluginResourceLimits
import org.slf4j.LoggerFactory
import java.io.InputStream

/**
 * Parses and validates plugin manifests (`META-INF/plugin.yml`/`plugin.yaml`) against the manifest
 * JSON schema and maps them onto [PluginManifest].
 *
 * After a successful validation the icon format and the license identifier are additionally checked on a
 * best-effort basis; a finding is only ever logged as a warning and never invalidates the manifest.
 */
internal object ManifestParser {

    /**
     * The two file names inside a plugin archive under which a manifest is looked up by the scanner.
     */
    val MANIFEST_FILE_NAMES: List<String> = listOf("META-INF/plugin.yml", "META-INF/plugin.yaml")

    private const val SCHEMA_RESOURCE_PATH = "/schema/plugin-manifest.schema.json"

    private const val MAX_LOGGED_VALUE_LENGTH = 64

    private val logger = LoggerFactory.getLogger(ManifestParser::class.java)

    private val yamlMapper: YAMLMapper = YAMLMapper.builder()
        .addModule(kotlinModule())
        .build()
        .apply { disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES) }

    private val schema: Schema by lazy {
        val schemaStream = requireNotNull(javaClass.getResourceAsStream(SCHEMA_RESOURCE_PATH)) {
            "Manifest JSON schema resource not found: $SCHEMA_RESOURCE_PATH"
        }
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(schemaStream)
    }

    /**
     * Parses and validates the given manifest YAML content.
     *
     * @throws ManifestValidationException if the content violates the manifest JSON schema or cannot be parsed
     */
    fun parse(yaml: String): PluginManifest {
        val node: JsonNode = try {
            yamlMapper.readTree(yaml)
        } catch (e: Exception) {
            throw ManifestValidationException("Plugin manifest is not valid YAML/JSON", cause = e)
        }

        // The validator is based on Jackson 3, so the Jackson 2 tree is handed over as JSON text
        // SECURITY: schema validation before any mapping: it is what enforces the id pattern/length and
        // SECURITY: rejects unknown fields, so no unvalidated manifest value ever reaches the framework.
        val violations = schema.validate(node.toString(), InputFormat.JSON)
            .map { "${it.instanceLocation}: ${it.message}" }
        if (violations.isNotEmpty()) {
            throw ManifestValidationException(
                "Plugin manifest violates the manifest schema: ${violations.joinToString("; ")}",
                violations = violations,
            )
        }

        val manifest = try {
            yamlMapper.treeToValue(node, PluginManifest::class.java)
        } catch (e: Exception) {
            throw ManifestValidationException("Plugin manifest could not be mapped to PluginManifest", cause = e)
        }
        warnAboutAdvisoryFindings(manifest)
        return manifest
    }

    /**
     * Parses and validates the manifest YAML content read from the given stream, reading at most
     * [PluginResourceLimits.MAX_MANIFEST_SIZE_BYTES].
     *
     * The stream is a ZIP entry of an unverified candidate, so its length is whatever the plugin author
     * chose: reading it wholesale would let a manifest entry of arbitrary size exhaust the heap during
     * the scan, before this candidate has passed anything at all.
     *
     * @throws ManifestValidationException if the content exceeds that limit, violates the manifest JSON
     * schema, or cannot be parsed
     */
    fun parse(input: InputStream): PluginManifest = parse(readBoundedManifest(input))

    /**
     * Reads one byte more than the limit allows, so exceeding it is detected from what was read instead
     * of from a length the stream claims.
     */
    private fun readBoundedManifest(input: InputStream): String {
        val limit = PluginResourceLimits.MAX_MANIFEST_SIZE_BYTES
        // SECURITY: reads at most limit+1 bytes, so an oversized manifest is detected without ever holding
        // SECURITY: more than that in memory.
        val bytes = input.readNBytes((limit + 1).toInt())
        if (bytes.size > limit) {
            throw ManifestValidationException(
                "Plugin manifest is larger than the maximum of $limit bytes",
            )
        }
        return bytes.toString(Charsets.UTF_8)
    }

    /**
     * Logs a warning for an unrecognised icon format and for a license identifier missing from the SPDX
     * list. Never throws: a check that cannot run is logged and skipped, so a best-effort finding can
     * neither invalidate a manifest nor abort a scan.
     */
    private fun warnAboutAdvisoryFindings(manifest: PluginManifest) {
        try {
            IconDetector.detectFormat(manifest.icon)
        } catch (e: IconFormatException) {
            logger.warn("Plugin '{}': {}", manifest.id, e.message)
        }

        val license = manifest.legal?.license ?: return
        try {
            val unknownIds = SpdxLicenses.unknownIds(license)
            if (unknownIds.isNotEmpty()) {
                logger.warn(
                    "Plugin '{}': license '{}' is not a known SPDX license expression (unknown: {})",
                    manifest.id, sanitizeForLog(license), unknownIds.joinToString { sanitizeForLog(it) },
                )
            }
        } catch (e: Exception) {
            logger.warn("Plugin '{}': the license could not be checked against the SPDX list ({})", manifest.id, e.javaClass.simpleName)
        }
    }

    // SECURITY: plugin-controlled text reaches the log only shortened and without control characters, so a
    // SECURITY: manifest cannot forge additional log lines.
    private fun sanitizeForLog(value: String): String =
        value.take(MAX_LOGGED_VALUE_LENGTH).map { if (it.isISOControl()) '?' else it }.joinToString("")
}
