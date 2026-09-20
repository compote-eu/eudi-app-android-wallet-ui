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

// Rewriting a document\'s DPoP binding. Several documents come out of one authorization, so they would
// otherwise share a DPoP key \u2014 and multipaz deletes the key a deleted document names, taking it from its
// siblings. Each document is therefore moved onto its own key, which means rewriting authorization data
// that belongs to multipaz.
package eu.europa.ec.shared.wallet.multipaz

import kotlinx.io.bytestring.ByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.Tstr

class DpopRebindingTest {

    private fun authorizationData(
        dpopKeyAlias: String = "shared-key",
        refreshToken: String = "rt-original",
        extras: Map<String, String> = mapOf("secureAreaId" to "sa-1"),
    ): ByteString {
        val builder = CborMap.builder()
            .put("type", "openid4vci")
            .put("issuerUri", "https://issuer.test")
            .put("configurationId", "pid_mdoc")
            .put("dpopKeyAlias", dpopKeyAlias)
            .put("refreshToken", refreshToken)
        extras.forEach { (k, v) -> builder.put(k, v) }
        return ByteString(Cbor.encode(builder.end().build()))
    }

    private fun ByteString.member(name: String): String? =
        ((Cbor.decode(toByteArray()) as CborMap).items[Tstr(name)] as? Tstr)?.value

    @Test
    fun the_key_and_token_are_replaced() {
        val updated = authorizationData()
            .withDpopBinding(RebindResult(dpopKeyAlias = "own-key", refreshToken = "rt-new"))

        assertNotNull(updated)
        assertEquals("own-key", updated.member("dpopKeyAlias"))
        assertEquals("rt-new", updated.member("refreshToken"))
    }

    @Test
    fun every_other_member_survives_the_rewrite() {
        // \u26d4 This is multipaz\'s own structure and it carries a schema hash. Dropping a member this
        // build does not know about would break a refresh silently, so the rewrite copies rather than
        // re-encodes a parsed object.
        val updated = authorizationData(
            extras = mapOf(
                "secureAreaId" to "sa-1",
                "authorizationServer" to "https://as.test",
                "walletAttestationKeyAlias" to "wa-key",
                "somethingThisBuildHasNeverHeardOf" to "keep-me",
            ),
        ).withDpopBinding(RebindResult(dpopKeyAlias = "own-key", refreshToken = "rt-new"))

        assertNotNull(updated)
        assertEquals("openid4vci", updated.member("type"))
        assertEquals("https://issuer.test", updated.member("issuerUri"))
        assertEquals("pid_mdoc", updated.member("configurationId"))
        assertEquals("sa-1", updated.member("secureAreaId"))
        assertEquals("https://as.test", updated.member("authorizationServer"))
        assertEquals("wa-key", updated.member("walletAttestationKeyAlias"))
        assertEquals("keep-me", updated.member("somethingThisBuildHasNeverHeardOf"))
    }

    @Test
    fun data_that_is_not_the_expected_map_is_left_alone() {
        // The honest answer for something written by a version whose schema changed: refuse to rewrite
        // it, so the caller keeps the shared key rather than corrupting the document.
        assertNull(ByteString(Cbor.encode(Tstr("not a map"))).withDpopBinding(
            RebindResult(dpopKeyAlias = "own-key", refreshToken = "rt-new"),
        ))
    }
}
