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

package eu.europa.ec.businesslogic.config

import android.content.Context
import eu.europa.ec.businesslogic.BuildConfig
import eu.europa.ec.eudi.rqes.HashAlgorithmOID
import eu.europa.ec.eudi.rqesui.domain.extension.toUriOrEmpty
import eu.europa.ec.eudi.rqesui.infrastructure.config.DocumentRetrievalConfig
import eu.europa.ec.eudi.rqesui.infrastructure.config.EudiRQESUiConfig
import eu.europa.ec.eudi.rqesui.infrastructure.config.data.QtspData
import eu.europa.ec.resourceslogic.R
import java.net.URI

/**
 * `local` flavor RQES configuration.
 *
 * RQES (remote document signing) is an OPTIONAL service — it is not needed to launch
 * the wallet, issue credentials, or present them. It therefore stays on the `dev`
 * reference signer by default, with the local alternative provided as a commented-out
 * line. To run the signer locally, uncomment the `${BuildConfig.LOCAL_IP}:8449`
 * endpoint (and remove/relocate the demo client secret below before doing anything
 * real). The TSA is a public timestamp authority and is left unchanged.
 */
class RQESConfigImpl(val context: Context) : EudiRQESUiConfig {

    override val qtsps: List<QtspData>
        get() = listOf(
            QtspData(
                name = "Wallet-Centric",
                // OPTIONAL service: RQES signer. Active = dev; local alternative commented.
                endpoint = "https://walletcentric.signer.dev.eudiw.dev/csc/v2".toUriOrEmpty(),
                // endpoint = "https://${BuildConfig.LOCAL_IP}:8449/csc/v2".toUriOrEmpty(), // local alt
                tsaUrl = "https://timestamp.sectigo.com/qualified",
                clientId = "wallet-client",
                clientSecret = "somesecret2",
                authFlowRedirectionURI = URI.create(BuildConfig.RQES_DEEPLINK),
                hashAlgorithm = HashAlgorithmOID.SHA_256,
            )
        )

    override val printLogs: Boolean get() = BuildConfig.DEBUG

    override val documentRetrievalConfig: DocumentRetrievalConfig
        get() = DocumentRetrievalConfig.X509Certificates(
            context,
            listOf(
                R.raw.pidissuerca02_cz,
                R.raw.pidissuerca02_ee,
                R.raw.pidissuerca02_eu,
                R.raw.pidissuerca02_lu,
                R.raw.pidissuerca02_nl,
                R.raw.pidissuerca02_pt,
                R.raw.pidissuerca02_ut
            )
        )
}
