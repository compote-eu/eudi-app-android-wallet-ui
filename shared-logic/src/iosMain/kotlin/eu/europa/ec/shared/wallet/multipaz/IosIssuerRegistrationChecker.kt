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
import eu.europa.ec.shared.wallet.revocation.StatusSignerTrustDomain
import eu.europa.ec.shared.wallet.trust.IosEtsiTrust
import eu.europa.ec.shared.wallet.trust.toTrustChain
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.crypto.X509CertChain
import org.multipaz.revocation.StatusList
import org.multipaz.util.Logger

/**
 * Asks whether an issuer is registered to hand out what it is offering, over the network.
 *
 * [IosIssuerRegistrationValidator] holds the rules; this supplies the three things the rules need from
 * the world — the metadata document, a trust verdict, and a revocation lookup — so that the rules stay
 * unit-testable and this stays the only part that needs an issuer to exist.
 *
 * ⛔ **The metadata is fetched again here rather than taken from the offer read.** multipaz's
 * `OpenID4VCI.getMetadata` parses only the endpoints it needs and drops `issuer_info` entirely (zero
 * occurrences at 0.99.0, exactly like `deferred_credential_endpoint`), so the parsed object cannot
 * answer this. One extra GET of a document the wallet already understands is the cheaper half of that
 * trade.
 */
class IosIssuerRegistrationChecker internal constructor(
    private val httpClient: HttpClient,
    // Internal because [IosEtsiTrust] is: the type is ours and stays ours, but a test in this module
    // must still be able to pin the verdict without reaching the network.
    private val trust: IosEtsiTrust,
) {

    constructor(httpClient: HttpClient) : this(httpClient, IosEtsiTrust())

    /**
     * @param configurationIds the configurations this exchange actually offers, or null for all of
     *   them. Narrowing matters: an issuer may publish more than it is offering, and judging it on the
     *   rest would refuse it for something the user was never shown.
     */
    suspend fun check(
        issuerUrl: String,
        configurationIds: Set<String>? = null,
        isPid: (OfferedAttestation) -> Boolean = ::looksLikeThePid,
    ): IssuerRegistrationOutcome {
        val document = runCatching {
            httpClient.get("${issuerUrl.trimEnd('/')}/$ISSUER_METADATA_PATH").bodyAsText().trim()
        }.getOrElse {
            Logger.w(TAG, "issuer metadata could not be read: ${it.message}")
            return IssuerRegistrationOutcome.Failed(
                IssuerRegistrationFailure.MALFORMED,
                detail = "issuer metadata could not be read",
            )
        }

        // Unsigned metadata has no signer, and the certificate's whole binding is to that signer. Say so
        // rather than reporting a certificate that nothing ties to this issuer.
        val payload = jwsPayload(document)
            ?: return IssuerRegistrationOutcome.NotOffered
        val signer = jwsCertificateChain(document)?.certificates?.firstOrNull()

        return IosIssuerRegistrationValidator(
            isChainTrusted = { chain -> chain.isTrustedFor(REGISTRATION_CONTEXT) },
            checkRevocation = { reference -> currentStatusOf(reference) },
            isPid = isPid,
        ).evaluate(
            metadataPayload = payload,
            metadataSigner = signer,
            // Derived from the document just fetched, so the offer and the judgement cannot drift.
            offered = offeredAttestationsIn(payload, configurationIds),
        )
    }

    private suspend fun X509CertChain.isTrustedFor(context: VerificationContext): Boolean =
        runCatching { trust.isTrusted(certificates.toTrustChain(), context) }
            .getOrElse {
                Logger.w(TAG, "trust check for $context failed to complete: ${it.message}")
                false
            }

    private suspend fun currentStatusOf(reference: StatusReference): RevocationOutcome =
        registrationStatusOf(reference, httpClient) { chain -> chain.isTrustedFor(STATUS_CONTEXT) }

    private companion object {
        const val TAG = "IssuerRegistrationChecker"
        const val ISSUER_METADATA_PATH = ".well-known/openid-credential-issuer"
        const val STATUS_VALID = 0

        /**
         * Both halves use the **relying-party** registration lists, which reads oddly until you see the
         * media type: the certificate declares `rc-wrp+jwt`, and wallet-core resolves issuer
         * registration against these same two contexts.
         */
        val REGISTRATION_CONTEXT = VerificationContext.WalletRelyingPartyRegistrationCertificate
        val STATUS_CONTEXT = VerificationContext.WalletRelyingPartyRegistrationCertificateStatus
    }
}

/**
 * Which offered attestations this wallet treats as the PID, and therefore which demand the
 * `PID_Provider` entitlement rather than an EAA one.
 *
 * Ours to define: the certificate says what an issuer may provide, but only the wallet knows which of
 * the things on offer *is* the PID.
 */
fun looksLikeThePid(offered: OfferedAttestation): Boolean =
    offered.doctype == PID_DOCTYPE || offered.vctValues.any { it in PID_VCT_VALUES }

private const val PID_DOCTYPE = "eu.europa.ec.eudi.pid.1"
private val PID_VCT_VALUES = setOf("urn:eudi:pid:1", "eu.europa.ec.eudi.pid_vc_sd_jwt")

/** The attestations a credential-issuer metadata document says are on offer. */
fun offeredAttestationsIn(
    metadataPayload: kotlinx.serialization.json.JsonObject,
    configurationIds: Set<String>? = null,
): List<OfferedAttestation> =
    metadataPayload["credential_configurations_supported"]?.jsonObject
        ?.filterKeys { configurationIds == null || it in configurationIds }
        ?.values
        ?.mapNotNull { element ->
            val config = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            when (config["format"]?.jsonPrimitive?.contentOrNull) {
                "mso_mdoc" -> OfferedAttestation(
                    format = "mso_mdoc",
                    doctype = config["doctype"]?.jsonPrimitive?.contentOrNull,
                )

                "dc+sd-jwt" -> OfferedAttestation(
                    format = "dc+sd-jwt",
                    vctValues = listOfNotNull(config["vct"]?.jsonPrimitive?.contentOrNull),
                )

                else -> null
            }
        }
        .orEmpty()


/**
 * The current status of a registration certificate, whichever side issued it.
 *
 * 🪤 `StatusList.fromJwt(token)` on its own throws `IllegalArgumentException: Failed requirement.` —
 * multipaz's `validateJwt` demands `publicKey != null || caValidated`, and its basic chain validator
 * cannot anchor a self-contained `x5c`. So the signer is checked by the caller, against the EU list
 * meant for exactly this, and its key is handed in. wallet-core's status verifier does the same thing
 * for the same reason.
 *
 * Shared by the issuer and relying-party validators because the mechanism is identical and the trap
 * above is worth having in one place rather than two.
 */
internal suspend fun registrationStatusOf(
    reference: StatusReference,
    httpClient: HttpClient,
    isSignerTrusted: suspend (X509CertChain) -> Boolean,
): RevocationOutcome = runCatching {
    val token = httpClient.get(reference.uri).bodyAsText().trim()
    val signer = jwsCertificateChain(token)?.certificates?.firstOrNull()
        ?: return RevocationOutcome.Unknown("status list token carries no x5c")
    val signerTrust = if (isSignerTrusted(X509CertChain(listOf(signer)))) {
        StatusSignerTrustDomain.Trusted
    } else {
        StatusSignerTrustDomain.NotTrusted
    }
    // 0.101.0: StatusList.fromJwt validates a chain up to a trusted root rather than matching a bare
    // public key — see MultipazRevocationChecker.kt's identical fix for why passing this already
    // separately-trusted leaf cert (the same one used two lines up for isSignerTrusted) is equivalent,
    // not weaker.
    when (StatusList.fromJwt(token, trustedRootCert = signer)[reference.index]) {
        0 -> RevocationOutcome.Valid(signerTrust)
        else -> RevocationOutcome.Invalid(signerTrust)
    }
}.getOrElse {
    Logger.w("RegistrationStatus", "registration certificate status could not be read: ${it.message}")
    RevocationOutcome.Unknown(it.message ?: "status list could not be read")
}
