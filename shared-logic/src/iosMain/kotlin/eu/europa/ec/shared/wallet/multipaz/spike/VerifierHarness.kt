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

// The *verifier's* half of a remote presentation, so a run on this machine has something real to talk to.
//
// A test harness rather than app code, and it is here rather than in a test source set for the same
// reason the issuance harness is: what needs exercising is the running app, and `simctl` cannot tap. The
// console probe plays the user; this plays the verifier's web front-end, driving
// `dev.verifier-backend.eudiw.dev`'s REST API exactly as the hosted wizard at dev.verifier.eudiw.dev does
// — create a transaction, hand the wallet the link a user would tap, then read back the verifier's own
// account of what it received.
//
// The verdict is deliberately the verifier's rather than ours. This is the one part of the wallet whose
// correctness cannot be judged from inside: multipaz implements OpenID4VP 1.0, and whether that is what
// the EUDI verifier wants is exactly the question. `/ui/presentations/{id}/events` answers it in the
// verifier's own words.
package eu.europa.ec.shared.wallet.multipaz.spike

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.multipaz.util.toBase64Url
import kotlin.random.Random

/** The EUDI dev verifier's backend — the same one the hosted wizard at dev.verifier.eudiw.dev drives. */
private const val VERIFIER = "https://dev.verifier-backend.eudiw.dev"

/** The mdoc PID claims to ask for. Three a real PID always carries. */
private val REQUESTED = listOf("family_name", "given_name", "birth_date")

/**
 * A presentation the verifier is waiting on: [authorizationUri] is what a user would tap, and
 * [transactionId] is how the verifier's verdict is read back afterwards.
 */
data class VerifierTransaction(
    val authorizationUri: String,
    val transactionId: String,
    val clientId: String,
)

/**
 * Asks the EUDI dev verifier to start a presentation, and returns the link the wallet would be opened
 * with. Null when the verifier will not start one, which is worth reporting rather than crashing — it is
 * a public dev service and this is a probe.
 */
suspend fun createVerifierTransaction(log: (String) -> Unit): VerifierTransaction? {
    val http = HttpClient(Darwin)
    try {
        val nonce = "ios-probe-" + Random.nextBytes(8).toBase64Url()

        // The registration certificate. The backend refuses to create a transaction without one
        // ("MissingRegistrationCertificate") and publishes usable ones itself; TEST-01 is the entry
        // that covers mdoc PID.
        val certificate = http.get("$VERIFIER/ui/intended-uses").bodyAsText()
            .let { Json.parseToJsonElement(it).jsonObject["intended_uses"]!!.jsonArray }
            .firstOrNull { it.jsonObject["intended_use_id"]?.jsonPrimitive?.content == "TEST-01" }
            ?.jsonObject?.get("registration_certificate")?.jsonPrimitive?.content
            ?: run {
                log("no TEST-01 registration certificate published by the verifier")
                return null
            }

        val initialized = http.post("$VERIFIER/ui/presentations/v2") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(JsonObject.serializer(), initRequest(nonce, certificate)))
        }.bodyAsText().let { Json.parseToJsonElement(it).jsonObject }

        val transactionId = initialized["transaction_id"]?.jsonPrimitive?.content
        val clientId = initialized["client_id"]?.jsonPrimitive?.content
        val requestUri = initialized["request_uri"]?.jsonPrimitive?.content
        if (transactionId == null || clientId == null || requestUri == null) {
            log("the verifier would not start a transaction: $initialized")
            return null
        }

        // The verifier hands back its own link under `haip-vp://`; this rebuilds it under `openid4vp://`
        // because the two differ in nothing else and that is the scheme a user is likeliest to arrive
        // on. Both are registered — see `IosDeepLinks.PRESENTATION_SCHEMES`.
        return VerifierTransaction(
            authorizationUri = "openid4vp://?client_id=${clientId.urlEncoded()}" +
                    "&request_uri=${requestUri.urlEncoded()}&request_uri_method=get",
            transactionId = transactionId,
            clientId = clientId,
        )
    } catch (t: Throwable) {
        log("could not reach the verifier: ${t::class.simpleName}: ${t.message}")
        return null
    } finally {
        http.close()
    }
}

/**
 * The verifier's own account of the exchange, one line per event.
 *
 * This is the only trustworthy verdict available: a wallet cannot tell whether the response it built was
 * acceptable, and the verifier's log says so explicitly — including *why* it refused, which the wallet
 * never sees (multipaz's `uriSchemePresentment` checks the status code and discards the body).
 */
suspend fun verifierEvents(transactionId: String): List<String> {
    val http = HttpClient(Darwin)
    return try {
        http.get("$VERIFIER/ui/presentations/$transactionId/events").bodyAsText()
            .let { Json.parseToJsonElement(it).jsonObject["events"]?.jsonArray.orEmpty() }
            .map { event ->
                val fields = event.jsonObject
                "[${fields["actor"]?.jsonPrimitive?.content}] " +
                        "${fields["event"]?.jsonPrimitive?.content}${fields.errorSuffix()}"
            }
    } catch (t: Throwable) {
        listOf("could not read the verifier's events: ${t::class.simpleName}: ${t.message}")
    } finally {
        http.close()
    }
}

/** The transaction the verifier's front-end would create for "PID, three attributes, mdoc". */
private fun initRequest(nonce: String, registrationCertificate: String) = buildJsonObject {
    put("type", "vp_token")
    put("nonce", nonce)
    put("registration_certificate", registrationCertificate)
    putJsonObject("dcql_query") {
        putJsonArray("credentials") {
            add(
                buildJsonObject {
                    put("id", "pid")
                    put("format", "mso_mdoc")
                    putJsonObject("meta") { put("doctype_value", MDOC_PID_DOC_TYPE) }
                    putJsonArray("claims") {
                        REQUESTED.forEach { element ->
                            add(
                                buildJsonObject {
                                    putJsonArray("path") {
                                        add(MDOC_PID_DOC_TYPE)
                                        add(element)
                                    }
                                }
                            )
                        }
                    }
                }
            )
        }
    }
}

/** Verifier events carry their failure in different fields depending on the step; show any of them. */
private fun JsonObject.errorSuffix(): String =
    listOf("error", "cause", "description", "reason")
        .mapNotNull { key -> this[key]?.let { "$key=$it" } }
        .let { if (it.isEmpty()) "" else " — " + it.joinToString(", ") }

/** Percent-encodes a query-parameter value. The `request_uri` travels inside one. */
private fun String.urlEncoded(): String = buildString {
    this@urlEncoded.encodeToByteArray().forEach { byte ->
        val char = byte.toInt().toChar()
        if (char.isLetterOrDigit() || char in "-_.~") append(char)
        else append('%').append(byte.toInt().and(0xFF).toString(16).uppercase().padStart(2, '0'))
    }
}
