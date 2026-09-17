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

