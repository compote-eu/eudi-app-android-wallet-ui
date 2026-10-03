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

package eu.europa.ec.shared.wallet.trustmark

import eu.europa.ec.corelogic.model.TrustMarkDomain
import eu.europa.ec.corelogic.model.TrustMarkInformationDomain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * iOS's stand-in for wallet-core's Trust Mark fetch: one GET of the configured resource, decoded as
 * wallet-core decodes it — strictly, and without looking at the status — so anything but the published
 * shape is a failure the screen reports, never a half-filled Trust Mark.
 */
class IosTrustMarkControllerTest {

    private val information = TrustMarkInformationDomain(
        resourceUrl = "https://example.com/resources/TrustMarkResource.json",
        certifiedWalletsUrl = "https://eidas.ec.europa.eu/efda/wallet/certified",
        walletSolutionUrl = "https://eidas.ec.europa.eu/efda/wallet/certified?id=WALLET_SOLUTION_ID",
    )

    private val resource = """
        {
          "image": { "name": "eu-logo.svg", "url": "https://example.com/assets/eu-logo.svg" },
          "text": {
            "name": "trust-mark-text",
            "localisations": { "en": "Certified wallet.", "fr": "Portefeuille certifié." }
          }
        }
    """.trimIndent()

    private val requested = mutableListOf<String>()

    private fun controller(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        language: String = "fr",
    ) = IosTrustMarkController(
        information = information,
        userLanguageTag = { language },
        httpClient = {
            HttpClient(
                MockEngine { request ->
                    requested += request.url.toString()
                    respond(body, status)
                }
            )
        },
    )

    @Test
    fun the_published_resource_becomes_a_trust_mark_in_the_users_language() = runTest {
        val trustMark = controller(resource).getTrustMark().getOrThrow()

        assertEquals(
            TrustMarkDomain(
                resourceUrl = information.resourceUrl,
                imageName = "eu-logo.svg",
                imageUrl = "https://example.com/assets/eu-logo.svg",
                localisedText = "Portefeuille certifié.",
                certifiedWalletsUrl = information.certifiedWalletsUrl,
                walletSolutionUrl = information.walletSolutionUrl,
            ),
            trustMark,
        )
        assertEquals(listOf(information.resourceUrl), requested)
    }

    @Test
    fun a_language_without_a_translation_falls_back_to_the_first() = runTest {
        val trustMark = controller(resource, language = "sk").getTrustMark().getOrThrow()

        assertEquals("Certified wallet.", trustMark.localisedText)
    }

    @Test
    fun an_error_page_is_a_failure() = runTest {
        val result = controller("<html>Not Found</html>", HttpStatusCode.NotFound).getTrustMark()

        assertTrue(result.isFailure)
    }

    @Test
    fun an_unexpected_field_is_a_failure_as_in_wallet_core() = runTest {
        val extended = resource.replaceFirst("\"image\"", "\"badge\": true, \"image\"")

        val result = controller(extended).getTrustMark()

        assertTrue(result.isFailure)
    }
}
