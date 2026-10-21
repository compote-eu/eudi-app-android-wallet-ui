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

import io.ktor.http.Url
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Signed credential issuer metadata is taken as an issuer's only in the shape Android's `RequireSigned`
 * accepts: its `typ` and `alg`, `sub` and `iat`, any `exp`/`nbf`, and `credential_issuer`.
 *
 * Until 2026-10-21 iOS checked only that `credential_issuer` began with the host it asked, and not at all
 * when it was absent.
 */
class SignedMetadataRulesTest {

    private val issuer = "https://issuer.test/tenant"
    private val now = Instant.fromEpochSeconds(1_800_000_000)

    private fun header(typ: String? = "openidvci-issuer-metadata+jwt", alg: String = "ES256") = buildJsonObject {
        typ?.let { put("typ", it) }
        put("alg", alg)
    }

    private fun claims(
        subject: String? = issuer,
        credentialIssuer: String? = issuer,
        issuedAt: Boolean = true,
        expiresAt: Instant? = null,
        notBefore: Instant? = null,
    ) = buildJsonObject {
        subject?.let { put("sub", it) }
        credentialIssuer?.let { put("credential_issuer", it) }
        if (issuedAt) put("iat", now.epochSeconds - 60)
        expiresAt?.let { put("exp", it.epochSeconds) }
        notBefore?.let { put("nbf", it.epochSeconds) }
    }

    private fun refusal(header: JsonObject = header(), claims: JsonObject = claims()) =
        signedMetadataRefusal(header, claims, expectedIssuer = issuer, now = now)

    private fun assertRefused(because: String, reason: String?) =
        assertTrue(reason?.contains(because) == true, "expected a refusal mentioning '$because', got $reason")

    @Test
    fun metadata_in_the_shape_real_issuers_sign_it_is_accepted() {
        assertNull(refusal())
        assertNull(refusal(header = header(alg = "ES384")))
        assertNull(refusal(claims = claims(expiresAt = now + 10.minutes, notBefore = now - 10.minutes)))
    }

    @Test
    fun a_jwt_not_typed_as_issuer_metadata_is_refused() {
        assertRefused("is typed JWT", refusal(header = header(typ = "JWT")))
        assertRefused("is typed null", refusal(header = header(typ = null)))
    }

    @Test
    fun an_algorithm_outside_es256_es384_es512_is_refused() {
        assertRefused("is signed with HS256", refusal(header = header(alg = "HS256")))
    }

    @Test
    fun a_subject_other_than_the_issuer_asked_or_none_is_refused() {
        assertRefused("names https://other.test as its subject", refusal(claims = claims(subject = "https://other.test")))
        assertRefused("names null as its subject", refusal(claims = claims(subject = null)))
    }

    @Test
    fun metadata_naming_no_time_it_was_issued_at_is_refused() {
        assertRefused("no time it was issued at", refusal(claims = claims(issuedAt = false)))
    }

    @Test
    fun expired_or_not_yet_valid_metadata_is_refused_beyond_a_minutes_skew() {
        assertRefused("expired at", refusal(claims = claims(expiresAt = now - 2.minutes)))
        assertRefused("is not valid before", refusal(claims = claims(notBefore = now + 2.minutes)))
        // Within the 60 seconds Nimbus allows on Android.
        assertNull(refusal(claims = claims(expiresAt = now - 0.5.minutes, notBefore = now + 0.5.minutes)))
    }

    @Test
    fun a_document_describing_another_issuer_or_none_is_refused() {
        // A path is part of the identifier: the host matching, as the old check asked, is not enough.
        assertRefused("describes https://issuer.test/other", refusal(claims = claims(credentialIssuer = "https://issuer.test/other")))
        assertRefused("describes null", refusal(claims = claims(credentialIssuer = null)))
    }

    @Test
    fun the_issuer_is_read_off_either_form_of_the_metadata_url() {
        // multipaz's (RFC 8414: the segment after the host) and the app's own (appended to the issuer).
        assertEquals(issuer, credentialIssuerOf(Url("https://issuer.test/.well-known/openid-credential-issuer/tenant")))
        assertEquals(issuer, credentialIssuerOf(Url("https://issuer.test/tenant/.well-known/openid-credential-issuer")))
        assertEquals("https://issuer.test", credentialIssuerOf(Url("https://issuer.test/.well-known/openid-credential-issuer")))
        assertEquals("https://issuer.test:8443", credentialIssuerOf(Url("https://issuer.test:8443/.well-known/openid-credential-issuer")))
    }
}
