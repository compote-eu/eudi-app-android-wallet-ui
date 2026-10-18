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

// Ported from upstream's core-logic test. Names are snake_case, not backticked sentences: this runs
// on Kotlin/Native too, where a test name may not contain spaces.
package eu.europa.ec.corelogic.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelyingPartyDomainTest {

    private val mockedAccessCertificateName = "Verifier Signer dev"
    private val mockedSubjectName = "NordicBank A/S"
    private val mockedSubjectUniqueId = "LEIXG-123456789"

    private val mockedDetails = RegistrationDetailsDomain(
        tradeName = mockedSubjectName,
        uniqueId = mockedSubjectUniqueId,
        logoUri = null,
        intendedUse = "mocked intended use",
        privacyPolicyUrl = "https://nordicbank.example/privacy",
        serviceDescription = "mocked service description",
    )

    //region resolveRequesterName

    @Test
    fun a_verified_registrations_name_outranks_the_access_certificate_name() {
        val registration = verified(details = mockedDetails)

        val result = registration.resolveRequesterName(
            accessCertificateName = mockedAccessCertificateName,
        )

        // The registrar attested this name, and the evaluation proved the certificate belongs to
        // the party that signed the request.
        assertEquals(mockedSubjectName, result)
    }

    @Test
    fun a_verified_registration_with_no_name_falls_back_to_the_access_certificate() {
        val registration = verified(details = mockedDetails.copy(tradeName = null))

        val result = registration.resolveRequesterName(
            accessCertificateName = mockedAccessCertificateName,
        )

        assertEquals(mockedAccessCertificateName, result)
    }

    @Test
    fun the_access_certificate_name_outranks_an_unverified_registrations_name() {
        val registration = notVerified(details = mockedDetails)

        val result = registration.resolveRequesterName(
            accessCertificateName = mockedAccessCertificateName,
        )

        assertEquals(mockedAccessCertificateName, result)
    }

    @Test
    fun an_unverified_registrations_name_is_the_last_resort() {
        val registration = notVerified(details = mockedDetails)

        val result = registration.resolveRequesterName(accessCertificateName = null)

        assertEquals(mockedSubjectName, result)
    }

    @Test
    fun nothing_parsed_and_no_certificate_name_resolves_no_name() {
        val registration = notVerified(details = null)

        val result = registration.resolveRequesterName(accessCertificateName = null)

        assertNull(result)
    }

    @Test
    fun an_unevaluated_registration_leaves_the_access_certificate_name() {
        val registration = RegistrationStatusDomain.NotEvaluated

        val result = registration.resolveRequesterName(
            accessCertificateName = mockedAccessCertificateName,
        )

        assertEquals(mockedAccessCertificateName, result)
    }

    //endregion

    //region requesterUniqueIdOrNull

    @Test
    fun a_verified_registration_identifies_the_requester() {
        assertEquals(
            mockedSubjectUniqueId,
            verified(details = mockedDetails).requesterUniqueIdOrNull()
        )
    }

    @Test
    fun an_unverified_registration_still_returns_the_parsed_identifier() {
        assertEquals(
            mockedSubjectUniqueId,
            notVerified(details = mockedDetails).requesterUniqueIdOrNull()
        )
    }

    @Test
    fun an_unevaluated_registration_identifies_nobody() {
        assertNull(RegistrationStatusDomain.NotEvaluated.requesterUniqueIdOrNull())
    }

    //endregion

    //region isFullyVerified — every screen's badge renders from this

    @Test
    fun the_badge_needs_both_a_trusted_certificate_and_an_unproblematic_registration() {
        assertTrue(
            relyingParty(
                hasTrustedAccessCertificate = true,
                registration = verified(details = mockedDetails),
            ).isFullyVerified
        )
    }

    @Test
    fun an_untrusted_access_certificate_denies_the_badge_however_good_the_registration() {
        assertFalse(
            relyingParty(
                hasTrustedAccessCertificate = false,
                registration = verified(details = mockedDetails),
            ).isFullyVerified
        )
    }

    @Test
    fun a_failed_registration_denies_the_badge_however_good_the_certificate() {
        assertFalse(
            relyingParty(
                hasTrustedAccessCertificate = true,
                registration = notVerified(details = mockedDetails),
            ).isFullyVerified
        )
    }

    @Test
    fun a_registration_never_evaluated_is_judged_on_the_certificate_alone() {
        // This is the default build: the check is off, so the badge must mean what it always did.
        assertTrue(
            relyingParty(
                hasTrustedAccessCertificate = true,
                registration = RegistrationStatusDomain.NotEvaluated,
            ).isFullyVerified
        )
        assertFalse(
            relyingParty(
                hasTrustedAccessCertificate = false,
                registration = RegistrationStatusDomain.NotEvaluated,
            ).isFullyVerified
        )
    }

    //endregion

    //region overaskedClaimsOrEmpty

    @Test
    fun only_a_verified_registration_reports_overasked_claims() {
        val overasked = OveraskedClaimDomain(
            path = ClaimPathDomain.ofPlainKeys(
                names = listOf("family_name"),
                type = ClaimType.MsoMdoc(namespace = "eu.europa.ec.eudi.pid.1"),
            ),
            attestationTypes = emptySet(),
        )

        assertEquals(
            listOf(overasked),
            RegistrationStatusDomain.Verified(
                details = mockedDetails,
                overaskedClaims = listOf(overasked),
            ).overaskedClaimsOrEmpty()
        )
        assertTrue(notVerified(details = mockedDetails).overaskedClaimsOrEmpty().isEmpty())
        assertTrue(RegistrationStatusDomain.NotEvaluated.overaskedClaimsOrEmpty().isEmpty())
    }

    @Test
    fun an_overasked_claim_with_no_attestation_types_applies_to_every_format() {
        val anyAttestation = OveraskedClaimDomain(
            path = ClaimPathDomain.ofPlainKeys(
                names = listOf("family_name"),
                type = ClaimType.SdJwtVc,
            ),
            attestationTypes = emptySet(),
        )

        assertTrue(anyAttestation.appliesTo(formatType = "urn:eu.europa.ec.eudi:pid:1"))
        assertTrue(anyAttestation.appliesTo(formatType = "anything"))
    }

    @Test
    fun an_overasked_claim_matches_its_attestation_type_case_insensitively() {
        val pidOnly = OveraskedClaimDomain(
            path = ClaimPathDomain.ofPlainKeys(
                names = listOf("family_name"),
                type = ClaimType.SdJwtVc,
            ),
            attestationTypes = setOf("urn:eu.europa.ec.eudi:pid:1"),
        )

        assertTrue(pidOnly.appliesTo(formatType = "URN:EU.EUROPA.EC.EUDI:PID:1"))
        assertFalse(pidOnly.appliesTo(formatType = "org.iso.18013.5.1.mDL"))
    }

    //endregion

    //region helper functions

    private fun verified(details: RegistrationDetailsDomain): RegistrationStatusDomain =
        RegistrationStatusDomain.Verified(details = details, overaskedClaims = emptyList())

    private fun notVerified(details: RegistrationDetailsDomain?): RegistrationStatusDomain =
        RegistrationStatusDomain.NotVerified(
            reason = RegistrationFailureReasonDomain.REVOCATION_STATUS_UNKNOWN,
            details = details,
        )

    private fun relyingParty(
        hasTrustedAccessCertificate: Boolean,
        registration: RegistrationStatusDomain,
    ) = RelyingPartyDomain(
        name = mockedSubjectName,
        uniqueId = mockedSubjectUniqueId,
        hasTrustedAccessCertificate = hasTrustedAccessCertificate,
        logoUri = null,
        registration = registration,
    )

    //endregion
}
