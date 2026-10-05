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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The registration check run before an issuance with no approval screen, judged by Android's rule:
 * `isBlockedForIssuance` over the outcome, with the check on — and no check at all with it off.
 */
class IosIssuerRegistrationPreflightTest {

    private val registration = IssuerRegistration(
        subject = "NTRSK-12345678",
        name = "Fixture Issuer",
        legalName = "Fixture Issuer s.r.o.",
        country = "SK",
        entitlements = listOf(IssuerEntitlements.PID),
        privacyPolicyUri = null,
        purpose = emptyList(),
        serviceDescription = emptyList(),
        providedAttestations = emptyList(),
        registeredCredentials = emptyList(),
        status = null,
        expiresAt = null,
        intermediaryIdentifier = null,
    )

    private var checks = 0

    private suspend fun preflight(
        enabled: Boolean = true,
        outcome: () -> IssuerRegistrationOutcome,
    ) = registrationPreflight(isEnabled = { enabled }) {
        checks++
        outcome()
    }

    @Test
    fun with_the_check_off_nothing_is_fetched_and_the_issuance_goes_ahead() = runTest {
        // "Off" means no traffic for the check, not a check whose answer is ignored.
        val verdict = preflight(enabled = false) { error("must not be called") }

        assertEquals(RegistrationPreflight.Proceed(registration = null), verdict)
        assertEquals(0, checks)
    }

    @Test
    fun a_verified_registration_covering_the_offer_goes_ahead_with_the_certificate() = runTest {
        val verdict = preflight { IssuerRegistrationOutcome.Verified(registration, overProvided = emptyList()) }

        assertEquals(RegistrationPreflight.Proceed(registration), verdict)
        assertEquals(1, checks)
    }

    @Test
    fun an_issuer_offering_more_than_it_registered_is_refused() = runTest {
        val extra = OfferedAttestation(format = "mso_mdoc", doctype = "org.iso.18013.5.1.mDL")

        val verdict = preflight { IssuerRegistrationOutcome.Verified(registration, overProvided = listOf(extra)) }

        assertEquals(RegistrationPreflight.Refused, verdict)
    }

    @Test
    fun a_registration_without_the_entitlement_is_refused() = runTest {
        val verdict = preflight {
            IssuerRegistrationOutcome.Failed(IssuerRegistrationFailure.ENTITLEMENT_MISSING, registration)
        }

        assertEquals(RegistrationPreflight.Refused, verdict)
    }

    @Test
    fun an_issuer_publishing_no_registration_certificate_is_refused() = runTest {
        // Android: "no registration certificate published; refuse like any unverified outcome".
        val verdict = preflight { IssuerRegistrationOutcome.NotOffered }

        assertEquals(RegistrationPreflight.Refused, verdict)
    }

    @Test
    fun metadata_that_could_not_be_fetched_is_a_failure_not_a_refusal() = runTest {
        val verdict = preflight {
            IssuerRegistrationOutcome.Unavailable(detail = "issuer metadata could not be read")
        }

        assertEquals(RegistrationPreflight.Unavailable("issuer metadata could not be read"), verdict)
    }

    @Test
    fun a_check_that_throws_is_a_failure_not_a_refusal() = runTest {
        val verdict = preflight { throw IllegalStateException("The trust list could not be loaded.") }

        assertIs<RegistrationPreflight.Unavailable>(verdict)
        assertEquals("The trust list could not be loaded.", verdict.detail)
    }
}
