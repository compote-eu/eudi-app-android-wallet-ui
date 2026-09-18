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

package eu.europa.ec.shared.ui.di

import eu.europa.ec.corelogic.model.IssuerRegistrationDomain
import eu.europa.ec.corelogic.model.RegistrationFailureReasonDomain
import eu.europa.ec.corelogic.model.isBlockedForIssuance
import eu.europa.ec.shared.wallet.multipaz.IssuerRegistration
import eu.europa.ec.shared.wallet.multipaz.IssuerRegistrationFailure
import eu.europa.ec.shared.wallet.multipaz.IssuerRegistrationOutcome
import eu.europa.ec.shared.wallet.multipaz.LocalizedText
import eu.europa.ec.shared.wallet.multipaz.OfferedAttestation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Turning an iOS registration outcome into the shape the shared offer screen reads.
 *
 * ⚠️ **Every rule here was read off Android's `toIssuerRegistrationDomain` rather than decided**, because
 * they are product decisions and a divergence would be silent — the screen would simply render something
 * different on one platform. The two that would have been got wrong by reading only the library are
 * pinned first.
 */
class IosIssuerRegistrationMappingTest {

    private fun registration(
        subject: String? = "LEIXG-123456789",
        name: String? = "Test PID Provider",
    ) = IssuerRegistration(
        subject = subject,
        name = name,
        legalName = "Test Legal Name",
        country = "EU",
        entitlements = emptyList(),
        privacyPolicyUri = "https://policy.test",
        purpose = listOf(LocalizedText("en", "Identity verification"), LocalizedText("sk", "Overenie")),
        serviceDescription = listOf(LocalizedText("en", "PID issuer")),
        providedAttestations = emptyList(),
        registeredCredentials = emptyList(),
        status = null,
        expiresAt = null,
        intermediaryIdentifier = null,
    )

    @Test
    fun over_providing_blocks_issuance_rather_than_merely_being_reported() {
        val outcome = IssuerRegistrationOutcome.Verified(
            registration = registration(),
            overProvided = listOf(OfferedAttestation(format = "dc+sd-jwt", vctValues = listOf("x"))),
        )

        val domain = outcome.toDomain(locale = "en")

        // 🪤 The library's evaluator only *reports* over-providing, and this port first mirrored that.
        // Android blocks at the app layer, which is where the decision actually lives — reading one
        // layer and assuming the other is what produced the wrong answer.
        val blocked = assertIs<IssuerRegistrationDomain.Blocked>(domain)
        assertEquals(IssuerRegistrationDomain.BlockedReasonDomain.ATTESTATION_OVER_PROVIDED, blocked.reason)
        assertTrue(domain.isBlockedForIssuance)
    }

    @Test
    fun a_missing_entitlement_blocks_when_the_certificate_said_whose_it_is() {
        val outcome = IssuerRegistrationOutcome.Failed(
            reason = IssuerRegistrationFailure.ENTITLEMENT_MISSING,
            registration = registration(),
        )

        val domain = outcome.toDomain(locale = "en")

        val blocked = assertIs<IssuerRegistrationDomain.Blocked>(domain)
        assertEquals(IssuerRegistrationDomain.BlockedReasonDomain.ENTITLEMENT_MISSING, blocked.reason)
        assertEquals("Test PID Provider", blocked.details.tradeName)
    }

    @Test
    fun a_missing_entitlement_with_no_readable_certificate_is_an_ordinary_failure() {
        val outcome = IssuerRegistrationOutcome.Failed(
            reason = IssuerRegistrationFailure.ENTITLEMENT_MISSING,
            registration = null,
        )

        val domain = outcome.toDomain(locale = "en")

        // There is nobody to name on the screen, so "blocked, and here is who" would be a lie.
        val notVerified = assertIs<IssuerRegistrationDomain.NotVerified>(domain)
        assertEquals(RegistrationFailureReasonDomain.ENTITLEMENT_MISSING, notVerified.reason)
        assertNull(notVerified.details)
    }

    @Test
    fun a_clean_certificate_verifies_and_carries_its_details() {
        val outcome = IssuerRegistrationOutcome.Verified(registration(), overProvided = emptyList())

        val domain = outcome.toDomain(locale = "en")

        val verified = assertIs<IssuerRegistrationDomain.Verified>(domain)
        assertEquals("Test PID Provider", verified.details.tradeName)
        assertEquals("LEIXG-123456789", verified.details.uniqueId)
        assertEquals("Identity verification", verified.details.intendedUse)
        assertEquals("https://policy.test", verified.details.privacyPolicyUrl)
        assertEquals(false, domain.isBlockedForIssuance)
    }

    @Test
    fun localized_certificate_text_follows_the_caller_s_locale() {
        val domain = IssuerRegistrationOutcome
            .Verified(registration(), overProvided = emptyList())
            .toDomain(locale = "sk")

        val verified = assertIs<IssuerRegistrationDomain.Verified>(domain)
        assertEquals("Overenie", verified.details.intendedUse)
        // Only English exists for this one, and a missing translation must not blank the field.
        assertEquals("PID issuer", verified.details.serviceDescription)
    }

    @Test
    fun an_issuer_publishing_no_certificate_is_not_evaluated_rather_than_failed() {
        val domain = IssuerRegistrationOutcome.NotOffered.toDomain(locale = "en")

        // Almost no issuer publishes one yet. `NotEvaluated` is what the settings flag is read
        // alongside — on its own it would refuse the whole ecosystem.
        assertIs<IssuerRegistrationDomain.NotEvaluated>(domain)
    }

    @Test
    fun the_binding_failure_keeps_its_meaning_across_the_two_enum_spellings() {
        val domain = IssuerRegistrationOutcome
            .Failed(IssuerRegistrationFailure.NOT_BOUND_TO_ISSUER, registration())
            .toDomain(locale = "en")

        // The shared enum was named for the relying-party side; the two mean the same thing.
        val notVerified = assertIs<IssuerRegistrationDomain.NotVerified>(domain)
        assertEquals(RegistrationFailureReasonDomain.NOT_BOUND_TO_REQUESTER, notVerified.reason)
    }

    @Test
    fun every_failure_reason_maps_to_one_of_its_own() {
        // A new reason must not quietly land on a neighbour's meaning; this fails to compile-or-pass
        // the moment the two enums drift.
        val mapped = IssuerRegistrationFailure.entries.map { reason ->
            val domain = IssuerRegistrationOutcome.Failed(reason, registration()).toDomain("en")
            when (domain) {
                is IssuerRegistrationDomain.NotVerified -> domain.reason.name
                is IssuerRegistrationDomain.Blocked -> domain.reason.name
                else -> "unmapped"
            }
        }
        assertEquals(IssuerRegistrationFailure.entries.size, mapped.toSet().size)
    }
}
