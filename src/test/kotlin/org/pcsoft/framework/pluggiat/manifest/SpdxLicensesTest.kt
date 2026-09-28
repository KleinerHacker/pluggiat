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

import org.junit.jupiter.api.Assertions.assertEquals
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

    /**
     * Use case: a plain, known identifier yields no unknown ids.
     */
    @Test
    fun `unknownIds is empty for a single known identifier`() {
        assertEquals(emptyList<String>(), SpdxLicenses.unknownIds("Apache-2.0"))
    }

    /**
     * Use case: a compound expression of known identifiers joined with `OR`, `AND` and parentheses is
     * recognised, the operators themselves are not reported as unknown.
     */
    @Test
    fun `unknownIds accepts compound expressions with operators and parentheses`() {
        assertEquals(emptyList<String>(), SpdxLicenses.unknownIds("(Apache-2.0 OR MIT) AND BSD-3-Clause"))
    }

    /**
     * Use case: operators are matched case-insensitively, since SPDX allows lower-case operators.
     */
    @Test
    fun `unknownIds treats operators case-insensitively`() {
        assertEquals(emptyList<String>(), SpdxLicenses.unknownIds("Apache-2.0 or MIT"))
    }

    /**
     * Use case: the exception identifier after `WITH` belongs to the separate SPDX exception list and
     * is therefore not checked against the license identifiers.
     */
    @Test
    fun `unknownIds skips the exception identifier following WITH`() {
        assertEquals(emptyList<String>(), SpdxLicenses.unknownIds("GPL-2.0-only WITH Classpath-exception-2.0"))
    }

    /**
     * Use case: the "or later" suffix `+` is ignored when looking the identifier up.
     */
    @Test
    fun `unknownIds ignores the or-later plus suffix`() {
        assertEquals(emptyList<String>(), SpdxLicenses.unknownIds("GPL-2.0+"))
    }

    /**
     * Use case: user-defined `LicenseRef-` and `DocumentRef-` identifiers are valid SPDX syntax and are
     * accepted without a lookup.
     */
    @Test
    fun `unknownIds accepts user-defined LicenseRef and DocumentRef identifiers`() {
        assertEquals(emptyList<String>(), SpdxLicenses.unknownIds("LicenseRef-Internal OR DocumentRef-Other:LicenseRef-X"))
    }

    /**
     * Use case: every identifier of an expression that is not on the SPDX list is reported, while the
     * known ones next to it are not.
     */
    @Test
    fun `unknownIds reports exactly the unknown identifiers`() {
        assertEquals(listOf("Custom", "License"), SpdxLicenses.unknownIds("Apache-2.0 Custom License"))
    }

    /**
     * Use case: a blank license expression is reported as unknown as a whole instead of passing as an
     * expression without any identifier.
     */
    @Test
    fun `unknownIds reports a blank expression as unknown`() {
        assertEquals(listOf("  "), SpdxLicenses.unknownIds("  "))
    }
}
