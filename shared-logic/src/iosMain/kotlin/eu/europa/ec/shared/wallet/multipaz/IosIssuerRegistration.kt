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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.util.fromBase64Url
import kotlin.time.Instant

/**
 * The issuer's registration certificate — ETSI TS 119 475 entitlements, carried in the issuer's own
 * metadata as `issuer_info` (ETSI TS 119 472-3) and answering *"is this issuer registered to issue
 * what it is offering me?"*.
 *
 * ## Why this is written here rather than taken from a library
 *
 * `wallet-core` implements it for Android, and Wallet Kit for the official iOS app. Neither is
 * reachable from Kotlin/Native, and our own [wallet-core#403] asks for the first to be published as
 * KMP — which was answered *"not a candidate until the upstream ecosystem matures"*. That answer is
 * about **porting their implementation**, which is written against nimbus-jose, BouncyCastle and
 * `java.security`. It does not follow that the capability is out of reach, and it is not: every
 * primitive the checks need is in multipaz's `commonMain` or already shipping in this wallet.
 *
 * | what wallet-core reaches for | what this uses instead |
 * |---|---|
 * | nimbus `SignedJWT` + x5c leaf | [org.multipaz.webtoken.validateJwt] |
 * | BouncyCastle `X500Name`/`BCStyle` | [X509Cert.subject]'s `components`, keyed by OID |
 * | ETSI `IsChainTrustedForEUDIW` | the same library, through our own ETSI trust |
 * | `statium` status lists | [org.multipaz.revocation.StatusList] |
 *
 * ⛔ **multipaz does not surface `issuer_info` at all** — zero occurrences at 0.99.0, and
 * `IssuerConfiguration` parses only the endpoints it needs. That is the same omission as
 * `deferred_credential_endpoint`, so it is closed the same way: the field is read from the metadata
 * document this wallet already fetches for itself.
 */
data class IssuerRegistration(
    /** `sub` — the issuer's organization identifier, matched against its signing certificate. */
    val subject: String?,
    val name: String?,
    val legalName: String?,
    val country: String?,
    val entitlements: List<String>,
    /** `privacy_policy` — a plain URI on the wire, not a localized list. */
    val privacyPolicyUri: String?,
    /** `purpose`, localized: `[{"lang": "en", "value": "Identity verification"}]`. */
    val purpose: List<LocalizedText>,
    /** `srv_description`, same shape as [purpose]. */
    val serviceDescription: List<LocalizedText>,
    val providedAttestations: List<RegisteredAttestation>,
    /**
     * `credentials` — what the subject is registered to handle, **with the claim paths**.
     *
     * Distinct from [providedAttestations] (`provides_attestations`), which an issuer certificate also
     * carries and which names attestations without claims. A *verifier's* certificate has only this
     * one, and its claim paths are what an over-asking check compares against.
     */
    val registeredCredentials: List<RegisteredAttestation>,
    val status: StatusReference?,
    val expiresAt: Instant?,
    /** Present when the certificate is held by an intermediary presenting on the issuer's behalf. */
    val intermediaryIdentifier: String?,
)

/** A certificate string carried once per language, as ETSI writes them. */
data class LocalizedText(val language: String, val value: String)

/** Picks the caller's language, falling back to the first entry rather than to nothing. */
fun List<LocalizedText>.forLocale(locale: String): String? =
    firstOrNull { it.language.equals(locale, ignoreCase = true) }?.value
        ?: firstOrNull { locale.startsWith(it.language, ignoreCase = true) }?.value
        ?: firstOrNull()?.value

/** An attestation the certificate says the issuer is registered to provide. */
data class RegisteredAttestation(
    val format: String,
    val doctype: String? = null,
    val vctValues: List<String> = emptyList(),
    /** Registered claim paths: `[namespace, element]` for mdoc, `[key, …]` for SD-JWT VC. */
    val claimPaths: List<List<String>> = emptyList(),
)

/** An attestation the issuer is actually offering in this exchange. */
data class OfferedAttestation(
    val format: String,
    val doctype: String? = null,
    val vctValues: List<String> = emptyList(),
)

/** Where the certificate's revocation status lives. */
data class StatusReference(val uri: String, val index: Int)

sealed interface IssuerRegistrationOutcome {

    /** The issuer publishes no registration certificate. Not a failure — most do not, yet. */
    data object NotOffered : IssuerRegistrationOutcome

    /**
     * The certificate is authentic and the issuer is entitled to everything it offers.
     *
     * [overProvided] names attestations offered but **not** listed in the certificate. Reported rather
     * than refused: it is the user's business to know an issuer is handing out more than it
     * registered, and wallet-core treats it the same way.
     */
    data class Verified(
        val registration: IssuerRegistration,
        val overProvided: List<OfferedAttestation>,
    ) : IssuerRegistrationOutcome

    data class Failed(
        val reason: IssuerRegistrationFailure,
        val registration: IssuerRegistration? = null,
        val detail: String? = null,
    ) : IssuerRegistrationOutcome
}

enum class IssuerRegistrationFailure {
    MALFORMED,
    SIGNATURE_INVALID,
    UNTRUSTED_PROVIDER,
    NOT_BOUND_TO_ISSUER,
    EXPIRED,
    STATUS_MISSING,
    REVOKED,
    REVOCATION_STATUS_UNKNOWN,
    ENTITLEMENT_MISSING,
}

/** ETSI TS 119 475 entitlement identifiers, as they appear in the certificate. */
object IssuerEntitlements {
    private const val BASE = "https://uri.etsi.org/19475/Entitlement"
    const val PID = "$BASE/PID_Provider"
    const val QEAA = "$BASE/QEAA_Provider"
    const val PUB_EAA = "$BASE/PUB_EAA_Provider"
    const val NON_Q_EAA = "$BASE/Non_Q_EAA_Provider"

    val EAA = setOf(QEAA, PUB_EAA, NON_Q_EAA)
}

/**
 * X.520 `organizationIdentifier`. multipaz's `OID` enum stops at `organizationName`, but
 * [org.multipaz.crypto.X500Name.components] is keyed by OID string, so the literal is all that is
 * needed — which is the whole of what BouncyCastle is doing on the Android side.
 */
private const val OID_ORGANIZATION_IDENTIFIER = "2.5.4.97"
private const val OID_SERIAL_NUMBER = "2.5.4.5"

/** The media type an issuer registration certificate declares. */
internal const val REGISTRATION_CERT_TYPE = "rc-wrp+jwt"

/**
 * Reads the issuer registration certificate out of a signed issuer-metadata document.
 *
 * @return the compact JWS, or null when the issuer publishes none.
 */
internal fun issuerRegistrationCertificateIn(metadataPayload: JsonObject): String? =
    metadataPayload["issuer_info"]?.jsonArray
        ?.mapNotNull { it.jsonObject }
        ?.firstOrNull { it["format"]?.jsonPrimitive?.contentOrNull == "registration_cert" }
        ?.get("data")?.jsonPrimitive?.contentOrNull

/** The identifier a certificate is bound to, read from the subject of whoever presented it. */
internal fun X509Cert.registrationIdentifier(): String? =
    subject.components[OID_ORGANIZATION_IDENTIFIER]?.value
        ?: subject.components[OID_SERIAL_NUMBER]?.value

/** Decodes a compact JWS payload without verifying it — verification is [validateIssuerRegistration]. */
internal fun jwsPayload(compact: String): JsonObject? = runCatching {
    Json.parseToJsonElement(compact.split('.')[1].fromBase64Url().decodeToString()).jsonObject
}.getOrNull()

internal fun jwsHeader(compact: String): JsonObject? = runCatching {
    Json.parseToJsonElement(compact.split('.')[0].fromBase64Url().decodeToString()).jsonObject
}.getOrNull()

/** The chain the certificate was signed with, needed to establish whether its signer is trusted. */
internal fun jwsCertificateChain(compact: String): X509CertChain? = runCatching {
    jwsHeader(compact)?.get("x5c")?.let { X509CertChain.fromX5c(it) }
}.getOrNull()

/** Builds the model from a verified certificate payload. */
internal fun issuerRegistrationFrom(payload: JsonObject): IssuerRegistration {
    fun str(key: String) = payload[key]?.jsonPrimitive?.contentOrNull
    return IssuerRegistration(
        subject = str("sub"),
        name = str("name"),
        legalName = str("sub_ln"),
        country = str("country"),
        entitlements = payload["entitlements"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
        privacyPolicyUri = str("privacy_policy"),
        purpose = payload["purpose"].toLocalizedText(),
        serviceDescription = payload["srv_description"].toLocalizedText(),
        providedAttestations = payload["provides_attestations"]?.jsonArray
            ?.mapNotNull { it.jsonObject.toRegisteredAttestation() }.orEmpty(),
        registeredCredentials = payload["credentials"]?.jsonArray
            ?.mapNotNull { runCatching { it.jsonObject }.getOrNull()?.toRegisteredAttestation() }
            .orEmpty(),
        status = payload["status"]?.jsonObject?.get("status_list")?.jsonObject?.let { list ->
            val uri = list["uri"]?.jsonPrimitive?.contentOrNull
            val idx = list["idx"]?.jsonPrimitive?.intOrNull
            if (uri != null && idx != null) StatusReference(uri, idx) else null
        },
        expiresAt = payload["exp"]?.jsonPrimitive?.longOrNull
            ?.let { Instant.fromEpochSeconds(it) },
        intermediaryIdentifier = payload["intermediary"]?.jsonObject
            ?.get("sub")?.jsonPrimitive?.contentOrNull,
    )
}

private fun kotlinx.serialization.json.JsonElement?.toLocalizedText(): List<LocalizedText> =
    runCatching {
        this?.jsonArray?.mapNotNull { entry ->
            val obj = entry.jsonObject
            val lang = obj["lang"]?.jsonPrimitive?.contentOrNull
            val value = obj["value"]?.jsonPrimitive?.contentOrNull
            if (lang != null && value != null) LocalizedText(lang, value) else null
        }
    }.getOrNull().orEmpty()

private fun JsonObject.toRegisteredAttestation(): RegisteredAttestation? {
    val format = this["format"]?.jsonPrimitive?.contentOrNull ?: return null
    val meta = this["meta"]?.jsonObject
    return RegisteredAttestation(
        format = format,
        doctype = meta?.get("doctype_value")?.jsonPrimitive?.contentOrNull,
        vctValues = meta?.get("vct_values")?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
        // 🪤 `claim`, singular, holding a list — the spelling the live certificates use.
        claimPaths = this["claim"]?.jsonArray
            ?.mapNotNull { entry ->
                runCatching {
                    entry.jsonObject["path"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                }.getOrNull()?.takeIf { it.isNotEmpty() }
            }
            .orEmpty(),
    )
}
