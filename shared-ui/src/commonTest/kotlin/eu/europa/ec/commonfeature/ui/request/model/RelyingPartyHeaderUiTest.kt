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

// Ported from upstream's common-feature test, with three adaptations: snake_case names (this runs on
// Kotlin/Native, where a test name may not contain spaces), `UiText` where upstream has a resolved
// `String`, and a `String` logo where upstream has a `java.net.URI`.
package eu.europa.ec.commonfeature.ui.request.model

import eu.europa.ec.corelogic.model.ClaimPathDomain
import eu.europa.ec.corelogic.model.ClaimType
import eu.europa.ec.corelogic.model.OveraskedClaimDomain
import eu.europa.ec.corelogic.model.RegistrationDetailsDomain
import eu.europa.ec.corelogic.model.RegistrationFailureReasonDomain
import eu.europa.ec.corelogic.model.RegistrationStatusDomain
import eu.europa.ec.corelogic.model.RelyingPartyDomain
import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.shared.resources.asUiText
import eu.europa.ec.uilogic.component.RelyingPartyDataUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RelyingPartyHeaderUiTest {

    private val mockedFallbackName: UiText = UiText.Raw("Unknown relying party")
    private val mockedRequesterLogoUri = "https://rpservices.example/logo.png"
    private val mockedVerifierName = "EUDIW Verifier"
    private val mockedTradeName = "NordicBank A/S"

    private val mockedDetails = RegistrationDetailsDomain(
        tradeName = mockedTradeName,
        uniqueId = "rp:nordicbank:prod",
        logoUri = "https://nordicbank.example/logo.png",
        intendedUse = "mocked intended use",
        privacyPolicyUrl = "https://nordicbank.example/privacy",
        serviceDescription = "mocked service description",
    )

    private val mockedOveraskedClaim = OveraskedClaimDomain(
        path = ClaimPathDomain.ofPlainKeys(
            names = listOf("family_name"),
            type = ClaimType.SdJwtVc,
        ),
        attestationTypes = emptySet(),
    )

    //region toRelyingPartyHeaderUi

    @Test
    fun a_verified_registration_shows_the_badge_and_the_registration_sections() {
        val relyingParty = relyingPartyDomain(
            name = mockedVerifierName,
            uniqueId = mockedDetails.uniqueId,
            hasTrustedAccessCertificate = true,
            registration = RegistrationStatusDomain.Verified(
                details = mockedDetails,
                overaskedClaims = emptyList(),
            ),
        )

        val result = relyingParty.toRelyingPartyHeaderUi(fallbackName = mockedFallbackName)

        assertEquals(
            RelyingPartyHeaderUi(
                relyingParty = RelyingPartyDataUi(
                    logo = mockedRequesterLogoUri,
                    isVerified = true,
                    name = mockedVerifierName.asUiText(),
                    uniqueId = mockedDetails.uniqueId,
                    description = null,
                ),
                intendedUse = mockedDetails.intendedUse,
                privacyPolicyUrl = mockedDetails.privacyPolicyUrl,
            ),
            result,
        )
    }

    @Test
    fun an_untrusted_access_certificate_renders_the_sections_without_the_badge() {
        val relyingParty = relyingPartyDomain(
            name = mockedVerifierName,
            uniqueId = mockedDetails.uniqueId,
            hasTrustedAccessCertificate = false,
            registration = RegistrationStatusDomain.Verified(
                details = mockedDetails,
                overaskedClaims = emptyList(),
            ),
        )

        val result = relyingParty.toRelyingPartyHeaderUi(fallbackName = mockedFallbackName)

        assertEquals(false, result.relyingParty.isVerified)
        assertEquals(mockedDetails.intendedUse, result.intendedUse)
        assertEquals(mockedDetails.privacyPolicyUrl, result.privacyPolicyUrl)
    }

    @Test
    fun a_failed_registration_that_parsed_nothing_hides_the_badge_and_the_sections() {
        val relyingParty = relyingPartyDomain(
            name = mockedVerifierName,
            uniqueId = null,
            hasTrustedAccessCertificate = true,
            registration = RegistrationStatusDomain.NotVerified(
                reason = RegistrationFailureReasonDomain.CERTIFICATE_ABSENT,
                details = null,
            ),
        )

        val result = relyingParty.toRelyingPartyHeaderUi(fallbackName = mockedFallbackName)

        assertEquals(false, result.relyingParty.isVerified)
        assertNull(result.intendedUse)
        assertNull(result.privacyPolicyUrl)
    }

    @Test
    fun a_failed_registration_carrying_details_renders_them_without_the_badge() {
        val relyingParty = relyingPartyDomain(
            name = mockedVerifierName,
            uniqueId = mockedDetails.uniqueId,
            hasTrustedAccessCertificate = true,
            registration = RegistrationStatusDomain.NotVerified(
                reason = RegistrationFailureReasonDomain.REVOCATION_STATUS_UNKNOWN,
                details = mockedDetails,
            ),
        )

        val result = relyingParty.toRelyingPartyHeaderUi(fallbackName = mockedFallbackName)

        // Shown to the user, but never with the badge.
        assertEquals(false, result.relyingParty.isVerified)
        assertEquals(mockedDetails.intendedUse, result.intendedUse)
        assertEquals(mockedDetails.privacyPolicyUrl, result.privacyPolicyUrl)
    }

    @Test
    fun an_unevaluated_registration_leaves_the_badge_to_the_access_certificate() {
        val relyingParty = relyingPartyDomain(
            name = mockedVerifierName,
            uniqueId = null,
            hasTrustedAccessCertificate = true,
            registration = RegistrationStatusDomain.NotEvaluated,
        )

        val result = relyingParty.toRelyingPartyHeaderUi(fallbackName = mockedFallbackName)

        assertEquals(true, result.relyingParty.isVerified)
        assertNull(result.intendedUse)
        assertNull(result.privacyPolicyUrl)
    }

    @Test
    fun an_unevaluated_registration_behind_an_untrusted_certificate_hides_the_badge() {
        val relyingParty = relyingPartyDomain(
            name = mockedVerifierName,
            uniqueId = null,
            hasTrustedAccessCertificate = false,
            registration = RegistrationStatusDomain.NotEvaluated,
        )

        val result = relyingParty.toRelyingPartyHeaderUi(fallbackName = mockedFallbackName)

        assertEquals(false, result.relyingParty.isVerified)
    }

    @Test
    fun a_requester_with_no_name_falls_back() {
        val relyingParty = relyingPartyDomain(
            name = null,
            uniqueId = null,
            hasTrustedAccessCertificate = true,
            registration = RegistrationStatusDomain.NotEvaluated,
        )

        val result = relyingParty.toRelyingPartyHeaderUi(fallbackName = mockedFallbackName)

        assertEquals(mockedFallbackName, result.relyingParty.name)
    }

    //endregion

    //region toRegistrationWarningUi

    @Test
    fun a_verified_registration_covering_the_request_raises_no_warning() {
        val relyingParty = relyingPartyDomain(
            name = mockedVerifierName,
            uniqueId = mockedDetails.uniqueId,
            hasTrustedAccessCertificate = true,
            registration = RegistrationStatusDomain.Verified(
                details = mockedDetails,
                overaskedClaims = emptyList(),
            ),
        )

        assertNull(relyingParty.toRegistrationWarningUi())
    }

    @Test
    fun overasked_claims_raise_an_unaccepted_overasked_warning() {
        val relyingParty = relyingPartyDomain(
            name = mockedVerifierName,
            uniqueId = mockedDetails.uniqueId,
            hasTrustedAccessCertificate = true,
            registration = RegistrationStatusDomain.Verified(
                details = mockedDetails,
                overaskedClaims = listOf(mockedOveraskedClaim),
            ),
        )

        assertEquals(
            RegistrationWarningUi(
                variant = RegistrationWarningVariantUi.OVERASKED,
                // Never pre-accepted: the user must flip the switch for every rendered request.
                riskAccepted = false,
            ),
            relyingParty.toRegistrationWarningUi(),
        )
    }

    @Test
    fun a_failed_registration_raises_an_unaccepted_not_verified_warning() {
        val relyingParty = relyingPartyDomain(
            name = mockedVerifierName,
            uniqueId = null,
            hasTrustedAccessCertificate = true,
            registration = RegistrationStatusDomain.NotVerified(
                reason = RegistrationFailureReasonDomain.SIGNATURE_INVALID,
                details = null,
            ),
        )

        assertEquals(
            RegistrationWarningUi(
                variant = RegistrationWarningVariantUi.NOT_VERIFIED,
                riskAccepted = false,
            ),
            relyingParty.toRegistrationWarningUi(),
        )
    }

    @Test
    fun an_unevaluated_registration_raises_no_warning() {
        // The default build: the check is off, so the consent screen must look as it always did.
        val relyingParty = relyingPartyDomain(
            name = mockedVerifierName,
            uniqueId = null,
            hasTrustedAccessCertificate = true,
            registration = RegistrationStatusDomain.NotEvaluated,
        )

        assertNull(relyingParty.toRegistrationWarningUi())
    }

    //endregion

    //region helper functions

    private fun relyingPartyDomain(
        name: String?,
        uniqueId: String?,
        hasTrustedAccessCertificate: Boolean,
        registration: RegistrationStatusDomain,
    ) = RelyingPartyDomain(
        name = name,
        uniqueId = uniqueId,
        hasTrustedAccessCertificate = hasTrustedAccessCertificate,
        logoUri = mockedRequesterLogoUri,
        registration = registration,
    )

    //endregion
}
