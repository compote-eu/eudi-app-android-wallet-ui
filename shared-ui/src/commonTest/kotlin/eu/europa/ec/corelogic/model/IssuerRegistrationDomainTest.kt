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

// Ported from upstream's core-logic test; snake_case names because this also runs on Kotlin/Native.
package eu.europa.ec.corelogic.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IssuerRegistrationDomainTest {

    private val mockedDetails = RegistrationDetailsDomain(
        tradeName = "PID Issuer dev",
        uniqueId = "LEIXG-987654321",
        logoUri = null,
        intendedUse = "mocked intended use",
        privacyPolicyUrl = "https://issuer.example/privacy",
        serviceDescription = "mocked service description",
    )

    //region isBlockedForIssuance

    @Test
    fun a_verified_registration_lets_the_issuance_proceed() {
        assertFalse(IssuerRegistrationDomain.Verified(details = mockedDetails).isBlockedForIssuance)
    }

    @Test
    fun an_offer_beyond_the_registered_scope_is_refused() {
        val registration = IssuerRegistrationDomain.Blocked(
            reason = IssuerRegistrationDomain.BlockedReasonDomain.ATTESTATION_NOT_REGISTERED,
            details = mockedDetails,
        )

        assertTrue(registration.isBlockedForIssuance)
    }

    @Test
    fun a_failed_validation_is_refused() {
        val registration = IssuerRegistrationDomain.NotVerified(
            reason = RegistrationFailureReasonDomain.SIGNATURE_INVALID,
            details = mockedDetails,
        )

        assertTrue(registration.isBlockedForIssuance)
    }

    @Test
    fun a_failed_validation_that_parsed_nothing_is_refused() {
        val registration = IssuerRegistrationDomain.NotVerified(
            reason = RegistrationFailureReasonDomain.CERTIFICATE_ABSENT,
            details = null,
        )

        assertTrue(registration.isBlockedForIssuance)
    }

    /**
     * The trap this property carries, pinned deliberately.
     *
     * `NotEvaluated` is what a *disabled* policy produces, and disabled is the default — so this
     * value being `true` means a caller that consulted it alone would refuse every issuance in a
     * stock build. Callers must check `WalletCoreConfig.isRegistrationCheckEnabled` first. This test
     * exists so that anyone tempted to "fix" the property to return false reads this note instead.
     */
    @Test
    fun a_registration_never_established_is_refused_and_that_is_deliberate() {
        assertTrue(IssuerRegistrationDomain.NotEvaluated.isBlockedForIssuance)
    }

    //endregion
}
