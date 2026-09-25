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

import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyConverter
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.util.Date

/**
 * Generates OpenPGP key material for the OpenPGP keyserver provider tests, without depending on an
 * external `gpg` installation.
 */
object OpenPgpTestFixtures {

    data class ExportedKey(val armoredPublicKey: ByteArray, val keyId: String, val publicKey: PublicKey)

    /**
     * Generates a fresh RSA key pair and exports it as an OpenPGP key (see [export]).
     */
    fun generateKey(): ExportedKey {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        return export(keyPair.public)
    }

    /**
     * Wraps [publicKey] as a single-key OpenPGP public key ring, returning both its ASCII-armored
     * export (as an HKP `mr` lookup would return it) and the OpenPGP key id derived from it.
     */
    fun export(publicKey: PublicKey): ExportedKey {
        val pgpPublicKey = JcaPGPKeyConverter().getPGPPublicKey(PublicKeyAlgorithmTags.RSA_GENERAL, publicKey, Date())
        val keyRing = PGPPublicKeyRing(listOf(pgpPublicKey))

        val armored = ByteArrayOutputStream().also { buffer ->
            ArmoredOutputStream(buffer).use { it.write(keyRing.encoded) }
        }.toByteArray()

        return ExportedKey(armored, "%016X".format(pgpPublicKey.keyID), publicKey)
    }
}
