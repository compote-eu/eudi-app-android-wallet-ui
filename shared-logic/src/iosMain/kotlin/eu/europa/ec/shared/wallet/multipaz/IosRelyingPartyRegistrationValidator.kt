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

import eu.europa.ec.eudi.etsi1196x2.consultation.VerificationContext
import eu.europa.ec.shared.wallet.trust.IosEtsiTrust
import eu.europa.ec.shared.wallet.trust.toTrustChain
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
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
 * Validates the **verifier's** registration certificate and reports what it does not cover.
 *
 * Deliberately close to [IosIssuerRegistrationValidator] and deliberately not merged with it: the two
 * share a certificate format and share nothing else. This one answers *may this verifier ask me for
 * these claims?*, checks a different entitlement, binds to a different certificate, and — unlike the
 * issuer side — **refuses nothing**. Android displays the outcome on the consent screen and blocks no
 * presentation on it; a wallet that silently dropped requests would be worse than one that says who is
 * asking and lets the user decide. (The `x509_hash` shape rule is enforced before this runs, by the
 * request observer — see [registrationCertificateRequirementFailure].)
 *
 * @param isChainTrusted the ETSI **WRPRC** list, the same one the issuer side consults — which reads
 *   oddly there and naturally here, since `rc-wrp+jwt` is the relying-party format.
 */
internal class IosRelyingPartyRegistrationValidator(
    private val isChainTrusted: suspend (X509CertChain) -> Boolean,
    private val checkRevocation: suspend (StatusReference) -> RevocationOutcome,
    private val clock: Clock = Clock.System,
) {

    /**
     * @param requestObject the verifier's signed request object, already decoded.
     * @param requestSigner the certificate that signed it — what the registration must be bound to.
     */
    suspend fun evaluate(
        requestObject: JsonObject,
        requestSigner: X509Cert?,
    ): RelyingPartyRegistrationOutcome {
        // A failure, not "nothing to evaluate": the user asked for registrations to be checked, and wallet-core
        // answers a missing one with CERTIFICATE_ABSENT, which the consent screen warns about. The official iOS
        // wallet stays silent here; Android is the reference this fork matches.
        val compact = relyingPartyCertificateIn(requestObject)
            ?: return RelyingPartyRegistrationOutcome.Failed(IssuerRegistrationFailure.CERTIFICATE_ABSENT).logged()
        return evaluateCertificate(compact, requestSigner, requestedClaimsIn(requestObject))
    }

    /**
     * The checks themselves, for a certificate however it arrived — in a request object's `verifier_info`,
     * or in an ISO 18013-5 request's `euWrprc` ([readerRegistrationCertificate]).
     *
     * @param compact the certificate as a compact JWS.
     * @param presenter the certificate the registration must be bound to: whoever signed the request
     *   object, or the reader that authenticated the device request.
     * @param requested every claim asked for, to find what the registration does not cover.
     */
    suspend fun evaluateCertificate(
        compact: String,
        presenter: X509Cert?,
        requested: List<OverAskedClaim>,
    ): RelyingPartyRegistrationOutcome = checkCertificate(compact, presenter, requested).logged()

    private suspend fun checkCertificate(
        compact: String,
        presenter: X509Cert?,
        requested: List<OverAskedClaim>,
    ): RelyingPartyRegistrationOutcome {
        val declaredType = jwsHeader(compact)?.get("typ")?.jsonPrimitive?.contentOrNull
        if (declaredType != REGISTRATION_CERT_TYPE) {
            return RelyingPartyRegistrationOutcome.Failed(
                IssuerRegistrationFailure.MALFORMED,
                detail = "unexpected typ '$declaredType', expected $REGISTRATION_CERT_TYPE",
            )
        }
        jwsCertificateChain(compact) ?: return RelyingPartyRegistrationOutcome.Failed(
            IssuerRegistrationFailure.SIGNATURE_INVALID,
            detail = "no x5c certificate chain",
        )

        // 🪤 `maxValidity` must be infinite, for the reason spelled out on the issuer side: multipaz
        // derives an expiry from `iat` when there is no `exp`, and these certificates carry no `exp`.
        var trusted = false
        val verified = runCatching {
            validateJwt(
                jwt = compact,
                jwtName = "relying party registration certificate",
                maxValidity = Duration.INFINITE,
                certificateChainValidator = { candidate, _ ->
                    isChainTrusted(candidate).also { trusted = it }
                },
            )
        }.getOrElse {
            Logger.w(TAG, "registration certificate did not verify: ${it.message}")
            return RelyingPartyRegistrationOutcome.Failed(
                if (!trusted) IssuerRegistrationFailure.UNTRUSTED_PROVIDER
                else IssuerRegistrationFailure.SIGNATURE_INVALID,
                detail = it.message,
            )
        }
        if (!trusted) {
            return RelyingPartyRegistrationOutcome.Failed(IssuerRegistrationFailure.UNTRUSTED_PROVIDER)
        }
        Logger.i(TAG, "registration certificate authenticated")

        val registration = issuerRegistrationFrom(verified)

        val presenterId = presenter?.registrationIdentifier()
        val boundTo = registration.intermediaryIdentifier ?: registration.subject
        if (presenterId == null || boundTo == null || presenterId != boundTo) {
            return RelyingPartyRegistrationOutcome.Failed(
                IssuerRegistrationFailure.NOT_BOUND_TO_ISSUER,
                registration,
                detail = "certificate names '$boundTo'; the request was signed by '$presenterId'",
            )
        }

        registration.expiresAt?.let {
            if (it < clock.now()) {
                return RelyingPartyRegistrationOutcome.Failed(
                    IssuerRegistrationFailure.EXPIRED,
                    registration,
                )
            }
        }

        val status = registration.status ?: return RelyingPartyRegistrationOutcome.Failed(
            IssuerRegistrationFailure.STATUS_MISSING,
            registration,
        )
        when (val outcome = checkRevocation(status)) {
            is RevocationOutcome.Valid -> Unit

            is RevocationOutcome.Invalid, is RevocationOutcome.Suspended ->
                return RelyingPartyRegistrationOutcome.Failed(
                    IssuerRegistrationFailure.REVOKED,
                    registration,
                )

            is RevocationOutcome.Unknown ->
                return RelyingPartyRegistrationOutcome.Failed(
                    IssuerRegistrationFailure.REVOCATION_STATUS_UNKNOWN,
                    registration,
                    detail = outcome.reason,
                )
        }

        if (!registration.registersAsServiceProvider()) {
            Logger.d(
                TAG,
                "verifier '${registration.name}' is not registered as a service provider; " +
                        "registered: ${registration.entitlements.joinToString().ifEmpty { "none" }}",
            )
            return RelyingPartyRegistrationOutcome.Failed(
                IssuerRegistrationFailure.ENTITLEMENT_MISSING,
                registration,
            )
        }

        val overAsked = registration.overAskedAmong(requested)
        if (overAsked.isNotEmpty()) {
            Logger.d(
                TAG,
                "verifier '${registration.name}' is OVER-ASKING for ${overAsked.size} claim(s): " +
                        overAsked.joinToString { "${it.format}:${it.path.joinToString(".")}" },
            )
        }
        return RelyingPartyRegistrationOutcome.Verified(registration, overAsked)
    }

    /**
     * Logs a failed evaluation with its reason, at INFO: the iOS log file keeps no debug lines, and most of
     * the exits above return without a message. Android's wallet-core logs the same lines at debug.
     */
    private fun RelyingPartyRegistrationOutcome.logged(): RelyingPartyRegistrationOutcome = also { outcome ->
        if (outcome is RelyingPartyRegistrationOutcome.Failed) {
            Logger.i(TAG, "registration evaluation failed: ${outcome.reason}" + outcome.detail?.let { " ($it)" }.orEmpty())
        }
    }

    private companion object {
        const val TAG = "RelyingPartyRegistration"
    }
}

/**
 * Runs [evaluation] on a validator over this wallet's ETSI lists: the WRPRC list for the certificate's own
 * chain, its status context for the status list that says whether it was revoked. One HTTP client serves
 * the status fetch and is closed afterwards.
 *
 * Throws what [evaluation] throws; each caller decides what a registration that cannot be judged means.
 */
internal suspend fun withEtsiRegistrationValidator(
    etsi: IosEtsiTrust,
    evaluation: suspend IosRelyingPartyRegistrationValidator.() -> RelyingPartyRegistrationOutcome,
): RelyingPartyRegistrationOutcome = HttpClient(Darwin).use { client ->
    IosRelyingPartyRegistrationValidator(
        isChainTrusted = { chain ->
            etsi.isTrusted(chain.certificates.toTrustChain(), VerificationContext.WalletRelyingPartyRegistrationCertificate)
        },
        checkRevocation = { reference ->
            registrationStatusOf(reference, client) { chain ->
                etsi.isTrusted(
                    chain.certificates.toTrustChain(),
                    VerificationContext.WalletRelyingPartyRegistrationCertificateStatus,
                )
            }
        },
    ).evaluation()
}
