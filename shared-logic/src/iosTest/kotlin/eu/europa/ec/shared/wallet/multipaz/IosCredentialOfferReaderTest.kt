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

import eu.europa.ec.shared.wallet.trust.TrustVerdict
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
    private fun engine(offerDocument: String? = null, metadata: String = issuerMetadata) = MockEngine { request ->
        when {
            request.url.toString() == "$issuerUrl/.well-known/openid-credential-issuer" ->
                respond(metadata, headers = headersOf("Content-Type", "application/json"))

            request.url.toString() == "$issuerUrl/offer" && offerDocument != null ->
                respond(offerDocument, headers = headersOf("Content-Type", "application/json"))

            else -> respondError(HttpStatusCode.NotFound)
        }
    }

    private val catalogue = listOf(
        IosVciIssuer(
            issuerUrl = issuerUrl,
            clientId = "eudiw-abca",
            redirectUri = "eu.europa.ec.euidi://authorization",
            order = 0,
        )
    )

    private fun reader(engine: MockEngine) = IosCredentialOfferReader(engine = engine, issuers = catalogue)

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

    // ---- names, as Android's offer screen shows them ------------------------------------------------
    // multipaz names an issuer or a configuration with no `display` "Untitled"; Android falls back instead,
    // to the host of the issuer's URL and to the doctype or vct.

    @Test
    fun an_issuer_that_published_no_name_is_shown_by_its_host() = runTest {
        // The fixture's issuer publishes no `display` of its own.
        val resolution = reader(engine()).resolve(offerLink(), locale = "en")

        assertEquals("issuer.test", assertIs<IosOfferResolution.Resolved>(resolution).issuerName)
    }

    @Test
    fun an_issuers_name_is_shown_in_the_users_language_else_in_its_first_language() = runTest {
        val named = """
            {"credential_issuer":"$issuerUrl",
             "credential_endpoint":"$issuerUrl/credential",
             "display":[{"name":"Vydavateľ digitálnych dokladov","locale":"sk"},
                        {"name":"Digital Credentials Issuer","locale":"en"}],
             "credential_configurations_supported":{
               "pid_mdoc":{"format":"mso_mdoc","doctype":"eu.europa.ec.eudi.pid.1","scope":"pid",
                 "display":[{"name":"PID (MSO MDoc)","locale":"en"}]}}}
        """.trimIndent()

        suspend fun issuerNameFor(locale: String) =
            assertIs<IosOfferResolution.Resolved>(reader(engine(metadata = named)).resolve(offerLink(), locale))
                .issuerName

        assertEquals("Digital Credentials Issuer", issuerNameFor("en"))
        // A language the issuer did not publish: the first entry, as Android's `getLocalizedValue` falls back —
        // not multipaz's pick, which would be English.
        assertEquals("Vydavateľ digitálnych dokladov", issuerNameFor("hu"))
    }

    @Test
    fun a_configuration_that_published_no_name_is_shown_by_its_doctype() = runTest {
        val unnamed = """
            {"credential_issuer":"$issuerUrl",
             "credential_endpoint":"$issuerUrl/credential",
             "credential_configurations_supported":{
               "loyalty_mdoc":{"format":"mso_mdoc","doctype":"org.example.loyalty","scope":"loyalty"}}}
        """.trimIndent()

        val resolution = reader(engine(metadata = unnamed))
            .resolve(offerLink(configurationIds = """["loyalty_mdoc"]"""), locale = "en")

        assertEquals(listOf("org.example.loyalty"), assertIs<IosOfferResolution.Resolved>(resolution).documentNames)
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

    // ---- display names in the user's language, whatever region the issuer writes ---------------

    @Test
    fun a_display_name_tagged_with_a_region_is_shown_in_the_users_language() = runTest {
        // multipaz matches display locales exactly, and the wallet asks in the user's bare language: an
        // issuer writing `sk-SK` was shown in its first entry, English, to a Slovak user.
        val regional = """
            {"credential_issuer":"$issuerUrl","credential_endpoint":"$issuerUrl/credential",
             "credential_configurations_supported":{
               "pid_mdoc":{"format":"mso_mdoc","doctype":"eu.europa.ec.eudi.pid.1","scope":"pid",
                 "display":[{"name":"PID","locale":"en-US"},{"name":"Občiansky preukaz","locale":"sk-SK"}]}}}
        """.trimIndent()
        val engine = MockEngine { request ->
            if (request.url.toString() == "$issuerUrl/.well-known/openid-credential-issuer") {
                respond(regional, headers = headersOf("Content-Type", "application/json"))
            } else {
                respondError(HttpStatusCode.NotFound)
            }
        }

        val resolution = reader(engine).resolve(offerLink(), locale = "sk")

        assertEquals(listOf("Občiansky preukaz"), assertIs<IosOfferResolution.Resolved>(resolution, "$resolution").documentNames)
    }

    @Test
    fun metadata_with_bare_languages_is_unchanged() = runTest {
        // The same names as before for every issuer seen so far, which all write bare codes.
        val resolution = reader(engine()).resolve(offerLink(), locale = "sk")

        assertEquals(listOf("PID (MSO MDoc)"), assertIs<IosOfferResolution.Resolved>(resolution).documentNames)
    }

    // ---- an issuer the trust lists do not vouch for --------------------------------------------

    /** Serves the issuer metadata signed by [signer], as an issuer that signs it does. */
    private suspend fun signedMetadataEngine(signer: TestSigner): MockEngine {
        val jwt = testSignedJwt(issuerMetadata, signer)
        return MockEngine { request ->
            if (request.url.toString() == "$issuerUrl/.well-known/openid-credential-issuer") {
                respond(jwt, headers = headersOf("Content-Type", "application/jwt"))
            } else {
                respondError(HttpStatusCode.NotFound)
            }
        }
    }

    private fun reader(engine: MockEngine, verdict: TrustVerdict) = IosCredentialOfferReader(
        engine = engine,
        issuers = catalogue,
        issuerTrust = { _, _ -> verdict },
    )

    @Test
    fun an_issuer_whose_signed_metadata_is_refused_is_reported_as_not_trusted() = runTest {
        // Android shows its "issuer not trusted" sheet for this refusal when it resolves the offer; a
        // generic failure would show the generic error screen instead.
        val resolution = reader(signedMetadataEngine(testSigner()), TrustVerdict.NOT_TRUSTED)
            .resolve(offerLink(), locale = "en")

        assertIs<IosOfferResolution.IssuerNotTrusted>(resolution, "$resolution")
    }

    @Test
    fun an_issuer_whose_signed_metadata_is_trusted_resolves_as_before() = runTest {
        val resolution = reader(signedMetadataEngine(testSigner()), TrustVerdict.TRUSTED)
            .resolve(offerLink(), locale = "en")

        assertEquals(listOf("PID (MSO MDoc)"), assertIs<IosOfferResolution.Resolved>(resolution, "$resolution").documentNames)
    }

    // ---- the grant facts that decide how an offer is issued -----------------------------------

    @Test
    fun an_offer_with_no_grants_is_neither_pre_authorized_nor_stateful() = runTest {
        val resolution = reader(engine()).resolve(offerLink(), locale = "en")

        val offer = assertIs<IosOfferResolution.Resolved>(resolution).offer
        // Both false is what lets such an offer be issued one configuration at a time.
        assertFalse(offer.isPreAuthorized)
        assertNull(offer.issuerState)
    }

    @Test
    fun a_pre_authorized_grant_is_reported_even_without_a_transaction_code() = runTest {
        // The tx code is optional; the grant is what decides, and reading only the code would have
        // sent a pre-authorized offer down the per-configuration path.
        val grants =
            """{"urn:ietf:params:oauth:grant-type:pre-authorized_code":{"pre-authorized_code":"abc"}}"""

        val resolution = reader(engine()).resolve(offerLink(grants = grants), locale = "en")

        val offer = assertIs<IosOfferResolution.Resolved>(resolution).offer
        assertTrue(offer.isPreAuthorized)
        assertNull(offer.txCodeLength)
    }

    @Test
    fun issuer_state_is_carried_off_the_authorization_code_grant() = runTest {
        val grants = """{"authorization_code":{"issuer_state":"state-from-issuer"}}"""

        val resolution = reader(engine()).resolve(offerLink(grants = grants), locale = "en")

        val offer = assertIs<IosOfferResolution.Resolved>(resolution).offer
        assertEquals("state-from-issuer", offer.issuerState)
        assertFalse(offer.isPreAuthorized)
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
