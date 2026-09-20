package org.pcsoft.framework.pluggiat.manifest

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpdxLicensesTest {

    /**
     * Use case: a well-known SPDX identifier used by this very project ("Apache-2.0") is recognised
     * as known.
     */
    @Test
    fun `recognises a well-known SPDX identifier`() {
        assertTrue(SpdxLicenses.isKnownSpdxId("Apache-2.0"))
    }

    /**
     * Use case: a free-form license string that is not an SPDX identifier is reported as unknown,
     * without throwing - the license field of the manifest stays free-form.
     */
    @Test
    fun `reports an unknown license string as not recognised`() {
        assertFalse(SpdxLicenses.isKnownSpdxId("My Custom License 1.0"))
    }

    /**
     * Use case: the identifier match is case-sensitive, matching the canonical SPDX casing.
     */
    @Test
    fun `identifier match is case-sensitive`() {
        assertFalse(SpdxLicenses.isKnownSpdxId("apache-2.0"))
    }
}
