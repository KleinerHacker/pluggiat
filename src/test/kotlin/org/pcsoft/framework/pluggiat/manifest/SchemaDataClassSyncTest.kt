package org.pcsoft.framework.pluggiat.manifest

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.reflect.full.primaryConstructor

/**
 * Verifies that the manifest JSON schema and the manifest data classes are kept in sync, since both
 * are maintained by hand without code generation in either direction.
 */
class SchemaDataClassSyncTest {

    private val mapper = ObjectMapper()

    private fun schemaPropertyNames(pointer: String): Set<String> {
        val schemaStream = requireNotNull(javaClass.getResourceAsStream("/schema/plugin-manifest.schema.json"))
        val root = schemaStream.use { mapper.readTree(it) }
        val node = if (pointer.isEmpty()) root else root.at(pointer)
        return node.get("properties").fieldNames().asSequence().toSet()
    }

    private fun dataClassPropertyNames(kClass: kotlin.reflect.KClass<*>): Set<String> =
        requireNotNull(kClass.primaryConstructor) { "${kClass.simpleName} has no primary constructor" }
            .parameters
            .mapNotNull { it.name }
            .toSet()

    /**
     * Use case: every top-level property of the manifest schema, except the internal `$version`
     * migration field, must have a matching constructor parameter on [PluginManifest].
     */
    @Test
    fun `every top-level schema field except dollar-version has a PluginManifest counterpart`() {
        val schemaFields = schemaPropertyNames("") - "\$version"
        val classFields = dataClassPropertyNames(PluginManifest::class)
        assertEquals(schemaFields, classFields)
    }

    /**
     * Use case: every constructor parameter of [PluginManifest] must have a matching property in the
     * manifest schema, so no data class field is ever added without extending the schema first.
     */
    @Test
    fun `every PluginManifest field has a top-level schema counterpart`() {
        val schemaFields = schemaPropertyNames("")
        val classFields = dataClassPropertyNames(PluginManifest::class)
        assertEquals(emptySet<String>(), classFields - schemaFields)
    }

    /**
     * Use case: the `author` schema definition and the [Author] data class must declare the same
     * fields, so an author's `name`/`mail` cannot drift apart between schema and code.
     */
    @Test
    fun `author schema definition matches Author data class`() {
        assertEquals(schemaPropertyNames("/\$defs/author"), dataClassPropertyNames(Author::class))
    }

    /**
     * Use case: the `links` schema definition and the [Links] data class must declare the same
     * fields.
     */
    @Test
    fun `links schema definition matches Links data class`() {
        assertEquals(schemaPropertyNames("/\$defs/links"), dataClassPropertyNames(Links::class))
    }

    /**
     * Use case: the `legal` schema definition and the [Legal] data class must declare the same
     * fields.
     */
    @Test
    fun `legal schema definition matches Legal data class`() {
        assertEquals(schemaPropertyNames("/\$defs/legal"), dataClassPropertyNames(Legal::class))
    }

    /**
     * Use case: the `dependency` schema definition and the [PluginDependency] data class must
     * declare the same fields.
     */
    @Test
    fun `dependency schema definition matches PluginDependency data class`() {
        assertEquals(schemaPropertyNames("/\$defs/dependency"), dataClassPropertyNames(PluginDependency::class))
    }

    /**
     * Use case: the `extensionEntry` schema definition and the [ExtensionEntry] data class must at
     * least agree on the shared, generic `implementation` field; concrete extension configuration
     * fields are added per extension point and are intentionally out of scope of this sync check.
     */
    @Test
    fun `extensionEntry schema definition declares the implementation field also present on ExtensionEntry`() {
        val schemaFields = schemaPropertyNames("/\$defs/extensionEntry")
        val classFields = dataClassPropertyNames(ExtensionEntry::class)
        assertEquals(classFields, schemaFields)
    }
}
