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
