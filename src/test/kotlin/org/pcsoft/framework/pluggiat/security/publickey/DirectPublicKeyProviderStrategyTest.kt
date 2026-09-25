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

package org.pcsoft.framework.pluggiat.security.publickey

import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator

class DirectPublicKeyProviderStrategyTest {

    /**
     * Use case: the directly supplied public key is returned unchanged, regardless of plugin id.
     */
    @Test
    fun `resolves the directly supplied public key unchanged`() {
        val publicKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public
        val strategy = DirectPublicKeyProviderStrategy(publicKey)

        assertSame(publicKey, strategy.resolve("plugin-a"))
        assertSame(publicKey, strategy.resolve("plugin-b"))
    }
}
