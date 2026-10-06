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

// Which language an issuer's names are kept in. multipaz keeps ONE display entry per document and one for the
// issuer, chosen by the locales it is given, and those are the names a document is stored with — so issuance
// has to ask in the user's language, as the add-document list does. The names are read here through multipaz
// itself, behind the same compatibility engine.
package eu.europa.ec.shared.wallet.multipaz

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.multipaz.provisioning.ProvisioningMetadata
import org.multipaz.provisioning.openid4vci.OpenID4VCI
import org.multipaz.provisioning.openid4vci.OpenID4VCIClientPreferences
import kotlin.test.Test
import kotlin.test.assertEquals

class IssuerDisplayLanguageTest {

    private val issuer = IosVciIssuer(
        issuerUrl = "https://issuer.test",
        clientId = "eudiw-abca",
        redirectUri = "eu.europa.ec.euidi://authorization",
        order = 0,
    )

    /** Display entries as the EU issuers publish them: inside `credential_metadata`, English first. */
    private fun metadata(documentDisplay: String, issuerDisplay: String) = """
        {"credential_issuer":"${issuer.issuerUrl}",
         "credential_endpoint":"${issuer.issuerUrl}/credential",
         "display":$issuerDisplay,
         "credential_configurations_supported":{
           "pid_mdoc":{"format":"mso_mdoc","doctype":"eu.europa.ec.eudi.pid.1","scope":"pid",
             "credential_metadata":{"display":$documentDisplay}}}}
    """.trimIndent()

    private val englishAndSlovak = metadata(
        documentDisplay = """[{"name":"PID","locale":"en"},{"name":"Občiansky preukaz","locale":"sk-SK"}]""",
        issuerDisplay = """[{"name":"Digital Credentials Issuer","locale":"en"},{"name":"Vydavateľ","locale":"sk"}]""",
    )

    private suspend fun namesKept(
        preferences: OpenID4VCIClientPreferences,
        metadata: String = englishAndSlovak,
    ): Pair<String, String> {
        val engine = MockEngine { request ->
            if (request.url.toString() == "${issuer.issuerUrl}/.well-known/openid-credential-issuer") {
                respond(metadata, headers = headersOf("Content-Type", "application/json"))
            } else {
                respondError(HttpStatusCode.NotFound)
            }
        }
        val kept: ProvisioningMetadata = OpenID4VCI.getMetadata(
            issuerUrl = issuer.issuerUrl,
            httpClient = openID4VciHttpClient(engine, issuerTrust = null),
            clientPreferences = preferences,
        )
        return kept.credentials.getValue("pid_mdoc").display.text to kept.display.text
    }

    // ---- the languages asked for ----------------------------------------------------------------

    @Test
    fun a_user_reading_another_language_asks_for_it_first_and_english_second() {
        assertEquals(listOf("sk", "en"), displayLocales("sk"))
    }

    @Test
    fun an_english_user_asks_for_english_once() {
        assertEquals(listOf("en"), displayLocales("en"))
    }

    @Test
    fun a_regional_tag_is_asked_for_as_its_language() {
        // The engine reduces the issuer's locales to bare languages before multipaz matches them exactly.
        assertEquals(listOf("sk", "en"), displayLocales("sk-SK"))
        assertEquals(listOf("en"), displayLocales("en-GB"))
    }

    @Test
    fun a_tag_with_no_language_in_it_asks_for_english() {
        assertEquals(listOf("en"), displayLocales(""))
    }

    // ---- the names kept, through multipaz ---------------------------------------------------------

    @Test
    fun a_slovak_user_gets_the_slovak_names() = runTest {
        assertEquals("Občiansky preukaz" to "Vydavateľ", namesKept(issuer.clientPreferences("sk")))
    }

    @Test
    fun an_english_user_gets_the_english_names() = runTest {
        assertEquals("PID" to "Digital Credentials Issuer", namesKept(issuer.clientPreferences("en")))
    }

    @Test
    fun a_language_the_issuer_does_not_publish_gets_english() = runTest {
        assertEquals("PID" to "Digital Credentials Issuer", namesKept(issuer.clientPreferences("fr")))
    }

    @Test
    fun an_issuer_publishing_neither_language_still_gives_its_first_names() = runTest {
        val germanOnly = metadata(
            documentDisplay = """[{"name":"Personalausweis","locale":"de"},{"name":"Carte","locale":"fr"}]""",
            issuerDisplay = """[{"name":"Aussteller","locale":"de"}]""",
        )

        assertEquals("Personalausweis" to "Aussteller", namesKept(issuer.clientPreferences("sk"), germanOnly))
    }

    // ---- issuance asks in the user's language ------------------------------------------------------

    @Test
    fun issuance_asks_for_the_names_in_the_users_language() = runTest {
        var language = "sk"
        val issuing = IosCredentialIssuer(
            walletEngine = IosWalletEngine(),
            issuers = listOf(issuer),
            userLanguage = { language },
        )

        assertEquals("Občiansky preukaz" to "Vydavateľ", namesKept(issuing.clientPreferencesFor(issuer)))

        // Asked per issuance, not fixed when the wallet started: the user can change it in between.
        language = "en"
        assertEquals(listOf("en"), issuing.clientPreferencesFor(issuer).locales)
    }
}
