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

package eu.europa.ec.corelogic.extension

import eu.europa.ec.corelogic.model.UntrustedIssuerReasonDomain
import eu.europa.ec.eudi.openid4vci.AuthorizationPolicyValidationError
import eu.europa.ec.eudi.openid4vci.CredentialIssuerMetadataError
import eu.europa.ec.eudi.openid4vci.CredentialOfferRequestError
import eu.europa.ec.eudi.openid4vci.CredentialOfferRequestException
import eu.europa.ec.eudi.wallet.trust.IssuerNotTrustedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import io.ktor.client.HttpClient
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TestThrowableExtensions {

    //region toUntrustedIssuerReasonOrNull

    @Test
    fun `an untrusted issuer chain is an access-certificate refusal`() {
        // Given
        val failure = mockedIssuerNotTrustedException

        // When
        val reason = failure.toUntrustedIssuerReasonOrNull()

        // Then
        assertEquals(UntrustedIssuerReasonDomain.ACCESS_CERTIFICATE, reason)
    }

    @Test
    fun `missing signed metadata is an access-certificate refusal`() {
        // Given
        val failure = CredentialIssuerMetadataError.MissingSignedMetadata()

        // When
        val reason = failure.toUntrustedIssuerReasonOrNull()

        // Then
        assertEquals(UntrustedIssuerReasonDomain.ACCESS_CERTIFICATE, reason)
    }

    @Test
    fun `invalid signed metadata is an access-certificate refusal`() {
        // Given
        val failure = CredentialIssuerMetadataError.InvalidSignedMetadata(
            cause = RuntimeException("bad signature")
        )

        // When
        val reason = failure.toUntrustedIssuerReasonOrNull()

        // Then
        assertEquals(UntrustedIssuerReasonDomain.ACCESS_CERTIFICATE, reason)
    }

    @Test
    fun `a registration policy failure is a registration-certificate refusal`() {
        // Given
        val failure = AuthorizationPolicyValidationError.MissingIssuerInfo()

        // When
        val reason = failure.toUntrustedIssuerReasonOrNull()

        // Then
        assertEquals(UntrustedIssuerReasonDomain.REGISTRATION_CERTIFICATE, reason)
    }

    @Test
    fun `a missing registration certificate is a registration-certificate refusal`() {
        // Given
        val failure = AuthorizationPolicyValidationError.MissingRegistrationCertificate()

        // When
        val reason = failure.toUntrustedIssuerReasonOrNull()

        // Then
        assertEquals(UntrustedIssuerReasonDomain.REGISTRATION_CERTIFICATE, reason)
    }

    @Test
    fun `a refusal wrapped deep in the cause chain is still found`() {
        // Given
        val failure = RuntimeException(
            "outer",
            IllegalStateException(
                "inner",
                mockedIssuerNotTrustedException,
            ),
        )

        // When
        val reason = failure.toUntrustedIssuerReasonOrNull()

        // Then
        assertEquals(UntrustedIssuerReasonDomain.ACCESS_CERTIFICATE, reason)
    }

    // A credential-offer failure carries its error in a property rather than in `cause`, so the
    // walk has to descend into the issuer-metadata reason to reach the refusal.
    @Test
    fun `a refusal behind an offer request error is found through the metadata reason`() {
        // Given
        val failure = CredentialOfferRequestException(
            error = CredentialOfferRequestError.UnableToResolveCredentialIssuerMetadata(
                reason = CredentialIssuerMetadataError.MissingSignedMetadata(),
            ),
        )

        // When
        val reason = failure.toUntrustedIssuerReasonOrNull()

        // Then
        assertEquals(UntrustedIssuerReasonDomain.ACCESS_CERTIFICATE, reason)
    }

    @Test
    fun `an offer request error unrelated to trust yields no refusal`() {
        // Given
        val failure = CredentialOfferRequestException(
            error = CredentialOfferRequestError.NonParseableCredentialOffer(
                reason = RuntimeException("malformed json"),
            ),
        )

        // When
        val reason = failure.toUntrustedIssuerReasonOrNull()

        // Then
        assertNull(reason)
    }

    @Test
    fun `an unrelated failure yields no refusal`() {
        // Given
        val failure = RuntimeException("no network")

        // When
        val reason = failure.toUntrustedIssuerReasonOrNull()

        // Then
        assertNull(reason)
    }

    //endregion

    //region isTerminalDeferredFailure

    /** Exactly what openid4vci-kt runs into: a typed error body read from a response that has none. */
    private class ErrorBody(val error: String)

    private fun failureFromAnEmptyBodied(status: HttpStatusCode): Throwable = runBlocking {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = "",
                    status = status,
                    headers = headersOf(
                        "WWW-Authenticate",
                        """DPoP realm="pid-issuer", error="invalid_token", error_description="Access token is not valid"""",
                    ),
                )
            }
        )
        runCatching { client.post("https://issuer.test/wallet/deferredEndpoint").body<ErrorBody>() }
            .exceptionOrNull()!!
    }

    @Test
    fun `an empty-bodied 401 from the deferred endpoint is final`() {
        // Given — Plaut's dev issuer, verbatim (2026-09-28/29)
        val failure = failureFromAnEmptyBodied(HttpStatusCode.Unauthorized)

        // Then — the real exception, not a stand-in for it
        assertTrue(failure is NoTransformationFoundException)
        assertTrue(failure.isTerminalDeferredFailure())
    }

    @Test
    fun `an empty-bodied server error is not final`() {
        // Given
        val failure = failureFromAnEmptyBodied(HttpStatusCode.InternalServerError)

        // Then — a passing fault on the issuer's side: asking again later is right
        assertFalse(failure.isTerminalDeferredFailure())
    }

    @Test
    fun `an invalid_token error body is final`() {
        // Given — what wallet-core makes of a JSON `Errored` answer
        val failure = IllegalStateException("invalid_token")

        // Then
        assertTrue(failure.isTerminalDeferredFailure())
    }

    @Test
    fun `a spent transaction id is final`() {
        assertTrue(IllegalStateException("invalid_transaction_id").isTerminalDeferredFailure())
    }

    @Test
    fun `a final answer is recognised when it is wrapped`() {
        assertTrue(RuntimeException("deferred query failed", IllegalStateException("invalid_token")).isTerminalDeferredFailure())
    }

    @Test
    fun `other failures are asked about again`() {
        assertFalse(IllegalStateException("issuance_pending").isTerminalDeferredFailure())
        assertFalse(IllegalStateException("server_error").isTerminalDeferredFailure())
        assertFalse(java.io.IOException("connection reset").isTerminalDeferredFailure())
        // Trust is its own outcome, decided before this one.
        assertFalse(mockedIssuerNotTrustedException.isTerminalDeferredFailure())
    }

    //endregion

    //region Mocked objects needed for tests.

    private val mockedIssuerNotTrustedException = IssuerNotTrustedException(
        message = "Issuer certificate chain is not trusted",
        cause = RuntimeException("untrusted chain"),
    )

    //endregion
}
