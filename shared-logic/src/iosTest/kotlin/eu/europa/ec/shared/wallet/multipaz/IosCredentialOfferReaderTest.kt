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

// Reading a credential offer. Two things make this worth pinning: the offer's parameters are parsed here
// rather than by multipaz (see the reader for why), and the transaction-code spec it reports is what sizes
// the PIN screen — a wrong length there is a screen the user cannot complete.
package eu.europa.ec.shared.wallet.multipaz

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosCredentialOfferReaderTest {

    private val issuerUrl = "https://issuer.test"

    private val issuerMetadata = """
        {"credential_issuer":"$issuerUrl",
         "credential_endpoint":"$issuerUrl/credential",
         "batch_credential_issuance":{"batch_size":10},
         "credential_configurations_supported":{
           "pid_mdoc":{"format":"mso_mdoc","doctype":"eu.europa.ec.eudi.pid.1","scope":"pid",
             "display":[{"name":"PID (MSO MDoc)","locale":"en"}]},
           "loyalty_mdoc":{"format":"mso_mdoc","doctype":"org.example.loyalty","scope":"loyalty",
             "display":[{"name":"Loyalty card","locale":"en"}]}}}
    """.trimIndent()

    private fun offerLink(
        configurationIds: String = """["pid_mdoc"]""",
        grants: String? = null,
        issuer: String = issuerUrl,
    ): String {
        val offer = buildString {
            append("""{"credential_issuer":"$issuer","credential_configuration_ids":$configurationIds""")
            grants?.let { append(""","grants":$it""") }
            append("}")
        }
        return "openid-credential-offer://?credential_offer=" + offer.encodeUrlParameter()
    }

    /** Serves the issuer metadata, and an offer document at `/offer` for the by-reference case. */
    private fun engine(offerDocument: String? = null) = MockEngine { request ->
        when {
            request.url.toString() == "$issuerUrl/.well-known/openid-credential-issuer" ->
                respond(issuerMetadata, headers = headersOf("Content-Type", "application/json"))

            request.url.toString() == "$issuerUrl/offer" && offerDocument != null ->
                respond(offerDocument, headers = headersOf("Content-Type", "application/json"))

            else -> respondError(HttpStatusCode.NotFound)
        }
    }

    private fun reader(engine: MockEngine) = IosCredentialOfferReader(
        engine = engine,
        issuers = listOf(
            IosVciIssuer(
                issuerUrl = issuerUrl,
                clientId = "eudiw-abca",
                redirectUri = "eu.europa.ec.euidi://authorization",
                order = 0,
            )
        ),
    )

    @Test
    fun an_offer_carrying_its_own_document_resolves_to_the_issuers_names() = runTest {
        val resolution = reader(engine()).resolve(offerLink(), locale = "en")

        val resolved = assertIs<IosOfferResolution.Resolved>(resolution)
        assertEquals(listOf("PID (MSO MDoc)"), resolved.documentNames)
        assertEquals(issuerUrl, resolved.offer.issuerUrl)
        assertEquals(listOf("pid_mdoc"), resolved.offer.configurationIds)
        // No grants at all: nothing to enter, so the offer-code screen must not appear.
        assertNull(resolved.offer.txCodeLength)
    }

    @Test
    fun an_offer_that_points_at_a_document_is_fetched() = runTest {
        val byReference = "openid-credential-offer://?credential_offer_uri=" +
                "$issuerUrl/offer".encodeUrlParameter()
        val document = """{"credential_issuer":"$issuerUrl","credential_configuration_ids":["loyalty_mdoc"]}"""

        val resolution = reader(engine(offerDocument = document)).resolve(byReference, locale = "en")

        assertEquals(
            listOf("Loyalty card"),
            assertIs<IosOfferResolution.Resolved>(resolution).documentNames,
        )
    }

    @Test
    fun a_transaction_code_is_reported_with_its_length_and_kind() = runTest {
        val grants = """
            {"urn:ietf:params:oauth:grant-type:pre-authorized_code":
              {"pre-authorized_code":"abc","tx_code":{"length":5,"input_mode":"numeric"}}}
        """.trimIndent()

        val resolution = reader(engine()).resolve(offerLink(grants = grants), locale = "en")

        val offer = assertIs<IosOfferResolution.Resolved>(resolution).offer
        assertEquals(5, offer.txCodeLength)
        assertTrue(offer.txCodeIsNumeric)
    }

    @Test
    fun a_free_text_transaction_code_is_reported_as_such_rather_than_rejected_here() = runTest {
        val grants = """
            {"urn:ietf:params:oauth:grant-type:pre-authorized_code":
              {"pre-authorized_code":"abc","tx_code":{"length":5,"input_mode":"text"}}}
        """.trimIndent()

        val resolution = reader(engine()).resolve(offerLink(grants = grants), locale = "en")

        // Whether this wallet can collect free text is a decision for the shared interactor, which turns
        // it into "invalid code format". The reader only reports what the issuer asked for.
        assertFalse(assertIs<IosOfferResolution.Resolved>(resolution).offer.txCodeIsNumeric)
    }

    @Test
    fun a_missing_input_mode_defaults_to_numeric_as_the_specification_says() = runTest {
        val grants = """
            {"urn:ietf:params:oauth:grant-type:pre-authorized_code":
              {"pre-authorized_code":"abc","tx_code":{"length":4}}}
        """.trimIndent()

        val resolution = reader(engine()).resolve(offerLink(grants = grants), locale = "en")

        assertTrue(assertIs<IosOfferResolution.Resolved>(resolution).offer.txCodeIsNumeric)
    }

    @Test
    fun whether_the_offer_contains_a_pid_is_decided_from_the_format_the_issuer_declares() = runTest {
        val withPid = reader(engine()).resolve(offerLink(), locale = "en")
        val withoutPid = reader(engine()).resolve(
            offerLink(configurationIds = """["loyalty_mdoc"]"""),
            locale = "en",
        )

        // The wallet's "PID first" rule reads this, so a mistake here would refuse a legitimate offer.
        assertTrue(assertIs<IosOfferResolution.Resolved>(withPid).containsPid)
        assertFalse(assertIs<IosOfferResolution.Resolved>(withoutPid).containsPid)
    }

    @Test
    fun an_offer_naming_a_document_the_issuer_does_not_advertise_is_refused() = runTest {
        val resolution = reader(engine()).resolve(
            offerLink(configurationIds = """["something_else"]"""),
            locale = "en",
        )

        // Accepting it would mean asking the user to agree to something the wallet cannot describe.
        val failure = assertIs<IosOfferResolution.Failure>(resolution)
        assertTrue("something_else" in failure.message)
    }

    @Test
    fun a_link_with_neither_an_offer_nor_a_reference_is_refused() = runTest {
        val resolution = reader(engine()).resolve("openid-credential-offer://?x=1", locale = "en")

        assertIs<IosOfferResolution.Failure>(resolution)
    }

    @Test
    fun an_offer_naming_no_document_is_refused() = runTest {
        val resolution = reader(engine()).resolve(offerLink(configurationIds = "[]"), locale = "en")

        assertIs<IosOfferResolution.Failure>(resolution)
    }

    @Test
    fun an_issuer_that_cannot_be_reached_is_a_failure_not_an_empty_offer() = runTest {
        val unreachable = MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }

        val resolution = reader(unreachable).resolve(offerLink(), locale = "en")

        assertIs<IosOfferResolution.Failure>(resolution)
    }
}

/** Percent-encodes a query-parameter value; the offer travels inside one. */
private fun String.encodeUrlParameter(): String = buildString {
    this@encodeUrlParameter.encodeToByteArray().forEach { byte ->
        val char = byte.toInt().toChar()
        if (char.isLetterOrDigit() || char in "-_.~") {
            append(char)
        } else {
            append('%').append(byte.toInt().and(0xFF).toString(16).uppercase().padStart(2, '0'))
        }
    }
}
