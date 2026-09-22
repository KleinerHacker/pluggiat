package org.pcsoft.framework.pluggiat.extension

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExtensionPointRegistryTest {

    /**
     * Use case: a host registers a valid, `@ExtensionPoint`-annotated configuration class; the
     * registry exposes its key, configuration class and exclusivity flag.
     */
    @Test
    fun `registers a valid extension point configuration class`() {
        val registry = ExtensionPointRegistry(listOf(ExporterTestConfig::class))

        val registration = registry.registrationFor("exporters")

        assertEquals(ExporterTestConfig::class, registration?.configurationClass)
        assertEquals(false, registration?.exclusive)
    }

    /**
     * Use case: an exclusive extension point's `exclusive` flag is read from the `@ExtensionPoint`
     * annotation and exposed via [ExtensionPointRegistry.isExclusive].
     */
    @Test
    fun `exposes the exclusive flag of a registered extension point`() {
        val registry = ExtensionPointRegistry(listOf(SlotTestConfig::class))

        assertTrue(registry.isExclusive("singleton-slot"))
    }

    /**
     * Use case: registering a configuration class without the `@ExtensionPoint` annotation is
     * rejected, since the host would otherwise offer an extension point without a resolvable key.
     */
    @Test
    fun `fails when a configuration class is not annotated with ExtensionPoint`() {
        assertThrows(ExtensionRegistrationException::class.java) {
            ExtensionPointRegistry(listOf(NotAnnotatedTestConfig::class))
        }
    }

    /**
     * Use case: two configuration classes declaring the same extension point key are rejected,
     * since the framework could not unambiguously resolve entries for that key.
     */
    @Test
    fun `fails when the same extension point key is registered twice`() {
        assertThrows(ExtensionRegistrationException::class.java) {
            ExtensionPointRegistry(listOf(ExporterTestConfig::class, DuplicateExporterTestConfig::class))
        }
    }

    /**
     * Use case: an extension point whose host plugin API type `T` is a final class fails
     * registration outright, since extension instances are exclusively handed to the host as a
     * runtime enforcement proxy (IP-06 task 6) and a final `T` can never be proxied - this is a
     * registration-time error of the host's own configuration class, not a late-binding plugin
     * problem, so it must not silently degrade.
     */
    @Test
    fun `fails when a registered host plugin API type is a final class`() {
        assertThrows(ExtensionRegistrationException::class.java) {
            ExtensionPointRegistry(listOf(FinalApiTestConfig::class))
        }
    }

    /**
     * Use case: an extension point whose host plugin API type `T` is an interface or open class
     * registers successfully.
     */
    @Test
    fun `registers an extension point with an interface host plugin API type`() {
        val registry = ExtensionPointRegistry(listOf(ExporterTestConfig::class))

        assertEquals(TestExporter::class.java, registry.registrationFor("exporters")?.apiType)
    }
}

@ExtensionPoint(key = "exporters")
private data class DuplicateExporterTestConfig(
    override val implementation: kotlin.reflect.KClass<out TestExporter>,
) : ExtensionConfiguration<TestExporter>
