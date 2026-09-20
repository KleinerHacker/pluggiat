package org.pcsoft.framework.pluggiat.manifest

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * Known SPDX license identifiers, bundled as a static resource snapshot of the official
 * [SPDX license list](https://spdx.org/licenses/).
 *
 * The `license` field of a plugin manifest is free-form text; this object only offers an optional,
 * best-effort check against the SPDX list and never rejects an unrecognised value.
 */
internal object SpdxLicenses {

    private const val RESOURCE_PATH = "/spdx/spdx-license-ids.json"

    private data class SpdxLicenseIds(
        val licenseListVersion: String,
        val licenseIds: List<String>,
    )

    private val mapper = ObjectMapper().registerModule(kotlinModule())

    /**
     * Version of the bundled SPDX license list snapshot.
     */
    val licenseListVersion: String by lazy { data.licenseListVersion }

    /**
     * All known SPDX license identifiers of the bundled snapshot.
     */
    val licenseIds: Set<String> by lazy { data.licenseIds.toSet() }

    private val data: SpdxLicenseIds by lazy {
        val stream = requireNotNull(javaClass.getResourceAsStream(RESOURCE_PATH)) {
            "SPDX license id resource not found: $RESOURCE_PATH"
        }
        stream.use { mapper.readValue<SpdxLicenseIds>(it) }
    }

    /**
     * Returns whether the given identifier is a known SPDX license identifier.
     *
     * The comparison is case-sensitive, matching the canonical casing of the SPDX license list.
     */
    fun isKnownSpdxId(licenseId: String): Boolean = licenseId in licenseIds
}
