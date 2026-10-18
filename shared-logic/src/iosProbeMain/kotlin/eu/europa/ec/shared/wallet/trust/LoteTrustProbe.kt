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

package eu.europa.ec.shared.wallet.trust

import eu.europa.ec.eudi.etsi1196x2.consultation.VerificationContext
import eu.europa.ec.shared.wallet.multipaz.harness.createVerifierTransaction
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.cinterop.BetaInteropApi
import kotlin.time.Clock
import platform.Foundation.NSData
import platform.Foundation.create
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Whether [IosEtsiTrust] reaches the same verdict on iOS that Android reaches.
 *
 * Not a unit test: it needs the live EU trust lists and a live issuer, so it belongs to a probe run.
 * What it exercises is the real component — the real [LoteJwtVerifier], the real relaxations — not a
 * stub, so a `Trusted` here means the wiring is at parity with Android's `configureEtsiTrust`.
 */
suspend fun probeLoteTrustLists(onResult: (String) -> Unit) {
    onResult("--- issuer trust (LOTE) ---")

    val trust = IosEtsiTrust()
    val chain = runCatching { issuerMetadataSignerChain() }.getOrElse {
        onResult("  could not read the issuer's metadata chain: ${it::class.simpleName}: ${it.message}")
        return
    }
    if (chain.isEmpty()) {
        onResult("  the issuer's metadata carried no x5c, so there is no chain to check")
        return
    }

    // The real question: is the signer of *this issuer's* signed metadata on the EU access-certificate
    // list? ⛔ WRPAC, not PID — metadata signers are access certificates; the PID list is for what signs
    // PID *credentials*. This probe asked for PID until 2026-09-18, which is the mistake that made the
    // end-entity profiles look unusable.
    val trusted = trust.isTrusted(chain, VerificationContext.WalletRelyingPartyAccessCertificate)
    onResult(
        "  $ISSUER_METADATA_URL signer (${chain.size} cert(s)) " +
                "trusted for WRPAC = $trusted"
    )

    // The other WRPAC consumer: a real verifier's access certificate, from a live request object. Both
    // are checked because the ETSI end-entity profiles are now applied rather than relaxed — a verifier
    // that silently stopped being trusted is the failure that change could cause, so it is watched.
    val verifierChain = runCatching { verifierRequestSignerChain(onResult) }.getOrElse { emptyList() }
    if (verifierChain.isEmpty()) {
        onResult("  (no live verifier chain to check)")
    } else {
        onResult(
            "  verifier access certificate (${verifierChain.size} cert(s)) = ${
                trust.verdict(verifierChain, VerificationContext.WalletRelyingPartyAccessCertificate)
            }"
        )
    }

    // A chain that is not in any list must come back false rather than throwing — the guard that keeps a
    // trust failure from taking down issuance.
    val bogus = chain.take(1)
    onResult("  same chain against WalletProviderAttestation (no list configured) = " +
            trust.isTrusted(bogus, VerificationContext.WalletProviderAttestation))

    // The verdict, not just the boolean: NOT_TRUSTED and UNDETERMINED are treated differently by
    // both callers, so a probe that printed only `isTrusted` could not tell a refusal from a
    // network failure — which is the distinction the whole enum exists for.
    onResult("  verdict for WRPAC = ${trust.verdict(chain, VerificationContext.WalletRelyingPartyAccessCertificate)}")

    // The second call must not re-download: the lists are cached on disk in the app group. A
    // noticeably faster second verdict is what says the cache is being read.
    val startedAt = Clock.System.now()
    val again = trust.verdict(chain, VerificationContext.WalletRelyingPartyAccessCertificate)
    onResult("  second verdict = $again in ${Clock.System.now() - startedAt} (cache warm)")
}

/**
 * The `x5c` chain from the issuer's signed metadata, as DER in [NSData].
 *
 * `x5c` entries are standard-alphabet base64 DER, which is exactly what
 * `NSData(base64Encoded:)` wants — so no byte-array juggling is needed.
 */
@OptIn(ExperimentalEncodingApi::class, BetaInteropApi::class)
private suspend fun issuerMetadataSignerChain(): List<NSData> {
    val client = HttpClient(Darwin.create())
    val jwt = try {
        client.get(ISSUER_METADATA_URL).bodyAsText().trim()
    } finally {
        client.close()
    }
    val header = jwt.split('.').firstOrNull() ?: return emptyList()
    val decoded = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
        .decode(header)
        .decodeToString()
    return Json.parseToJsonElement(decoded).jsonObject["x5c"]
        ?.jsonArray
        ?.mapNotNull { entry ->
            NSData.create(base64EncodedString = entry.jsonPrimitive.content, options = 0uL)
        }
        .orEmpty()
}

private const val ISSUER_METADATA_URL =
    "https://dev.issuer-backend.eudiw.dev/.well-known/openid-credential-issuer"

/**
 * The x5c of a **live** verifier request object, which is that verifier's WRP access certificate.
 *
 * Built by actually starting a transaction at the dev verifier, so the certificate is the one a wallet
 * would really be asked to trust rather than a fixture that can quietly go stale.
 */
@OptIn(ExperimentalEncodingApi::class, BetaInteropApi::class)
private suspend fun verifierRequestSignerChain(log: (String) -> Unit): List<NSData> {
    val transaction = createVerifierTransaction(log) ?: return emptyList()
    val requestUri = transaction.authorizationUri
        .substringAfter("request_uri=", "")
        .substringBefore("&")
        .urlDecoded()
    if (requestUri.isEmpty()) return emptyList()

    val client = HttpClient(Darwin.create())
    val jwt = try {
        client.get(requestUri).bodyAsText().trim()
    } finally {
        client.close()
    }
    val header = jwt.split('.').firstOrNull() ?: return emptyList()
    val decoded = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
        .decode(header)
        .decodeToString()
    return Json.parseToJsonElement(decoded).jsonObject["x5c"]
        ?.jsonArray
        ?.mapNotNull { element ->
            NSData.create(base64EncodedString = element.jsonPrimitive.content, options = 0uL)
        }
        .orEmpty()
}

/** Percent-decoding, enough for the one parameter this probe pulls out of an authorization URI. */
private fun String.urlDecoded(): String {
    if ('%' !in this) return this
    val bytes = mutableListOf<Byte>()
    var index = 0
    while (index < length) {
        val char = this[index]
        if (char == '%' && index + 2 < length) {
            bytes += substring(index + 1, index + 3).toInt(16).toByte()
            index += 3
        } else {
            bytes += char.code.toByte()
            index++
        }
    }
    return bytes.toByteArray().decodeToString()
}
