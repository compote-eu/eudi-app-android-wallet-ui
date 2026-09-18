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

import kotlinx.serialization.json.jsonObject
import kotlin.random.Random
import org.multipaz.util.toBase64Url
import eu.europa.ec.shared.wallet.trust.toTrustChain
import eu.europa.ec.shared.wallet.trust.IosEtsiTrust
import eu.europa.ec.eudi.etsi1196x2.consultation.VerificationContext
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import io.ktor.http.contentType
import io.ktor.http.ContentType
import io.ktor.client.request.setBody
import io.ktor.client.request.post
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Does the **live EU issuer's registration certificate actually verify**, end to end?
 *
 * Two links in [IosIssuerRegistrationValidator] cannot be answered by a unit test, because both are
 * about the real world rather than the code:
 *
 *  1. **is the certificate's signer trusted by the ETSI WRPRC list** we already consult? The unit
 *     tests stub that answer, so a green suite says nothing about whether the real chain lands.
 *  2. **is the status list reachable, and does it say the certificate is valid?** Both
 *     `STATUS_MISSING` and `REVOCATION_STATUS_UNKNOWN` fail closed, so a status list that cannot be
 *     read turns a correctly-registered issuer into a refusal. The certificate points at
 *     `dev.issuer.eudiw.dev`, a **different host** from the issuer itself.
 *
 * Everything else about the flow was measured with `openssl` before a line was written: the dev
 * issuer serves its metadata as a signed JWS, that document carries
 * `issuer_info[0].format == "registration_cert"`, the certificate inside is `typ: rc-wrp+jwt` / ES256
 * with a one-entry `x5c`, and its `sub` equals the metadata signer's `organizationIdentifier`.
 */
fun probeIssuerRegistration(issuerUrl: String, onResult: (String) -> Unit) {
    // ⛔ `Dispatchers.Main`, never `Default`. The callback Swift hands in is a closure from
    // `iOSApp.init()`, which is main-actor isolated: invoking it from a background queue trips
    // `dispatch_assert_queue` inside swift_task_isCurrentExecutor and the app dies with SIGTRAP before
    // printing a single line. Measured — every other probe here already uses Main for this reason.
    CoroutineScope(Dispatchers.Main).launch { runIssuerRegistrationProbe(issuerUrl, onResult) }
}

private suspend fun runIssuerRegistrationProbe(issuerUrl: String, onResult: (String) -> Unit) {
    val client = HttpClient(Darwin)
    val log = StringBuilder()
    fun say(line: String) {
        log.appendLine(line)
        onResult(line)
    }

    say("== issuer registration certificate: $issuerUrl")

    val metadataDocument = runCatching {
        client.get("$issuerUrl/.well-known/openid-credential-issuer").bodyAsText().trim()
    }.getOrElse {
        say("FAILED to fetch metadata: ${it.message}")
        return
    }

    if (metadataDocument.count { it == '.' } < 2 || metadataDocument.startsWith("{")) {
        say("metadata is NOT signed (plain JSON) -> no signer, so the binding cannot be satisfied")
        return
    }

    val payload = jwsPayload(metadataDocument)
    if (payload == null) {
        say("FAILED: metadata JWS payload could not be decoded")
        return
    }
    val signer = jwsCertificateChain(metadataDocument)?.certificates?.firstOrNull()
    say("metadata signer: ${signer?.subject?.name ?: "none"}")
    say("  organizationIdentifier: ${signer?.registrationIdentifier() ?: "absent"}")

    val certificate = issuerRegistrationCertificateIn(payload)
    if (certificate == null) {
        say("issuer publishes NO registration certificate (issuer_info absent or no registration_cert)")
        return
    }
    say("registration certificate typ: ${jwsHeader(certificate)?.get("typ")?.jsonPrimitive?.contentOrNull}")
    say("  sub: ${jwsPayload(certificate)?.get("sub")?.jsonPrimitive?.contentOrNull}")

    val offered = offeredAttestationsIn(payload)
    say("offering ${offered.size} configuration(s)")

    // ⛔ The PRODUCTION checker, not a second copy of its wiring. An earlier version of this probe
    // rebuilt the validator inline and would have kept passing while the shipped path rotted — this
    // page's own recurring failure is comparing a port to itself.
    val outcome = runCatching {
        IosIssuerRegistrationChecker(client).check(issuerUrl = issuerUrl)
    }.getOrElse {
        say("check THREW: ${it::class.simpleName}: ${it.message}")
        return
    }

    when (outcome) {
        is IssuerRegistrationOutcome.NotOffered -> say("RESULT: no certificate offered")
        is IssuerRegistrationOutcome.Failed ->
            say("RESULT: FAILED ${outcome.reason}${outcome.detail?.let { " ($it)" } ?: ""}")

        is IssuerRegistrationOutcome.Verified -> {
            say("RESULT: VERIFIED — '${outcome.registration.name}' (${outcome.registration.country})")
            say("  entitlements: ${outcome.registration.entitlements.joinToString()}")
            say("  over-provided: ${outcome.overProvided.size} of ${offered.size} offered")
            outcome.overProvided.forEach {
                say("    ${it.format} ${it.doctype ?: it.vctValues.joinToString("|")}")
            }
        }
    }
}



/**
 * Does the **live EU dev verifier's** registration certificate verify, and does its request over-ask?
 *
 * The relying-party twin of [probeIssuerRegistration], and the same two questions no unit test can
 * answer: whether a real chain lands against the ETSI WRPRC list, and whether the status list on a
 * different host can be read. It creates a real transaction so the request object is a real one —
 * `verifier_info` only appears there, never in any metadata document.
 */
fun probeRelyingPartyRegistration(onResult: (String) -> Unit) {
    CoroutineScope(Dispatchers.Main).launch { runRelyingPartyProbe(onResult) }
}

private suspend fun runRelyingPartyProbe(onResult: (String) -> Unit) {
    val client = HttpClient(Darwin)
    fun say(line: String) = onResult(line)
    say("== relying party registration certificate: $VERIFIER")

    val certificate = runCatching {
        Json.parseToJsonElement(client.get("$VERIFIER/ui/intended-uses").bodyAsText())
            .jsonObject["intended_uses"]!!.jsonArray
            .first { it.jsonObject["intended_use_id"]?.jsonPrimitive?.contentOrNull == "TEST-01" }
            .jsonObject["registration_certificate"]!!.jsonPrimitive.content
    }.getOrElse {
        say("FAILED to read the verifier's intended uses: ${it.message}")
        return
    }

    val requestUri = runCatching {
        val body = buildJsonObject {
            put("type", "vp_token")
            put("nonce", "probe-rp-" + Random.nextBytes(6).toBase64Url())
            put("registration_certificate", certificate)
            putJsonObject("dcql_query") {
                putJsonArray("credentials") {
                    add(
                        buildJsonObject {
                            put("id", "pid")
                            put("format", "mso_mdoc")
                            putJsonObject("meta") { put("doctype_value", "eu.europa.ec.eudi.pid.1") }
                            putJsonArray("claims") {
                                add(
                                    buildJsonObject {
                                        putJsonArray("path") {
                                            add("eu.europa.ec.eudi.pid.1")
                                            add("family_name")
                                        }
                                    }
                                )
                            }
                        }
                    )
                }
            }
        }
        val created = client.post("$VERIFIER/ui/presentations/v2") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(JsonObject.serializer(), body))
        }.bodyAsText()
        Json.parseToJsonElement(created).jsonObject["request_uri"]!!.jsonPrimitive.content
    }.getOrElse {
        say("FAILED to create a transaction: ${it.message}")
        return
    }

    val requestObjectJws = runCatching { client.get(requestUri).bodyAsText().trim() }
        .getOrElse {
            say("FAILED to fetch the request object: ${it.message}")
            return
        }
    val requestObject = jwsPayload(requestObjectJws)
    if (requestObject == null) {
        say("FAILED: the request object could not be decoded")
        return
    }
    val signer = jwsCertificateChain(requestObjectJws)?.certificates?.firstOrNull()
    say("request object signer: ${signer?.subject?.name ?: "none"}")
    say("  organizationIdentifier: ${signer?.registrationIdentifier() ?: "absent"}")
    say("verifier_info present: ${relyingPartyCertificateIn(requestObject) != null}")
    say("requested claims: ${requestedClaimsIn(requestObject).size}")

    val trust = IosEtsiTrust()
    val outcome = runCatching {
        IosRelyingPartyRegistrationValidator(
            isChainTrusted = { chain ->
                trust.isTrusted(
                    chain.certificates.toTrustChain(),
                    VerificationContext.WalletRelyingPartyRegistrationCertificate,
                ).also { say("  signer trusted by the WRPRC list: $it") }
            },
            checkRevocation = { reference ->
                say("  status list: ${reference.uri} idx=${reference.index}")
                registrationStatusOf(reference, client) { chain ->
                    trust.isTrusted(
                        chain.certificates.toTrustChain(),
                        VerificationContext.WalletRelyingPartyRegistrationCertificateStatus,
                    )
                }.also { say("  status -> $it") }
            },
        ).evaluate(requestObject, signer)
    }.getOrElse {
        say("evaluate THREW: ${it::class.simpleName}: ${it.message}")
        return
    }

    when (outcome) {
        is RelyingPartyRegistrationOutcome.NotOffered -> say("RESULT: no certificate offered")
        is RelyingPartyRegistrationOutcome.Failed ->
            say("RESULT: FAILED ${outcome.reason}${outcome.detail?.let { " ($it)" } ?: ""}")

        is RelyingPartyRegistrationOutcome.Verified -> {
            say("RESULT: VERIFIED — '${outcome.registration.name}' (${outcome.registration.country})")
            say("  entitlements: ${outcome.registration.entitlements.joinToString()}")
            say("  over-asked: ${outcome.overAsked.size}")
            outcome.overAsked.forEach { say("    ${it.format}:${it.path.joinToString(".")}") }
        }
    }
}

private const val VERIFIER = "https://dev.verifier-backend.eudiw.dev"
