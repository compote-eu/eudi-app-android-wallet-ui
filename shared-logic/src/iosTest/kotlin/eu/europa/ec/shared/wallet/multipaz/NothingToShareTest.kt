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

import eu.europa.ec.shared.wallet.trust.ReaderTrustSource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.multipaz.crypto.X509CertChain
import org.multipaz.trustmanagement.TrustMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Who the "nothing to share" screen names. multipaz ends that way before consent, so the requester is
 * rebuilt from the request's signer, which the observing engine kept; Android's screen names it too.
 */
class NothingToShareTest {

    private suspend fun noticeSignedByTestVerifier() = PresentationRequestNotice().apply {
        // `testVerifierCertificate` is self-signed with CN "Test Verifier".
        remember(
            requestObject = buildJsonObject {},
            signerChain = X509CertChain(listOf(testVerifierCertificate(dnsNames = listOf("verifier.test")))),
        )
    }

    @Test
    fun a_verifier_a_trusted_list_vouches_for_is_named_by_the_list_and_trusted() = runTest {
        val shown = nothingToShare(
            notice = noticeSignedByTestVerifier(),
            readerTrust = ReaderTrustSource { TrustMetadata(displayName = "EUDI Verifier") },
            registration = RelyingPartyRegistrationOutcome.NotChecked,
        )

        assertEquals("EUDI Verifier", shown.requesterName)
        assertTrue(shown.requesterIsTrusted)
        assertEquals(RelyingPartyRegistrationOutcome.NotChecked, shown.relyingPartyRegistration)
    }

    @Test
    fun without_a_verdict_it_is_named_by_its_own_certificate_and_not_trusted() = runTest {
        val shown = nothingToShare(
            notice = noticeSignedByTestVerifier(),
            readerTrust = ReaderTrustSource { null },
            registration = RelyingPartyRegistrationOutcome.NotOffered,
        )

        assertEquals("Test Verifier", shown.requesterName)
        assertFalse(shown.requesterIsTrusted)
    }

    @Test
    fun a_trust_check_that_fails_still_names_it_by_its_certificate() = runTest {
        val shown = nothingToShare(
            notice = noticeSignedByTestVerifier(),
            readerTrust = ReaderTrustSource { error("the trust lists could not be read") },
            registration = RelyingPartyRegistrationOutcome.NotOffered,
        )

        assertEquals("Test Verifier", shown.requesterName)
        assertFalse(shown.requesterIsTrusted)
    }

    @Test
    fun a_request_nothing_signed_names_no_one() = runTest {
        val shown = nothingToShare(
            notice = PresentationRequestNotice(),
            readerTrust = ReaderTrustSource { TrustMetadata(displayName = "unused") },
            registration = RelyingPartyRegistrationOutcome.NotOffered,
        )

        // The screen then falls back to its own "Unknown Relying Party", which is the honest rendering.
        assertNull(shown.requesterName)
        assertFalse(shown.requesterIsTrusted)
    }
}
