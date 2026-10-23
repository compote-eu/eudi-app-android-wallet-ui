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

// Rewriting the authorization data multipaz stores on a document: a rotated refresh token written in, and the
// shared DPoP key given up before a deletion. It is multipaz's own structure, so every other member must
// survive untouched.
package eu.europa.ec.shared.wallet.multipaz

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.Tstr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AuthorizationDataRewriteTest {

    private fun authorizationData(
        extras: Map<String, String> = mapOf("secureAreaId" to "sa-1"),
    ): ByteString {
        val builder = CborMap.builder()
            .put("type", "openid4vci")
            .put("issuerUri", "https://issuer.test")
            .put("configurationId", "pid_mdoc")
            .put("dpopKeyAlias", "shared-key")
            .put("refreshToken", "rt-original")
        extras.forEach { (k, v) -> builder.put(k, v) }
        return ByteString(Cbor.encode(builder.end().build()))
    }

    private fun ByteString.member(name: String): String? =
        ((Cbor.decode(toByteArray()) as CborMap).items[Tstr(name)] as? Tstr)?.value

    /** Everything that is not being changed, this build's members and one it has never heard of. */
    private val others = mapOf(
        "secureAreaId" to "sa-1",
        "authorizationServer" to "https://as.test",
        "walletAttestationKeyAlias" to "wa-key",
        "somethingThisBuildHasNeverHeardOf" to "keep-me",
    )

    private fun assertOthersKept(updated: ByteString) {
        assertEquals("openid4vci", updated.member("type"))
        assertEquals("https://issuer.test", updated.member("issuerUri"))
        assertEquals("pid_mdoc", updated.member("configurationId"))
        others.forEach { (name, value) -> assertEquals(value, updated.member(name), name) }
    }

    @Test
    fun a_rotated_refresh_token_replaces_the_spent_one_and_nothing_else() {
        val updated = assertNotNull(authorizationData(others).withRefreshToken("rt-rotated"))

        assertEquals("rt-rotated", updated.member("refreshToken"))
        assertEquals("rt-rotated", updated.openID4VciRefreshToken())
        assertEquals("shared-key", updated.member("dpopKeyAlias"))
        assertOthersKept(updated)
    }

    /** What a document gives up before it is deleted, so multipaz leaves its siblings their key. */
    @Test
    fun giving_up_the_dpop_key_removes_only_its_alias() {
        val updated = assertNotNull(authorizationData(others).withoutDpopKey())

        assertNull(updated.member("dpopKeyAlias"))
        assertNull(updated.openID4VciDpopKeyAlias())
        assertEquals("rt-original", updated.member("refreshToken"))
        assertOthersKept(updated)
    }

    @Test
    fun data_that_is_not_the_expected_map_is_left_alone() {
        // The honest answer for something written by a version whose schema changed: refuse to rewrite it
        // rather than corrupt the document.
        val notAMap = ByteString(Cbor.encode(Tstr("not a map")))
        assertNull(notAMap.withRefreshToken("rt-rotated"))
        assertNull(notAMap.withoutDpopKey())
        assertNull(notAMap.openID4VciRefreshToken())
    }
}
