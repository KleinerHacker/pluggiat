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

package org.pcsoft.framework.pluggiat.sandbox

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies [SandboxDeactivatedException]'s message formatting: it names the affected plugin so a
 * host can tell which plugin's governed call was rejected.
 */
class SandboxDeactivatedExceptionTest {

    /**
     * Use case: the exception message names the plugin id it was constructed with.
     */
    @Test
    fun `message contains the plugin id`() {
        val exception = SandboxDeactivatedException("example-plugin")

        assertTrue(exception.message!!.contains("example-plugin"))
    }
}
