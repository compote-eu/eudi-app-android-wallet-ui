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

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The **verifier's** registration certificate — the relying-party twin of [IosIssuerRegistration],
 * and the reason that file's parser needed no changes: the media type is literally
 * `rc-wrp+jwt`, *wallet relying party*. The issuer side was the borrowed case.
 *
 * Read from `verifier_info` in the signed request object — a claim multipaz does not parse (zero
 * occurrences at 0.99.0, exactly like `issuer_info` and `deferred_credential_endpoint`), inside a JWS
 * this wallet already opens for `response_uri` and `state`. Measured against the live EU dev verifier
 * on 2026-09-17: a real transaction's request object carries
 * `verifier_info: [{"format":"registration_cert","data":"<rc-wrp+jwt>"}]`.
 *
 * ## What differs from the issuer side, and it is only these
 *
 *  - the entitlement is **`Service_Provider`**, not the provider vocabulary;
 *  - the excess runs the other way — **over-asking** (claims requested but not registered) rather than
 *    over-providing;
 *  - the certificate binds to whoever signed the **request object**, not the issuer metadata;
 *  - the outcome refuses nothing. Android displays it, and a warning holds Share back until the user
 *    accepts the risk, but no outcome blocks the presentation, so none does here.
 *
 * The one refusal is earlier and is about the request's *shape*, not the evaluation: with the check on,
 * an `x509_hash` verifier must carry exactly one certificate, as openid4vp-kt requires — see
 * [registrationCertificateRequirementFailure].
 */
internal object RelyingPartyEntitlements {
    const val SERVICE_PROVIDER = "https://uri.etsi.org/19475/Entitlement/Service_Provider"
}

/** A claim the verifier asked for that its certificate does not register. */
data class OverAskedClaim(
    /** `mso_mdoc` or `dc+sd-jwt`, as the certificate spells it. */
    val format: String,
    /** The claim path as requested: `[namespace, element]` for mdoc, `[key, …]` for SD-JWT VC. */
    val path: List<String>,
    val doctype: String? = null,
    val vctValues: List<String> = emptyList(),
)

sealed interface RelyingPartyRegistrationOutcome {

    /**
     * Nothing could be evaluated: no request object was seen, or no ETSI trust source was configured.
     *
     * ⚠️ Not "the verifier sent no certificate" — since 2026-10-02 that is
     * `Failed(IssuerRegistrationFailure.CERTIFICATE_ABSENT)`, as on Android, where wallet-core reports a
     * missing certificate as a failed registration and the consent screen warns about it.
     */
    data object NotOffered : RelyingPartyRegistrationOutcome

    /** The user has the registration check switched off, so the certificate was not looked at. */
    data object NotChecked : RelyingPartyRegistrationOutcome

    data class Verified(
        val registration: IssuerRegistration,
        val overAsked: List<OverAskedClaim>,
    ) : RelyingPartyRegistrationOutcome

    data class Failed(
        val reason: IssuerRegistrationFailure,
        val registration: IssuerRegistration? = null,
        val detail: String? = null,
    ) : RelyingPartyRegistrationOutcome
}

/**
 * The registration certificate in a request object's `verifier_info`, or null when there is not exactly
 * one usable entry.
 *
 * Read as wallet-core's `extractRegistrationCertificate` reads it: the **single** `registration_cert`
 * entry, carrying no `credential_ids`, whose `data` is a string. Two entries, or one scoped to particular
 * credentials, count as none — which with the check switched on is a missing certificate.
 */
internal fun relyingPartyCertificateIn(requestObject: JsonObject): String? {
    val entry = registrationCertificateEntriesIn(requestObject).singleOrNull() ?: return null
    if (entry["credential_ids"] != null) return null
    return (entry["data"] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/**
 * Why a request object fails the requirement openid4vp-kt places on an `x509_hash` verifier while a
 * registration policy is installed, or null when it meets it.
 *
 * openid4vp-kt 0.15.1's `RegistrationCertificatePolicyEvaluator` asks an `x509_hash` client for
 * `verifier_info` holding **exactly one** `registration_cert` entry with no `credential_ids` and a string
 * `data`, and rejects the request otherwise (`MissingRequiredRegistrationCertificate`,
 * `MultipleRegistrationCertificates`, `MalformedRegistrationCertificate`). wallet-core installs that
 * policy whenever the user's registration check is on, so on Android this is what "on" means for such a
 * verifier. Other client id prefixes are not asked.
 */
internal fun registrationCertificateRequirementFailure(requestObject: JsonObject): String? {
    val entries = registrationCertificateEntriesIn(requestObject)
    return when {
        entries.isEmpty() -> "no registration certificate"
        entries.size > 1 -> "more than one registration certificate"
        entries.single()["credential_ids"] != null -> "a registration certificate scoped to credential_ids"
        (entries.single()["data"] as? JsonPrimitive)?.isString != true -> "a registration certificate that is not a string"
        else -> null
    }
}

private fun registrationCertificateEntriesIn(requestObject: JsonObject): List<JsonObject> =
    (requestObject["verifier_info"] as? JsonArray).orEmpty()
        .mapNotNull { it as? JsonObject }
        .filter { (it["format"] as? JsonPrimitive)?.contentOrNull == REGISTRATION_CERT_FORMAT }

private const val REGISTRATION_CERT_FORMAT = "registration_cert"

/** Every claim a DCQL query asks for, paired with the format and attestation type asking for it. */
internal fun requestedClaimsIn(requestObject: JsonObject): List<OverAskedClaim> =
    requestObject["dcql_query"]?.jsonObject?.get("credentials")?.jsonArray
        ?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
        ?.flatMap { credential ->
            val format = credential["format"]?.jsonPrimitive?.contentOrNull ?: return@flatMap emptyList()
            val meta = credential["meta"]?.jsonObject
            val doctype = meta?.get("doctype_value")?.jsonPrimitive?.contentOrNull
            val vctValues = meta?.get("vct_values")?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            credential["claims"]?.jsonArray
                ?.mapNotNull { claim ->
                    val path = runCatching {
                        claim.jsonObject["path"]?.jsonArray
                            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    }.getOrNull() ?: return@mapNotNull null
                    if (path.isEmpty()) null
                    else OverAskedClaim(format, path, doctype, vctValues)
                }
                .orEmpty()
        }
        .orEmpty()

/**
 * Claims requested but not registered.
 *
 * ⚠️ A claim is registered when the certificate lists the **same path under a credential entry of the
 * same format that covers the same attestation type**. Matching on the path alone would let a verifier
 * registered for a driving licence's `family_name` ask for a PID's.
 */
internal fun IssuerRegistration.overAskedAmong(
    requested: List<OverAskedClaim>,
): List<OverAskedClaim> = requested.filter { claim ->
    registeredCredentials.none { registered -> registered.registers(claim) }
}

private fun RegisteredAttestation.registers(claim: OverAskedClaim): Boolean {
    if (format != claim.format) return false
    val typeMatches = (doctype != null && doctype == claim.doctype) ||
            (vctValues.isNotEmpty() && claim.vctValues.any { it in vctValues })
    return typeMatches && claim.path in claimPaths
}

/** Whether the certificate registers [claim]'s entitlement at all. */
internal fun IssuerRegistration.registersAsServiceProvider(): Boolean =
    RelyingPartyEntitlements.SERVICE_PROVIDER in entitlements
