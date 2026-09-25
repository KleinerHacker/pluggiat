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

import java.security.PublicKey

/**
 * Resolves the [PublicKey] expected to have signed a given plugin, for use by
 * `org.pcsoft.framework.pluggiat.security.SignatureSecurityStrategy`.
 *
 * Concrete implementations (truststore-based, a directly supplied key, an online OpenPGP keyserver,
 * ...) are provided by `org.pcsoft.framework.pluggiat.security.publickey` implementations shipped
 * separately; this interface only defines the injection point used by the signature strategy.
 */
fun interface PublicKeyProviderStrategy {

    /**
     * Resolves the expected public key for the plugin identified by [pluginId] (the plugin's
     * manifest `id`), or `null` if no key could be resolved for it. Resolution failures must never
     * throw - an unresolved key is a defined, non-fatal outcome that the signature strategy treats
     * as a failed check.
     */
    fun resolve(pluginId: String): PublicKey?
}
