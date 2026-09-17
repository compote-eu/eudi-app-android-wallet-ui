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
import eu.europa.ec.corelogic.model.RegistrationDetailsDomain
import eu.europa.ec.corelogic.model.RegistrationFailureReasonDomain
import eu.europa.ec.shared.wallet.multipaz.IssuerRegistration
import eu.europa.ec.shared.wallet.multipaz.IssuerRegistrationFailure
import eu.europa.ec.shared.wallet.multipaz.IssuerRegistrationOutcome
import eu.europa.ec.shared.wallet.multipaz.forLocale

/**
 * The iOS registration outcome in the shape the shared screens already read.
 *
 * ⚠️ **Written against Android's `toIssuerRegistrationDomain`, not invented**, because the rules it
 * encodes are product decisions rather than iOS ones — and two of them are not obvious:
 *
 *  - **over-providing blocks.** The library's evaluator only *reports* it, so reading that layer alone
 *    (as this port first did) produces a wallet that shrugs at an issuer handing out attestations it
 *    never registered. The app layer is where the refusal lives on Android, and it belongs here too.
 *  - **a missing entitlement blocks only when the certificate was readable enough to say whose it is.**
 *    Without details there is nobody to name on the screen, so it degrades to an ordinary failure.
 */
internal fun IssuerRegistrationOutcome.toDomain(locale: String): IssuerRegistrationDomain =
    when (this) {
        is IssuerRegistrationOutcome.NotOffered -> IssuerRegistrationDomain.NotEvaluated

        is IssuerRegistrationOutcome.Verified ->
            if (overProvided.isEmpty()) {
                IssuerRegistrationDomain.Verified(details = registration.toDetails(locale))
            } else {
                IssuerRegistrationDomain.Blocked(
                    reason = IssuerRegistrationDomain.BlockedReasonDomain.ATTESTATION_OVER_PROVIDED,
                    details = registration.toDetails(locale),
                )
            }

        is IssuerRegistrationOutcome.Failed -> {
            val details = registration?.toDetails(locale)
            if (reason == IssuerRegistrationFailure.ENTITLEMENT_MISSING && details != null) {
                IssuerRegistrationDomain.Blocked(
                    reason = IssuerRegistrationDomain.BlockedReasonDomain.ENTITLEMENT_MISSING,
                    details = details,
                )
            } else {
                IssuerRegistrationDomain.NotVerified(
                    reason = reason.toDomain(),
                    details = details,
                )
            }
        }
    }

private fun IssuerRegistration.toDetails(locale: String) = RegistrationDetailsDomain(
    tradeName = name ?: legalName,
    uniqueId = subject,
    // The certificate carries no logo; the offer's own issuer logo is shown beside this.
    logoUri = null,
    intendedUse = purpose.forLocale(locale),
    privacyPolicyUrl = privacyPolicyUri,
    serviceDescription = serviceDescription.forLocale(locale),
)

/**
 * ⚠️ `NOT_BOUND_TO_ISSUER` maps to `NOT_BOUND_TO_REQUESTER`: the shared enum was named for the
 * relying-party side, and the two mean the same thing — the certificate names somebody other than
 * whoever presented it.
 */
private fun IssuerRegistrationFailure.toDomain(): RegistrationFailureReasonDomain = when (this) {
    IssuerRegistrationFailure.MALFORMED -> RegistrationFailureReasonDomain.MALFORMED
    IssuerRegistrationFailure.SIGNATURE_INVALID -> RegistrationFailureReasonDomain.SIGNATURE_INVALID
    IssuerRegistrationFailure.UNTRUSTED_PROVIDER -> RegistrationFailureReasonDomain.UNTRUSTED_PROVIDER
    IssuerRegistrationFailure.NOT_BOUND_TO_ISSUER -> RegistrationFailureReasonDomain.NOT_BOUND_TO_REQUESTER
    IssuerRegistrationFailure.EXPIRED -> RegistrationFailureReasonDomain.EXPIRED
    IssuerRegistrationFailure.STATUS_MISSING -> RegistrationFailureReasonDomain.STATUS_MISSING
    IssuerRegistrationFailure.REVOKED -> RegistrationFailureReasonDomain.REVOKED
    IssuerRegistrationFailure.REVOCATION_STATUS_UNKNOWN ->
        RegistrationFailureReasonDomain.REVOCATION_STATUS_UNKNOWN

    IssuerRegistrationFailure.ENTITLEMENT_MISSING ->
        RegistrationFailureReasonDomain.ENTITLEMENT_MISSING
}
