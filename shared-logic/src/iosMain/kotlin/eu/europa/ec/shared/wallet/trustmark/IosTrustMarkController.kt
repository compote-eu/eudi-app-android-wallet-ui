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

// iOS's Trust Mark: the EC-hosted resource fetched as wallet-core 0.31.0's `TrustMarkManagerImpl` fetches it
// on Android — one GET of the resource URL, the body decoded with a plain (strict) `Json`, no status check:
// an error page fails the decode, and that failure is the screen's "could not load". There is no wallet-core
// on iOS, so this is the whole of it.
package eu.europa.ec.shared.wallet.trustmark

import eu.europa.ec.corelogic.controller.WalletCoreTrustMarkController
import eu.europa.ec.corelogic.extension.localizedTrustMarkText
import eu.europa.ec.corelogic.model.TrustMarkDomain
import eu.europa.ec.corelogic.model.TrustMarkInformationDomain
import eu.europa.ec.shared.wallet.platform.iosUserLanguage
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class IosTrustMarkController(
    private val information: TrustMarkInformationDomain,
    /** The user's language, as Android takes it from its resource provider's locale. */
    private val userLanguageTag: () -> String = { iosUserLanguage() },
    private val httpClient: () -> HttpClient = { HttpClient(Darwin) },
) : WalletCoreTrustMarkController {

    override suspend fun getTrustMark(): Result<TrustMarkDomain> = runCatching {
        val client = httpClient()
        val body = try {
            client.get(information.resourceUrl).bodyAsText()
        } finally {
            client.close()
        }
        val resource = Json.decodeFromString(TrustMarkResource.serializer(), body)
        TrustMarkDomain(
            resourceUrl = information.resourceUrl,
            imageName = resource.image.name,
            imageUrl = resource.image.url,
            localisedText = resource.text.localisations.localizedTrustMarkText(userLanguageTag()),
            certifiedWalletsUrl = information.certifiedWalletsUrl,
            walletSolutionUrl = information.walletSolutionUrl,
        )
    }
}

/** wallet-core's `TrustMarkResource`: the logo and the localised text, as the EC publishes them. */
@Serializable
internal data class TrustMarkResource(val image: Image, val text: Text) {
    @Serializable
    data class Image(val name: String, val url: String)

    @Serializable
    data class Text(val name: String, val localisations: Map<String, String>)
}
