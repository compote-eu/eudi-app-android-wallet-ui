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

// Who the remote consent screen says is asking, across the two trust layers — the rules Android's
// `buildRelyingParty` applies, held here because iOS builds the same object on its own.
package eu.europa.ec.shared.ui.di

import eu.europa.ec.commonfeature.ui.request.model.RegistrationWarningVariantUi
import eu.europa.ec.commonfeature.ui.request.model.toRegistrationWarningUi
import eu.europa.ec.corelogic.model.RegistrationFailureReasonDomain
import eu.europa.ec.corelogic.model.RegistrationStatusDomain
import eu.europa.ec.shared.wallet.multipaz.IssuerRegistration
import eu.europa.ec.shared.wallet.multipaz.IssuerRegistrationFailure
import eu.europa.ec.shared.wallet.multipaz.LocalizedText
import eu.europa.ec.shared.wallet.multipaz.RelyingPartyRegistrationOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosRelyingPartyNamingTest {

    /** What the EU dev verifier's `LR-INTER-01` registration and access certificate say. */
    private val accessCertificateName = "Verifier Signer dev"

    private fun registration(name: String? = "Netcompany Verifier (DEV)") = IssuerRegistration(
        subject = "LEIXG-123456789",
        name = name,
        legalName = null,
        country = "EU",
        entitlements = emptyList(),
        privacyPolicyUri = "http://data.europa.eu/eudi/policy/trust-service-practice-statement",
        purpose = listOf(LocalizedText("en", "Learning credential")),
        serviceDescription = emptyList(),
        providedAttestations = emptyList(),
        registeredCredentials = emptyList(),
        status = null,
        expiresAt = null,
        intermediaryIdentifier = null,
    )

    private fun relyingParty(outcome: RelyingPartyRegistrationOutcome, requesterName: String? = accessCertificateName) =
        relyingPartyDomain(
            requesterName = requesterName,
            requesterIsTrusted = requesterName != null,
            registration = outcome,
            locale = "en",
        )

    @Test
    fun a_verified_registration_names_the_verifier_by_its_trade_name() {
        // Watched on 2026-10-02: Android showed "Netcompany Verifier (DEV)", iOS "Verifier Signer dev".
        val relyingParty = relyingParty(RelyingPartyRegistrationOutcome.Verified(registration(), overAsked = emptyList()))

        assertEquals("Netcompany Verifier (DEV)", relyingParty.name)
        assertEquals("LEIXG-123456789", relyingParty.uniqueId)
        assertTrue(relyingParty.isFullyVerified)
    }

    @Test
    fun a_verified_registration_without_a_name_keeps_the_access_certificate_name() {
        val relyingParty = relyingParty(
            RelyingPartyRegistrationOutcome.Verified(registration(name = null), overAsked = emptyList()),
        )

        assertEquals(accessCertificateName, relyingParty.name)
    }

    @Test
    fun a_failed_registration_does_not_rename_a_trusted_verifier_and_drops_the_badge() {
        val relyingParty = relyingParty(
            RelyingPartyRegistrationOutcome.Failed(IssuerRegistrationFailure.REVOKED, registration = registration()),
        )

        // A name the evaluation did not stand behind is never preferred to the trusted certificate's.
        assertEquals(accessCertificateName, relyingParty.name)
        assertFalse(relyingParty.isFullyVerified, "the success screen reads this too")
    }

    @Test
    fun a_failed_registration_names_an_otherwise_unnamed_verifier_as_a_last_resort() {
        val relyingParty = relyingParty(
            RelyingPartyRegistrationOutcome.Failed(IssuerRegistrationFailure.REVOKED, registration = registration()),
            requesterName = null,
        )

        assertEquals("Netcompany Verifier (DEV)", relyingParty.name)
    }

    @Test
    fun a_missing_certificate_warns_as_on_android_and_drops_the_badge() {
        // Watched on Android 2026-10-02 19:08: "registered information could not be obtained", Share held
        // back until the switch was flipped, the verifier unbadged.
        val relyingParty = relyingParty(RelyingPartyRegistrationOutcome.Failed(IssuerRegistrationFailure.CERTIFICATE_ABSENT))

        val registration = assertIs<RegistrationStatusDomain.NotVerified>(relyingParty.registration)
        assertEquals(RegistrationFailureReasonDomain.CERTIFICATE_ABSENT, registration.reason)
        assertNull(registration.details)
        assertEquals(RegistrationWarningVariantUi.NOT_VERIFIED, relyingParty.toRegistrationWarningUi()?.variant)
        assertFalse(relyingParty.isFullyVerified)
        assertEquals(accessCertificateName, relyingParty.name)
    }

    @Test
    fun an_unevaluated_registration_leaves_the_access_certificate_name_and_no_identifier() {
        for (outcome in listOf(RelyingPartyRegistrationOutcome.NotChecked, RelyingPartyRegistrationOutcome.NotOffered)) {
            val relyingParty = relyingParty(outcome)

            assertEquals(accessCertificateName, relyingParty.name, "$outcome")
            assertNull(relyingParty.uniqueId, "$outcome")
            assertTrue(relyingParty.isFullyVerified, "$outcome is judged on the certificate alone")
        }
    }
}
