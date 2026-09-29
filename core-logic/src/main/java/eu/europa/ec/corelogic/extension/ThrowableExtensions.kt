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
import io.ktor.client.call.NoTransformationFoundException

/**
 * Which trust layer refused the issuer behind this failure, or null when the failure is not about
 * trust and the caller should surface it as an ordinary error. An untrusted issuer chain or
 * missing/invalid signed metadata reads as [UntrustedIssuerReasonDomain.ACCESS_CERTIFICATE]; an
 * absent registration certificate, or one that fails policy validation, reads as
 * [UntrustedIssuerReasonDomain.REGISTRATION_CERTIFICATE].
 */
fun Throwable.toUntrustedIssuerReasonOrNull(): UntrustedIssuerReasonDomain? {
    var throwable: Throwable? = this
    while (throwable != null) {
        if (throwable is IssuerNotTrustedException ||
            throwable is CredentialIssuerMetadataError.InvalidSignedMetadata ||
            throwable is CredentialIssuerMetadataError.MissingSignedMetadata
        ) {
            return UntrustedIssuerReasonDomain.ACCESS_CERTIFICATE
        }

        if (throwable is AuthorizationPolicyValidationError) {
            return UntrustedIssuerReasonDomain.REGISTRATION_CERTIFICATE
        }

        // CredentialOfferRequestException carries its error in a property, not in `cause`
        val offerError = (throwable as? CredentialOfferRequestException)?.error
        throwable =
            if (offerError is CredentialOfferRequestError.UnableToResolveCredentialIssuerMetadata) {
                offerError.reason
            } else {
                throwable.cause
            }
    }
    return null
}

/**
 * Whether a deferred credential query failed in a way no later poll can change, so the parked document
 * is to be treated as expired — and removed — rather than asked about again every few seconds.
 *
 * wallet-core 0.30.2 cannot tell these apart from a passing failure itself. It restores a parked
 * document's access token **without its `expires_in`** (`DeferredContext.fromBytes`), so its own expiry
 * check never fires and openid4vci-kt never refreshes before polling (`refreshIfNeeded`): once the issuer
 * stops accepting the token, the same `401 invalid_token` comes back on every poll, for ever. Measured on
 * 2026-09-28/29 against Plaut's dev issuer, which rejects its token ~40 s after issuing it — in this wallet
 * and in Plaut's own, both polling every ~6 s indefinitely. `invalid_transaction_id` — a spent or unknown
 * handle, OpenID4VCI 1.0 §9.3 — is just as final.
 *
 * Two shapes reach here:
 * - a JSON error body, which openid4vci-kt reads as `Errored` and wallet-core turns into
 *   `IllegalStateException(error)` (`ProcessDeferredOutcome`);
 * - a `401` with an **empty** body and the error only in `WWW-Authenticate` (Plaut's issuer), which
 *   openid4vci-kt cannot parse and fails as Ktor's [NoTransformationFoundException] naming the 401.
 *   ⚠️ That exception does not carry the challenge, so any 401 there is taken as the token's end.
 *   openid4vci-kt has already answered the one challenge it acts on, a DPoP nonce, before it gets here.
 */
fun Throwable.isTerminalDeferredFailure(): Boolean =
    generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH).any { failure ->
        (failure is IllegalStateException && failure.message in TERMINAL_DEFERRED_ERRORS) ||
            (failure is NoTransformationFoundException &&
                failure.message.orEmpty().contains(RESPONSE_STATUS_UNAUTHORIZED))
    }

private val TERMINAL_DEFERRED_ERRORS = setOf("invalid_token", "invalid_transaction_id")

/** How Ktor names the status in a [NoTransformationFoundException]'s message. */
private const val RESPONSE_STATUS_UNAUTHORIZED = "Response status `401"

private const val MAX_CAUSE_DEPTH = 16
