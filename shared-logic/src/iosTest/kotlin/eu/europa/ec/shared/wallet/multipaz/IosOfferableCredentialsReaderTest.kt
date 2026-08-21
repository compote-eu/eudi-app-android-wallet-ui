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

import eu.europa.ec.shared.wallet.config.iosWalletConfig
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The issuer catalogue read, over a mock issuer rather than the real ones.
 *
 * Worth pinning because this is the only thing standing between iOS and an add-document list, and because
 * two of its behaviours are choices rather than mechanics: one unreachable issuer must not hide another's
 * documents, and a read that reaches nobody must still fail.
 */
class IosOfferableCredentialsReaderTest {

    private val firstIssuer = IosVciIssuer(
        issuerUrl = "https://issuer-one.test",
        clientId = "eudiw-abca",
        redirectUri = "eu.europa.ec.euidi://authorization",
        order = 0,
    )

    private val secondIssuer = IosVciIssuer(
        issuerUrl = "https://issuer-two.test",
        clientId = "eudiw-abca",
        redirectUri = "eu.europa.ec.euidi://authorization",
        order = 1,
    )

    private fun metadataFor(
        issuerUrl: String,
        configurations: String,
    ) = """
        {"credential_issuer":"$issuerUrl",
         "credential_endpoint":"$issuerUrl/credential",
         "batch_credential_issuance":{"batch_size":10},
         "credential_configurations_supported":{$configurations}}
    """.trimIndent()

    private val pidMdoc = """
        "pid_mdoc":{"format":"mso_mdoc","doctype":"eu.europa.ec.eudi.pid.1","scope":"pid",
          "display":[{"name":"PID (MSO MDoc)","locale":"en"},{"name":"PID (mDoc)","locale":"sk"}]}
    """.trimIndent()

    private val mdlMdoc = """
        "mdl_mdoc":{"format":"mso_mdoc","doctype":"org.iso.18013.5.1.mDL","scope":"mdl",
          "display":[{"name":"Mobile Driving Licence","locale":"en"}]}
    """.trimIndent()

    private val pidSdJwt = """
        "pid_sd_jwt":{"format":"dc+sd-jwt","vct":"urn:eudi:pid:1","scope":"pid_sd",
          "display":[{"name":"PID (SD-JWT VC)","locale":"en"}]}
    """.trimIndent()

    /** Serves each issuer's metadata; anything else (a logo, say) fails, as the real hosts do. */
    private fun engineServing(vararg metadata: Pair<String, String>) = MockEngine { request ->
        val body = metadata.firstOrNull { (issuerUrl, _) ->
            request.url.toString() == "$issuerUrl/.well-known/openid-credential-issuer"
        }?.second

        if (body == null) {
            respondError(HttpStatusCode.NotFound)
        } else {
            respond(body, headers = headersOf("Content-Type", "application/json"))
        }
    }

    @Test
    fun every_configuration_an_issuer_offers_becomes_an_entry() = runTest {
        val reader = IosOfferableCredentialsReader(
            issuers = listOf(firstIssuer),
            engine = engineServing(
                firstIssuer.issuerUrl to metadataFor(
                    firstIssuer.issuerUrl,
                    "$pidMdoc,$mdlMdoc,$pidSdJwt",
                ),
            ),
        )

        val result = assertIs<OfferableCredentialsResult.Success>(reader.read(locale = "en"))

        assertEquals(3, result.credentials.size)
        val pid = result.credentials.single { it.configurationId == "pid_mdoc" }
        assertEquals("PID (MSO MDoc)", pid.name)
        assertEquals("eu.europa.ec.eudi.pid.1", pid.formatType)
        assertEquals(firstIssuer.issuerUrl, pid.issuerUrl)
        assertEquals(0, pid.issuerOrder)
        // The SD-JWT format carries its type where mdoc carries a doctype; both land in `formatType`,
        // because that is what decides whether a configuration is a PID.
        assertEquals(
            "urn:eudi:pid:1",
            result.credentials.single { it.configurationId == "pid_sd_jwt" }.formatType,
        )
    }

    @Test
    fun each_issuer_keeps_its_own_url_and_order() = runTest {
        val reader = IosOfferableCredentialsReader(
            issuers = listOf(firstIssuer, secondIssuer),
            engine = engineServing(
                firstIssuer.issuerUrl to metadataFor(firstIssuer.issuerUrl, pidMdoc),
                secondIssuer.issuerUrl to metadataFor(secondIssuer.issuerUrl, mdlMdoc),
            ),
        )

        val result = assertIs<OfferableCredentialsResult.Success>(reader.read(locale = "en"))

        // The add-document screen groups by issuer and sorts by this order, so both must survive the read.
        assertEquals(
            listOf(firstIssuer.issuerUrl to 0, secondIssuer.issuerUrl to 1),
            result.credentials.map { it.issuerUrl to it.issuerOrder },
        )
    }

    @Test
    fun an_unreachable_issuer_does_not_hide_the_others() = runTest {
        val reader = IosOfferableCredentialsReader(
            issuers = listOf(firstIssuer, secondIssuer),
            // Only the second answers.
            engine = engineServing(
                secondIssuer.issuerUrl to metadataFor(secondIssuer.issuerUrl, mdlMdoc),
            ),
        )

        val result = assertIs<OfferableCredentialsResult.Success>(reader.read(locale = "en"))

        // Deliberately unlike Android, which fails the whole list. See the note in the reader.
        assertEquals(listOf("mdl_mdoc"), result.credentials.map { it.configurationId })
    }

    @Test
    fun a_read_that_reaches_nobody_fails_with_what_went_wrong() = runTest {
        val reader = IosOfferableCredentialsReader(
            issuers = listOf(firstIssuer),
            engine = engineServing(),
        )

        val result = assertIs<OfferableCredentialsResult.Failure>(reader.read(locale = "en"))

        // The message reaches the screen, so it has to say something about the issuer rather than be blank.
        assertTrue(result.message.isNotBlank(), "a failed read must explain itself")
        assertTrue(
            firstIssuer.issuerUrl in result.message,
            "expected the issuer in the message, was: ${result.message}",
        )
    }

    @Test
    fun no_configured_issuers_is_a_failure_rather_than_an_empty_list() = runTest {
        val reader = IosOfferableCredentialsReader(issuers = emptyList(), engine = engineServing())

        // An empty success would render as "no documents available", which reads as the issuers having
        // nothing to offer rather than the wallet having nobody to ask.
        assertIs<OfferableCredentialsResult.Failure>(reader.read(locale = "en"))
    }

    @Test
    fun the_users_locale_picks_which_display_name_is_used() = runTest {
        val reader = IosOfferableCredentialsReader(
            issuers = listOf(firstIssuer),
            engine = engineServing(
                firstIssuer.issuerUrl to metadataFor(firstIssuer.issuerUrl, pidMdoc),
            ),
        )

        val result = assertIs<OfferableCredentialsResult.Success>(reader.read(locale = "sk"))

        assertEquals("PID (mDoc)", result.credentials.single().name)
    }

    @Test
    fun the_real_catalogue_names_the_configured_issuers_with_the_registered_client() {
        // The URLs are per build flavour now, so this asserts the catalogue *mirrors* the flavour
        // rather than pinning dev's two — the values themselves are pinned in `IosWalletConfigTest`,
        // which the verify set runs under both flavours.
        assertEquals(
            iosWalletConfig.issuerUrls,
            IosIssuerCatalog.issuers.map { it.issuerUrl },
        )
        assertEquals(
            List(iosWalletConfig.issuerUrls.size) { it },
            IosIssuerCatalog.issuers.map { it.order },
        )
        assertTrue(IosIssuerCatalog.issuers.all { it.clientId == "eudiw-abca" })
        // The redirect the issuers registered *and* the app delegate filters on — one constant, so the
        // two cannot drift.
        assertTrue(
            IosIssuerCatalog.issuers.all {
                it.redirectUri == IosAuthorizationRedirects.REDIRECT_PREFIX
            }
        )
    }
}
