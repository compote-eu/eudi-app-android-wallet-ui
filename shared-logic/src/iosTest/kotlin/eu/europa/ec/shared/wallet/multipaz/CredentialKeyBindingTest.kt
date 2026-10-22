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

package eu.europa.ec.shared.wallet.multipaz

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPublicKey
import org.multipaz.sdjwt.SdJwt
import org.multipaz.util.toBase64Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A credential is stored only on the key it is bound to — wallet-core's certifiers, which compare an mdoc's
 * MSO `deviceKey` and an SD-JWT VC's `cnf` key with the key the credential is stored under, and refuse a
 * response with more or fewer credentials than keys.
 *
 * Until 2026-10-22 iOS checked this only for a deferred credential, which it pairs by key; an immediate one
 * went to the pending credential at its position, whichever key it was bound to.
 */
class CredentialKeyBindingTest {

    private val mdlDocType = "org.iso.18013.5.1.mDL"

    private suspend fun newKey(): EcPublicKey = Crypto.createEcPrivateKey(EcCurve.P256).publicKey

    private fun compact(header: JsonObject, payload: JsonObject) =
        listOf(header, payload).joinToString(".", postfix = ".c2ln") { it.toString().encodeToByteArray().toBase64Url() }

    /** A `jwt` proof as multipaz signs it: the key in the header's `jwk`. Its signature is the issuer's business. */
    private suspend fun proofOf(key: EcPublicKey): String = compact(
        buildJsonObject {
            put("typ", "openid4vci-proof+jwt")
            put("alg", "ES256")
            put("jwk", key.toJwk())
        },
        buildJsonObject { put("nonce", "c_nonce") },
    )

    /** A key attestation as the wallet provider mints it, attesting [keys] in their order. */
    private suspend fun attestationOf(keys: List<EcPublicKey>): String {
        val jwks = keys.map { it.toJwk() }
        return compact(
            buildJsonObject {
                put("typ", "key-attestation+jwt")
                put("alg", "ES256")
            },
            buildJsonObject {
                put("attested_keys", buildJsonArray { jwks.forEach { add(it) } })
                put("nonce", "c_nonce")
            },
        )
    }

    private suspend fun jwtProofRequest(vararg keys: EcPublicKey): JsonObject {
        val proofs = keys.map { proofOf(it) }
        return buildJsonObject {
            put("credential_configuration_id", "mdl")
            putJsonObject("proofs") { putJsonArray("jwt") { proofs.forEach { add(JsonPrimitive(it)) } } }
        }
    }

    private suspend fun attestationRequest(vararg keys: EcPublicKey): JsonObject {
        val attestation = attestationOf(keys.toList())
        return buildJsonObject {
            put("credential_configuration_id", "pid_sd_jwt")
            putJsonObject("proofs") { putJsonArray("attestation") { add(JsonPrimitive(attestation)) } }
        }
    }

    private suspend fun mdocBoundTo(key: EcPublicKey, signer: TestSigner? = null): String {
        val issuer = signer ?: testSigner()
        return testMdocCredential(mdlDocType, issuer.chain, issuer.key, deviceKey = key)
    }

    private suspend fun sdJwtVcBoundTo(key: EcPublicKey?): String = SdJwt.create(
        issuerKey = AsymmetricKey.ephemeral(),
        kbKey = key,
        claims = """{"family_name":"Kotlin"}""",
        nonSdClaims = """{"iss":"https://issuer.test","vct":"urn:eudi:pid:1"}""",
    ).compactSerialization

    private suspend fun assertRefused(request: JsonObject, credentials: List<String>, because: String) {
        val refusal = assertFailsWith<CredentialIntegrityException> { verifyKeyBinding(request, credentials) }
        assertTrue(because in refusal.message.orEmpty(), "unexpected: ${refusal.message}")
    }

    @Test
    fun jwt_proofs_name_their_keys_in_the_order_they_were_sent() = runTest {
        val (first, second) = List(2) { newKey() }

        assertEquals(listOf(first, second), proofKeysOf(jwtProofRequest(first, second)))
    }

    @Test
    fun an_attestation_names_the_keys_it_attests_in_its_order() = runTest {
        val (first, second) = List(2) { newKey() }

        assertEquals(listOf(first, second), proofKeysOf(attestationRequest(first, second)))
    }

    @Test
    fun a_request_without_proofs_asks_for_keyless_credentials_and_is_not_checked() = runTest {
        val keyless = buildJsonObject { put("credential_configuration_id", "mdl") }

        assertNull(proofKeysOf(keyless))
        verifyKeyBinding(keyless, listOf(mdocBoundTo(newKey())))
    }

    @Test
    fun proofs_naming_no_key_this_wallet_can_read_are_refused() = runTest {
        val unknownType = buildJsonObject {
            putJsonObject("proofs") { putJsonArray("di_vp") { add(JsonPrimitive("x")) } }
        }
        val noJwk = buildJsonObject {
            putJsonObject("proofs") {
                putJsonArray("jwt") { add(JsonPrimitive(compact(buildJsonObject { put("alg", "ES256") }, buildJsonObject {}))) }
            }
        }

        assertRefused(unknownType, listOf(mdocBoundTo(newKey())), because = "proofs name no key")
        assertRefused(noJwk, listOf(mdocBoundTo(newKey())), because = "proofs name no key")
    }

    @Test
    fun mdocs_each_bound_to_the_key_asked_for_at_its_position_are_accepted() = runTest {
        val (first, second) = List(2) { newKey() }
        val signer = testSigner()

        verifyKeyBinding(jwtProofRequest(first, second), listOf(mdocBoundTo(first, signer), mdocBoundTo(second, signer)))
    }

    @Test
    fun an_sd_jwt_vc_bound_to_the_attested_key_is_accepted() = runTest {
        val key = newKey()

        verifyKeyBinding(attestationRequest(key), listOf(sdJwtVcBoundTo(key)))
    }

    @Test
    fun a_credential_bound_to_another_key_than_the_one_asked_for_is_refused() = runTest {
        assertRefused(jwtProofRequest(newKey()), listOf(mdocBoundTo(newKey())), because = "bound to another key")
        assertRefused(attestationRequest(newKey()), listOf(sdJwtVcBoundTo(newKey())), because = "bound to another key")
    }

    @Test
    fun credentials_in_another_order_than_their_keys_are_refused() = runTest {
        // Each is bound to a key the wallet asked for, but multipaz would store each on the other one.
        val (first, second) = List(2) { newKey() }
        val signer = testSigner()

        assertRefused(
            jwtProofRequest(first, second),
            listOf(mdocBoundTo(second, signer), mdocBoundTo(first, signer)),
            because = "credential 1 of 2 is bound to another key",
        )
    }

    @Test
    fun more_or_fewer_credentials_than_keys_are_refused() = runTest {
        val (first, second) = List(2) { newKey() }
        val signer = testSigner()

        assertRefused(jwtProofRequest(first, second), listOf(mdocBoundTo(first, signer)), because = "sent 1 credential(s) for 2 key(s)")
        assertRefused(
            jwtProofRequest(first),
            listOf(mdocBoundTo(first, signer), mdocBoundTo(second, signer)),
            because = "sent 2 credential(s) for 1 key(s)",
        )
        assertRefused(jwtProofRequest(first), emptyList(), because = "sent 0 credential(s) for 1 key(s)")
    }

    @Test
    fun an_sd_jwt_vc_bound_to_no_key_is_refused() = runTest {
        assertRefused(attestationRequest(newKey()), listOf(sdJwtVcBoundTo(null)), because = "names no key it is bound to")
    }
}
