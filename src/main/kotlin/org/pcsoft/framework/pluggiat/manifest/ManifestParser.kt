package org.pcsoft.framework.pluggiat.manifest

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import java.io.InputStream

/**
 * Parses and validates plugin manifests (`META-INF/plugin.yml`/`plugin.yaml`) against the manifest
 * JSON schema and maps them onto [PluginManifest].
 */
internal object ManifestParser {

    /**
     * The two file names inside a plugin archive under which a manifest is looked up by the scanner.
     */
    val MANIFEST_FILE_NAMES: List<String> = listOf("META-INF/plugin.yml", "META-INF/plugin.yaml")

    private const val SCHEMA_RESOURCE_PATH = "/schema/plugin-manifest.schema.json"

    private val yamlMapper: YAMLMapper = YAMLMapper.builder()
        .addModule(kotlinModule())
        .build()
        .apply { disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES) }

    private val schema: JsonSchema by lazy {
        val schemaStream = requireNotNull(javaClass.getResourceAsStream(SCHEMA_RESOURCE_PATH)) {
            "Manifest JSON schema resource not found: $SCHEMA_RESOURCE_PATH"
        }
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaStream)
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

        val violations = schema.validate(node).map { it.message }
        if (violations.isNotEmpty()) {
            throw ManifestValidationException(
                "Plugin manifest violates the manifest schema: ${violations.joinToString("; ")}",
                violations = violations,
            )
        }

        return try {
            yamlMapper.treeToValue(node, PluginManifest::class.java)
        } catch (e: Exception) {
            throw ManifestValidationException("Plugin manifest could not be mapped to PluginManifest", cause = e)
        }
    }

    /**
     * Parses and validates the manifest YAML content read from the given stream.
     *
     * @throws ManifestValidationException if the content violates the manifest JSON schema or cannot be parsed
     */
    fun parse(input: InputStream): PluginManifest = parse(input.readBytes().toString(Charsets.UTF_8))
}
