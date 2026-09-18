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
 *  - nothing is refused. Android displays the outcome and never blocks a presentation on it, so
 *    neither does this.
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

    /** The request carries no registration certificate. Most verifiers publish none yet. */
    data object NotOffered : RelyingPartyRegistrationOutcome

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

/** The compact JWS in a request object's `verifier_info`, or null when it carries none. */
internal fun relyingPartyCertificateIn(requestObject: JsonObject): String? =
    requestObject["verifier_info"]?.jsonArray
        ?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
        ?.firstOrNull { it["format"]?.jsonPrimitive?.contentOrNull == "registration_cert" }
        ?.get("data")?.jsonPrimitive?.contentOrNull

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
