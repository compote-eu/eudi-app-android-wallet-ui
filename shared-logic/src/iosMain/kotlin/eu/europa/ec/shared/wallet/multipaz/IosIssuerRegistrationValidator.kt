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

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.util.Logger
import org.multipaz.webtoken.validateJwt
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * Validates an issuer's registration certificate and decides whether it covers what is being offered.
 *
 * The order matches wallet-core's, because the order is the security property: a certificate whose
 * signature or signer cannot be established carries **no** claims worth reading, so nothing it says is
 * shown to the user before that is settled.
 *
 * @param isChainTrusted whether the certificate's signer chain is trusted. In production this is the
 *   ETSI **WRPRC** list — the same list and the same library Android consults, and the same one this
 *   wallet already uses for issuer and reader trust.
 * @param checkRevocation the status-list lookup. Kept injectable because it is the one check that
 *   needs the network *after* the certificate has been read, and the one most likely to be absent.
 * @param isPid decides whether an offered attestation is a PID, which selects the entitlement it
 *   requires. Ours to supply: only this wallet knows which doctype and vct it treats as the PID.
 */
internal class IosIssuerRegistrationValidator(
    private val isChainTrusted: suspend (X509CertChain) -> Boolean,
    private val checkRevocation: suspend (StatusReference) -> RevocationOutcome,
    private val isPid: (OfferedAttestation) -> Boolean,
    private val clock: Clock = Clock.System,
) {

    /**
     * @param metadataPayload the issuer's metadata claims, already parsed.
     * @param metadataSigner the certificate that signed those claims — the issuer's own identity, and
     *   what the certificate must be bound to. Null where the metadata was not signed, which fails the
     *   binding check rather than skipping it.
     */
    suspend fun evaluate(
        metadataPayload: JsonObject,
        metadataSigner: X509Cert?,
        offered: List<OfferedAttestation>,
    ): IssuerRegistrationOutcome {
        val compact = issuerRegistrationCertificateIn(metadataPayload)
            ?: return IssuerRegistrationOutcome.NotOffered

        val declaredType = jwsHeader(compact)?.get("typ")?.jsonPrimitive?.contentOrNull
        if (declaredType != REGISTRATION_CERT_TYPE) {
            return IssuerRegistrationOutcome.Failed(
                IssuerRegistrationFailure.MALFORMED,
                detail = "unexpected typ '$declaredType', expected $REGISTRATION_CERT_TYPE",
            )
        }

        // Read only to fail early and clearly: `validateJwt` parses `x5c` again for itself, but its
        // message for a missing one is not one a user could act on.
        jwsCertificateChain(compact)
            ?: return IssuerRegistrationOutcome.Failed(
                IssuerRegistrationFailure.SIGNATURE_INVALID,
                detail = "no x5c certificate chain",
            )

        // 🪤 `maxValidity` MUST be infinite. multipaz's default is 10 hours and, with no `exp`, it
        // derives one from `iat` — so a registration certificate issued six weeks ago is rejected as
        // "expired" by a check that has nothing to do with the certificate's own validity. Measured
        // against the live EU dev issuer, whose certificate carries `iat` and no `exp`. Expiry is
        // checked below, on the field that actually means it.
        var trusted = false
        val verified = runCatching {
            validateJwt(
                jwt = compact,
                jwtName = "issuer registration certificate",
                maxValidity = Duration.INFINITE,
                certificateChainValidator = { candidate, _ ->
                    isChainTrusted(candidate).also { trusted = it }
                },
            )
        }.getOrElse {
            Logger.w(TAG, "registration certificate did not verify: ${it.message}")
            // multipaz folds "the chain was refused" into the same failure as a bad signature, so the
            // flag above is what tells the two apart — and they are different things to tell a user.
            return IssuerRegistrationOutcome.Failed(
                if (!trusted) IssuerRegistrationFailure.UNTRUSTED_PROVIDER
                else IssuerRegistrationFailure.SIGNATURE_INVALID,
                detail = it.message,
            )
        }
        if (!trusted) {
            return IssuerRegistrationOutcome.Failed(IssuerRegistrationFailure.UNTRUSTED_PROVIDER)
        }

        val registration = issuerRegistrationFrom(verified)

        val presenterId = metadataSigner?.registrationIdentifier()
        val boundTo = registration.intermediaryIdentifier ?: registration.subject
        if (presenterId == null || boundTo == null || presenterId != boundTo) {
            return IssuerRegistrationOutcome.Failed(
                IssuerRegistrationFailure.NOT_BOUND_TO_ISSUER,
                registration,
                detail = "certificate names '$boundTo'; metadata was signed by '$presenterId'",
            )
        }

        registration.expiresAt?.let {
            if (it < clock.now()) {
                return IssuerRegistrationOutcome.Failed(IssuerRegistrationFailure.EXPIRED, registration)
            }
        }

        val status = registration.status
            ?: return IssuerRegistrationOutcome.Failed(
                IssuerRegistrationFailure.STATUS_MISSING,
                registration,
            )
        // The wallet's own revocation type, rather than a parallel one: a registration certificate's
        // status list is the same mechanism as a credential's, down to the signer-trust question.
        when (val outcome = checkRevocation(status)) {
            is RevocationOutcome.Valid -> Unit

            is RevocationOutcome.Invalid, is RevocationOutcome.Suspended ->
                return IssuerRegistrationOutcome.Failed(
                    IssuerRegistrationFailure.REVOKED,
                    registration,
                )

            // ⚠️ Fails closed, deliberately and unlike our *document* revocation posture. A document
            // whose status cannot be read is already in the wallet and the user can still see it; an
            // issuer whose registration cannot be checked has not issued anything yet, so there is
            // nothing to lose by declining to vouch for it. wallet-core makes the same choice.
            is RevocationOutcome.Unknown ->
                return IssuerRegistrationOutcome.Failed(
                    IssuerRegistrationFailure.REVOCATION_STATUS_UNKNOWN,
                    registration,
                    detail = outcome.reason,
                )
        }

        val unmet = registration.unmetEntitlements(offered, isPid)
        if (unmet.isNotEmpty()) {
            Logger.d(
                TAG,
                "issuer '${registration.name}' is not entitled to issue what it offers; " +
                        "unmet: ${unmet.joinToString()}; registered: " +
                        registration.entitlements.joinToString().ifEmpty { "none" },
            )
            return IssuerRegistrationOutcome.Failed(
                IssuerRegistrationFailure.ENTITLEMENT_MISSING,
                registration,
            )
        }

        return IssuerRegistrationOutcome.Verified(
            registration = registration,
            overProvided = registration.overProvidedAmong(offered),
        )
    }

    private companion object {
        const val TAG = "IssuerRegistration"
    }
}

/** Which entitlement an offered attestation demands of the issuer. */
internal enum class EntitlementRequirement { PID_PROVIDER, EAA_PROVIDER }

internal fun IssuerRegistration.unmetEntitlements(
    offered: List<OfferedAttestation>,
    isPid: (OfferedAttestation) -> Boolean,
): Set<EntitlementRequirement> =
    offered
        .map { if (isPid(it)) EntitlementRequirement.PID_PROVIDER else EntitlementRequirement.EAA_PROVIDER }
        .filterNot { requirement ->
            when (requirement) {
                EntitlementRequirement.PID_PROVIDER -> IssuerEntitlements.PID in entitlements
                EntitlementRequirement.EAA_PROVIDER -> entitlements.any { it in IssuerEntitlements.EAA }
            }
        }
        .toSet()

/**
 * Attestations offered but not listed in the certificate.
 *
 * Reported, never refused — and deliberately so: the certificate is a statement about what the issuer
 * *registered* to provide, and an issuer handing out more than that is something to show a user, not
 * something to decide for them.
 */
internal fun IssuerRegistration.overProvidedAmong(
    offered: List<OfferedAttestation>,
): List<OfferedAttestation> = offered.filter { candidate ->
    providedAttestations.none { it.covers(candidate) }
}

private fun RegisteredAttestation.covers(offered: OfferedAttestation): Boolean {
    if (format != offered.format) return false
    val doctypeMatch = doctype != null && doctype == offered.doctype
    val vctMatch = vctValues.isNotEmpty() && offered.vctValues.any { it in vctValues }
    return doctypeMatch || vctMatch
}
