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

import eu.europa.ec.shared.wallet.document.WalletCredentialPolicy
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.sdjwt.credential.KeyBoundSdJwtVcCredential
import org.multipaz.securearea.software.SoftwareCreateKeySettings
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.util.toBase64Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * A document's SD-JWT claims leave out exactly what the official iOS wallet leaves out (Wallet Kit's
 * `toDocClaim`), which is also what Android's document manager leaves out.
 *
 * Until 2026-10-22 iOS also left out `iat`, `exp`, `nbf`, `sub`, `jti` and `…#integrity`, and showed
 * `assurance_level`; both reference wallets do the opposite.
 */
class SdJwtClaimDisplayTest {

    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun the_digests_key_binding_status_list_issuer_audience_type_and_assurance_level_are_left_out() {
        assertTrue(isHiddenSdJwtClaim("_sd", json("""["a"]""")))
        assertTrue(isHiddenSdJwtClaim("_sd_alg", JsonPrimitive("sha-256")))
        assertTrue(isHiddenSdJwtClaim("...", JsonPrimitive("digest")))
        assertTrue(isHiddenSdJwtClaim("cnf", json("""{"jwk":{"kty":"EC"}}""")))
        assertTrue(isHiddenSdJwtClaim("status", json("""{"status_list":{"idx":1,"uri":"https://s.test"}}""")))
        assertTrue(isHiddenSdJwtClaim("assurance_level", JsonPrimitive("high")))
        assertTrue(isHiddenSdJwtClaim("iss", JsonPrimitive("https://issuer.test")))
        assertTrue(isHiddenSdJwtClaim("aud", JsonPrimitive("https://verifier.test")))
        assertTrue(isHiddenSdJwtClaim("vct", JsonPrimitive("urn:eudi:pid:1")))
    }

    @Test
    fun the_jwts_own_times_and_identifiers_are_shown_as_the_reference_wallets_show_them() {
        for (name in listOf("iat", "exp", "nbf")) assertFalse(isHiddenSdJwtClaim(name, JsonPrimitive(1_790_974_170)), name)
        assertFalse(isHiddenSdJwtClaim("sub", JsonPrimitive("holder")))
        assertFalse(isHiddenSdJwtClaim("jti", JsonPrimitive("id-1")))
        assertFalse(isHiddenSdJwtClaim("vct#integrity", JsonPrimitive("sha256-abc")))
        assertFalse(isHiddenSdJwtClaim("given_name", JsonPrimitive("Erika")))
    }

    @Test
    fun a_claim_of_another_shape_than_its_rule_names_is_shown() {
        assertFalse(isHiddenSdJwtClaim("cnf", JsonPrimitive("not a key")))
        assertFalse(isHiddenSdJwtClaim("status", JsonPrimitive("active")))
        assertFalse(isHiddenSdJwtClaim("status", json("""{"other":{}}""")))
        assertFalse(isHiddenSdJwtClaim("iss", JsonPrimitive(7)))
        assertFalse(isHiddenSdJwtClaim("vct", json("""{"name":"x"}""")))
        assertFalse(isHiddenSdJwtClaim("aud", json("""["a","b"]""")))
    }

    @Test
    fun the_same_rules_apply_inside_nested_claims() {
        val nested = json(
            """{"street_address":"Main 1","iss":"https://inner.test","cnf":{"jwk":{}},
               "places":[{"locality":"A","vct":"x"}]}"""
        )

        assertEquals(
            json("""{"street_address":"Main 1","places":[{"locality":"A"}]}"""),
            nested.withoutHiddenSdJwtClaims(),
        )
    }

    @Test
    fun a_stored_sd_jwt_document_shows_its_attributes_and_the_jwts_times_and_nothing_else() = runTest {
        val storage = EphemeralStorage()
        val secureArea = SoftwareSecureArea.create(storage)
        val store = MultipazWalletStore.build(
            storage = storage,
            secureAreas = listOf(secureArea),
            documentManagerId = MultipazWalletStore.DEFAULT_DOCUMENT_MANAGER_ID,
        )
        val vct = "urn:eudi:pid:1"
        val metadata = EudiDocumentMetadata.create(
            documentManagerId = store.documentManagerId,
            format = StoredDocumentFormat.SdJwtVc(vct),
            credentialPolicy = WalletCredentialPolicy.RotatingBatch(numberOfCredentials = 1),
        )
        val document = store.documentStore.createDocument(displayName = "PID", metadata = metadata)
        val credential = KeyBoundSdJwtVcCredential.create(
            document = document,
            asReplacementForIdentifier = null,
            domain = store.documentManagerId,
            secureArea = secureArea,
            vct = vct,
            createKeySettings = SoftwareCreateKeySettings.Builder().build(),
        )
        // Resolved before building the JSON: `toJwk` suspends, and the object builder is not a coroutine body.
        val holderJwk = secureArea.getKeyInfo(credential.alias).publicKey.toJwk()
        val now = Clock.System.now()
        credential.certify(
            ByteString(
                signedSdJwt(
                    buildJsonObject {
                        put("iss", "https://issuer.test")
                        put("vct", vct)
                        put("iat", now.epochSeconds)
                        put("nbf", now.epochSeconds)
                        put("exp", (now + 30.days).epochSeconds)
                        put("assurance_level", "high")
                        putJsonObject("cnf") { put("jwk", holderJwk) }
                        putJsonObject("status") {
                            putJsonObject("status_list") { put("idx", 1); put("uri", "https://status.test/1") }
                        }
                        put("given_name", "Erika")
                        putJsonObject("address") { put("locality", "Berlin"); put("iss", "https://inner.test") }
                    },
                ).encodeToByteArray(),
            ),
        )

        val claims = document.readJsonClaims()

        assertEquals(setOf("iat", "nbf", "exp", "given_name", "address"), claims.map { it.name }.toSet())
        assertEquals(json("""{"locality":"Berlin"}"""), claims.single { it.name == "address" }.value)
    }

    /** An SD-JWT as an issuer sends it, every claim plain, signed by a document signer in its `x5c`. */
    private suspend fun signedSdJwt(payload: JsonObject): String {
        val signer = testSigner()
        val header = buildJsonObject {
            put("alg", "ES256")
            put("typ", "dc+sd-jwt")
            put("x5c", signer.chain.toX5c(excludeRoot = false))
        }
        val signingInput = listOf(header, payload).joinToString(".") { it.toString().encodeToByteArray().toBase64Url() }
        val signature = Crypto.sign(signer.key, Algorithm.ES256, signingInput.encodeToByteArray())
        return "$signingInput.${signature.toCoseEncoded().toBase64Url()}~"
    }
}
