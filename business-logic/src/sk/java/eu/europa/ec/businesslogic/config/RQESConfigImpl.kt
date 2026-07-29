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
import eu.europa.ec.eudi.rqesui.domain.entities.localization.LocalizableKey
import eu.europa.ec.eudi.rqesui.domain.extension.toUriOrEmpty
import eu.europa.ec.eudi.rqesui.infrastructure.config.DocumentRetrievalConfig
import eu.europa.ec.eudi.rqesui.infrastructure.config.EudiRQESUiConfig
import eu.europa.ec.eudi.rqesui.infrastructure.config.data.QtspData
import eu.europa.ec.resourceslogic.R
import java.net.URI

/**
 * `sk` flavor RQES configuration.
 *
 * RQES (remote document signing) is an OPTIONAL service — it is not needed to launch
 * the wallet, issue credentials, or present them. It therefore stays on the `dev`
 * reference signer by default, with the local alternative provided as a commented-out
 * line. To run the signer locally, uncomment the `${BuildConfig.LOCAL_IP}:8449`
 * endpoint (and remove/relocate the demo client secret below before doing anything
 * real). The TSA is a public timestamp authority and is left unchanged.
 */
class RQESConfigImpl(val context: Context) : EudiRQESUiConfig {

    // RQES signing sub-SDK strings — its own ~35 keys, separate from strings.xml. Provided
    // here for the sk flavor in Slovak + Hungarian; the SDK selects by device language
    // (Locale.getDefault().language) and falls back per key to the English default otherwise.
    override val translations: Map<String, Map<LocalizableKey, String>>
        get() = mapOf(
            "sk" to mapOf(
                LocalizableKey.SignDocument to "Podpísať dokument",
                LocalizableKey.CancelSignProcessTitle to "Zrušiť proces podpisovania?",
                LocalizableKey.CancelSignProcessSubtitle to "Zrušením sa vrátite späť na zoznam dokumentov bez podpísania dokumentu",
                LocalizableKey.CancelSignProcessSecondaryText to "Zrušiť podpisovanie",
                LocalizableKey.CancelSignProcessPrimaryText to "Pokračovať v podpisovaní",
                LocalizableKey.SelectDocumentTitle to "Dokument",
                LocalizableKey.SelectDocumentSubtitle to "Vyberte dokument zo svojho zariadenia na elektronické podpísanie.",
                LocalizableKey.SelectSigningService to "Vyberte službu podpisovania",
                LocalizableKey.SelectSigningServiceSubtitle to "Služba vzdialeného podpisovania umožňuje bezpečné online podpisovanie dokumentov.",
                LocalizableKey.SelectServiceTitle to "Služby podpisovania",
                LocalizableKey.SelectServiceSubtitle to "Vyberte službu podpisovania, ktorá sa použije na vydanie digitálneho certifikátu",
                LocalizableKey.SelectCertificateSubtitle to "Podpisový certifikát sa používa na overenie vašej totožnosti a je prepojený s vaším elektronickým podpisom.",
                LocalizableKey.SelectSigningCertificateTitle to "Vyberte podpisový certifikát",
                LocalizableKey.SelectSigningCertificateSubtitle to "Vyberte digitálny certifikát na podpísanie dokumentu",
                LocalizableKey.SigningService to "Služba podpisovania",
                LocalizableKey.SigningCertificate to "Podpisový certifikát",
                LocalizableKey.SigningCertificates to "Podpisové certifikáty",
                LocalizableKey.SuccessDescription to "Váš dokument bol úspešne podpísaný.",
                LocalizableKey.View to "ZOBRAZIŤ",
                LocalizableKey.Close to "Zavrieť",
                LocalizableKey.Cancel to "Zrušiť",
                LocalizableKey.Continue to "Pokračovať",
                LocalizableKey.Done to "Hotovo",
                LocalizableKey.Share to "Zdieľať",
                LocalizableKey.SharingDocument to "Zdieľať dokument?",
                LocalizableKey.CloseSharingMessage to "Zatvorením sa vrátite späť na hlavnú obrazovku bez uloženia alebo zdieľania dokumentu.",
                LocalizableKey.GenericErrorButtonRetry to "SKÚSIŤ ZNOVA",
                LocalizableKey.GenericErrorMessage to "Ups! Niečo sa pokazilo",
                LocalizableKey.GenericServiceErrorMessage to "Zdá sa, že služba podpisovania RQES je nedostupná. Skúste to znova neskôr.",
                LocalizableKey.GenericErrorDescription to "Ak problém pretrváva, kontaktujte zákaznícku podporu",
                LocalizableKey.GenericErrorDocumentNotFound to "Nenašli sa žiadne údaje dokumentu",
                LocalizableKey.GenericErrorDocumentMultipleNotSupported to "Viacero dokumentov momentálne nie je podporovaných",
                LocalizableKey.GenericErrorQtspNotFound to "Nenašiel sa žiadny vybraný QTSP",
                LocalizableKey.GenericErrorCertificatesNotFound to "Nenašli sa žiadne certifikáty",
                LocalizableKey.Certificate to "Certifikát @arg",
            ),
            "hu" to mapOf(
                LocalizableKey.SignDocument to "Dokumentum aláírása",
                LocalizableKey.CancelSignProcessTitle to "Megszakítja az aláírási folyamatot?",
                LocalizableKey.CancelSignProcessSubtitle to "A megszakítás visszairányítja a dokumentumok listájához a dokumentum aláírása nélkül",
                LocalizableKey.CancelSignProcessSecondaryText to "Aláírás megszakítása",
                LocalizableKey.CancelSignProcessPrimaryText to "Aláírás folytatása",
                LocalizableKey.SelectDocumentTitle to "Dokumentum",
                LocalizableKey.SelectDocumentSubtitle to "Válasszon egy dokumentumot az eszközéről az elektronikus aláíráshoz.",
                LocalizableKey.SelectSigningService to "Válasszon aláírási szolgáltatást",
                LocalizableKey.SelectSigningServiceSubtitle to "A távoli aláírási szolgáltatás biztonságos online dokumentum-aláírást tesz lehetővé.",
                LocalizableKey.SelectServiceTitle to "Aláírási szolgáltatások",
                LocalizableKey.SelectServiceSubtitle to "Válassza ki azt az aláírási szolgáltatást, amely a digitális tanúsítvány kiállításához lesz használva",
                LocalizableKey.SelectCertificateSubtitle to "Az aláírási tanúsítvány az Ön személyazonosságának ellenőrzésére szolgál, és az elektronikus aláírásához kapcsolódik.",
                LocalizableKey.SelectSigningCertificateTitle to "Válasszon aláírási tanúsítványt",
                LocalizableKey.SelectSigningCertificateSubtitle to "Válasszon digitális tanúsítványt a dokumentum aláírásához",
                LocalizableKey.SigningService to "Aláírási szolgáltatás",
                LocalizableKey.SigningCertificate to "Aláírási tanúsítvány",
                LocalizableKey.SigningCertificates to "Aláírási tanúsítványok",
                LocalizableKey.SuccessDescription to "Sikeresen aláírta a dokumentumát.",
                LocalizableKey.View to "MEGTEKINTÉS",
                LocalizableKey.Close to "Bezárás",
                LocalizableKey.Cancel to "Mégse",
                LocalizableKey.Continue to "Folytatás",
                LocalizableKey.Done to "Kész",
                LocalizableKey.Share to "Megosztás",
                LocalizableKey.SharingDocument to "Megosztja a dokumentumot?",
                LocalizableKey.CloseSharingMessage to "A bezárás visszairányítja a kezdőképernyőre a dokumentum mentése vagy megosztása nélkül.",
                LocalizableKey.GenericErrorButtonRetry to "ÚJRA",
                LocalizableKey.GenericErrorMessage to "Hoppá! Valami hiba történt",
                LocalizableKey.GenericServiceErrorMessage to "Úgy tűnik, az RQES aláírási szolgáltatás nem érhető el. Kérjük, próbálja meg később.",
                LocalizableKey.GenericErrorDescription to "Ha a probléma továbbra is fennáll, forduljon az ügyfélszolgálathoz",
                LocalizableKey.GenericErrorDocumentNotFound to "Nem található dokumentumadat",
                LocalizableKey.GenericErrorDocumentMultipleNotSupported to "Több dokumentum jelenleg nem támogatott",
                LocalizableKey.GenericErrorQtspNotFound to "Nem található kiválasztott QTSP",
                LocalizableKey.GenericErrorCertificatesNotFound to "Nem található tanúsítvány",
                LocalizableKey.Certificate to "Tanúsítvány @arg",
            ),
        )

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
