/*
 * Copyright (c) 2026 European Commission
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the European
 * Commission - subsequent versions of the EUPL (the "Licence"); You may not use this work
 * except in compliance with the Licence.
 *
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the Licence is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF
 * ANY KIND, either express or implied. See the Licence for the specific language
 * governing permissions and limitations under the Licence.
 */

// How a deferred issuance's collected credentials find the waiting credentials they belong to: by the key
// each is bound to. `IosDeferredDocumentCompleterTest` covers the mdoc round trip; these pin the rule itself
// and the SD-JWT VC side, which is where pairing by position was seen to break (5 of a deferred PID's 7).
package eu.europa.ec.shared.wallet.multipaz

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.sdjwt.SdJwt
import kotlin.test.Test
import kotlin.test.assertEquals

class DeferredCredentialPairingTest {

    @Test
    fun a_credential_bound_to_no_waiting_key_stays_out_and_each_key_is_claimed_once() = runTest {
        val (keyA, keyB, unknown) = List(3) { Crypto.createEcPrivateKey(EcCurve.P256).publicKey }

        val paired = pairByBoundKey(
            waiting = listOf("a" to keyA, "b" to keyB),
            issued = listOf("x" to keyB, "y" to unknown, "z" to keyB, "w" to null, "v" to keyA),
        )

        assertEquals(listOf("b" to "x", "a" to "v"), paired)
    }

    @Test
    fun an_sd_jwt_vc_is_bound_to_its_cnf_key() = runTest {
        val holderKey = Crypto.createEcPrivateKey(EcCurve.P256).publicKey
        val sdJwt = SdJwt.create(
            issuerKey = AsymmetricKey.ephemeral(),
            kbKey = holderKey,
            claims = """{"family_name":"Kotlin"}""",
            nonSdClaims = """{"iss":"https://issuer.test","vct":"urn:eudi:pid:1"}""",
        )

        assertEquals(
            holderKey,
            boundKeyOf(ByteString(sdJwt.compactSerialization.encodeToByteArray()), isMdoc = false),
        )
    }
}
