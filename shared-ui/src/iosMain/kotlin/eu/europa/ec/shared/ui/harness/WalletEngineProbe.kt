// How iOS behaviour gets verified.
//
// `simctl` cannot tap, so nothing on a screen can be reached by driving the UI; this walks the same
// interactors the screens use and prints what they answer. That makes it the iOS counterpart of the
// emulator runs on Android, not a test — it talks to the live EUDI dev issuer and verifier, and it
// *writes*: it seeds fixture documents, sets a PIN, issues, presents and re-issues against the real
// store.
//
// Which is why it does not run unless asked. `iOSApp.init()` starts it only for a launch carrying
// `--wallet-probe`; an ordinary launch touches none of this.
package eu.europa.ec.shared.ui.harness

import eu.europa.ec.shared.wallet.WalletDocument
import eu.europa.ec.shared.wallet.multipaz.IosWalletEngine
import eu.europa.ec.shared.wallet.multipaz.IosTransactionKind
import eu.europa.ec.shared.wallet.multipaz.createIosWalletEngine
import eu.europa.ec.shared.wallet.multipaz.runBackgroundReIssuance
import eu.europa.ec.dashboardfeature.interactor.DashboardInteractor
import eu.europa.ec.dashboardfeature.interactor.HomeInteractor
import eu.europa.ec.dashboardfeature.interactor.DocumentDetailsInteractor
import eu.europa.ec.dashboardfeature.interactor.DocumentDetailsInteractorPartialState
import eu.europa.ec.dashboardfeature.interactor.DocumentsPlatformBridge
import eu.europa.ec.dashboardfeature.interactor.SettingsInteractor
import eu.europa.ec.dashboardfeature.interactor.TransactionInteractorGetTransactionsPartialState
import eu.europa.ec.dashboardfeature.interactor.TransactionsInteractor
import eu.europa.ec.dashboardfeature.interactor.TransactionDetailsInteractor
import eu.europa.ec.dashboardfeature.interactor.TransactionDetailsInteractorPartialState
import eu.europa.ec.dashboardfeature.ui.component.BottomNavigationItem
import eu.europa.ec.commonfeature.config.IssuanceFlowType
import eu.europa.ec.issuancefeature.interactor.AddDocumentInteractor
import eu.europa.ec.issuancefeature.interactor.AddDocumentInteractorScopedPartialState
import eu.europa.ec.issuancefeature.interactor.DocumentIssuanceSuccessInteractor
import eu.europa.ec.issuancefeature.interactor.DocumentIssuanceSuccessInteractorGetUiItemsPartialState
import eu.europa.ec.issuancefeature.interactor.DocumentOfferInteractor
import eu.europa.ec.issuancefeature.interactor.ResolveDocumentOfferInteractorPartialState
import eu.europa.ec.authenticationlogic.controller.storage.PinStorageController
import eu.europa.ec.authenticationlogic.secure.securePinOf
import eu.europa.ec.commonfeature.interactor.QuickPinInteractor
import eu.europa.ec.commonfeature.interactor.BiometricInteractor
import eu.europa.ec.commonfeature.interactor.QrScanInteractor
import eu.europa.ec.authenticationlogic.storage.IosBiometricGate
import eu.europa.ec.authenticationlogic.storage.IosBiometricOutcome
import eu.europa.ec.dashboardfeature.interactor.SettingsPlatformBridge
import eu.europa.ec.shared.wallet.trust.probeLoteTrustLists
import eu.europa.ec.startupfeature.interactor.SplashInteractor
import eu.europa.ec.shared.resources.StringCatalog
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import eu.europa.ec.shared.wallet.multipaz.IosAuthorizationRedirects
import eu.europa.ec.shared.wallet.multipaz.IosCredentialIssuer
import eu.europa.ec.shared.wallet.multipaz.IosIssuanceProgress
import eu.europa.ec.issuancefeature.interactor.PlatformOfferResolution
import eu.europa.ec.shared.ui.di.IosDocumentOfferPlatformBridge
import eu.europa.ec.shared.wallet.multipaz.IosCredentialOfferReader
import eu.europa.ec.shared.wallet.multipaz.IosDeepLinks
import eu.europa.ec.shared.wallet.multipaz.IosIssuerCatalog
import eu.europa.ec.shared.wallet.multipaz.harness.REVOCATION_FIXTURE_INDEX
import eu.europa.ec.shared.wallet.multipaz.harness.REVOCATION_FIXTURE_URI
import eu.europa.ec.shared.wallet.multipaz.harness.revocationFixtureToken
import eu.europa.ec.shared.wallet.multipaz.harness.seedIosRevocableFixture
import eu.europa.ec.shared.wallet.multipaz.harness.createVerifierTransaction
import eu.europa.ec.shared.wallet.multipaz.harness.verifierEvents
import eu.europa.ec.presentationfeature.interactor.PresentationLoadingInteractor
import eu.europa.ec.presentationfeature.interactor.PresentationLoadingObserveResponsePartialState
import eu.europa.ec.presentationfeature.interactor.PresentationRequestInteractor
import eu.europa.ec.presentationfeature.interactor.PresentationRequestInteractorPartialState
import eu.europa.ec.presentationfeature.interactor.PresentationSuccessInteractor
import eu.europa.ec.presentationfeature.interactor.PresentationSuccessInteractorGetUiItemsPartialState
import eu.europa.ec.shared.wallet.multipaz.harness.seedIosWalletFixture
import eu.europa.ec.shared.wallet.multipaz.harness.buildDcApiProbeRequest
import eu.europa.ec.shared.wallet.multipaz.IosDocumentProviderBridge
import eu.europa.ec.shared.wallet.multipaz.IosDcApiConsent
import eu.europa.ec.shared.wallet.multipaz.IosPresentmentDisclosure
import eu.europa.ec.shared.wallet.multipaz.IosPresentmentRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import platform.Foundation.NSFileManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.Foundation.writeToFile
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import eu.europa.ec.commonfeature.config.PresentationMode
import eu.europa.ec.commonfeature.config.RequestUriConfig
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.proximityfeature.interactor.ProximityLoadingInteractor
import eu.europa.ec.proximityfeature.interactor.ProximityQRInteractor
import eu.europa.ec.proximityfeature.interactor.ProximityQRPartialState
import eu.europa.ec.proximityfeature.interactor.ProximityRequestInteractor
import eu.europa.ec.proximityfeature.interactor.ProximitySuccessInteractor
import org.koin.mp.KoinPlatform

/**
 * Walks the wallet's iOS half end to end, reporting each step to [onResult].
 *
 * Seeded fixtures come first because two things still need them: revocation, which no dev issuer
 * publishes a status list for, and the empty-wallet paths. Everything after that is the real thing —
 * real issuance from `dev.issuer-backend.eudiw.dev`, a real presentation to
 * `dev.verifier-backend.eudiw.dev`, a real credential refresh.
 */
fun probeMultipazWalletEngine(onResult: (String) -> Unit) {
    CoroutineScope(Dispatchers.Main).launch {
        try {
            val seeded = seedIosWalletFixture()
            onResult(if (seeded != null) "seeded fixture document $seeded" else "wallet already seeded")

            val engine = createIosWalletEngine()
            val engineForRevocation = KoinPlatform.getKoin().get<IosWalletEngine>()

            val cheap = engine.getAllDocuments()
            onResult("getAllDocuments -> ${cheap.size} document(s), ids=${cheap.map { it.id }}")

            engine.getAllDocumentsWithDetails(locale = "en").forEach { onResult(it.describe("en")) }
            engine.getAllDocumentsWithDetails(locale = "sk").forEach { onResult(it.describe("sk")) }

            val pid = engine.getMainPidDocument()
            onResult(
                if (pid == null) "getMainPidDocument -> none"
                else "getMainPidDocument -> ${pid.id} claims=${pid.claims}"
            )

            val bookmarkId = cheap.firstOrNull()?.id
            if (bookmarkId != null) {
                engine.storeBookmark(bookmarkId)
                val stored = engine.isDocumentBookmarked(bookmarkId)
                engine.deleteBookmark(bookmarkId)
                val cleared = engine.isDocumentBookmarked(bookmarkId)
                onResult("bookmark stored=$stored cleared=${!cleared}")
            }

            onResult("revoked ids cached before this run: ${engine.getRevokedDocumentIds()}")

            // The details path end to end — bridge builds the claim tree, shared interactor turns it
            // into the screen's state. Verified here because `simctl` cannot synthesise the tap that
            // would open the screen.
            cheap.firstOrNull()?.id?.let { documentId ->
                val details = KoinPlatform.getKoin().get<DocumentDetailsInteractor>()
                details.getDocumentDetails(documentId, wasIssuerDetailsExpanded = false)
                    .first()
                    .let { state ->
                        onResult(
                            when (state) {
                                is DocumentDetailsInteractorPartialState.Success ->
                                    "getDocumentDetails -> ${state.documentDetailsDomain.docName} " +
                                            "claims=${state.documentDetailsDomain.documentClaims.size} " +
                                            "state=${state.issuerDetails?.documentState} " +
                                            "issuer=${state.issuerDetails?.issuerName} " +
                                            "bookmarked=${state.documentIsBookmarked} " +
                                            "counter=${state.documentCredentialsInfoUi?.title} " +
                                            "firstClaim=${state.documentDetailsDomain.documentClaims.firstOrNull()?.displayTitle}"

                                is DocumentDetailsInteractorPartialState.Failure ->
                                    "getDocumentDetails FAILED: ${state.error}"
                            }
                        )
                    }
            }

            // The same path again, but for a document this wallet actually *provisioned* — the only
            // kind that carries the issuer's per-claim display names. The fixture above deliberately
            // does not, so it shows raw identifiers; printing both is what makes the difference
            // visible, and a regression in either direction obvious.
            engine.getAllDocumentsWithDetails(locale = "en")
                // A *real* issuer, not just any issuer metadata: the seeded fixtures carry
                // `sampleIssuerMetadata`, so "has an issuer reference" picks one of those and shows
                // identifiers — which is exactly how the first version of this check fooled itself.
                .filter {
                    engineForRevocation.getIssuerReference(it.id)
                        ?.issuerId?.startsWith("https://dev.") == true
                }
                // One per format: mdoc and SD-JWT VC read through different multipaz credentials, so a
                // single sample would leave one of the two unproven.
                .distinctBy { it.formatType }
                .forEach { provisioned ->
                    onResult("  format ${provisioned.formatType}:")
                    listOf("en", "sk").forEach { locale ->
                        val details = KoinPlatform.getKoin().get<DocumentDetailsInteractor>()
                            .getDocumentDetails(provisioned.id, wasIssuerDetailsExpanded = false)
                            .first()
                        onResult(
                            "[$locale] provisioned claim titles -> " + when (details) {
                                is DocumentDetailsInteractorPartialState.Success ->
                                    details.documentDetailsDomain.documentClaims
                                        .take(3)
                                        .joinToString { it.displayTitle }

                                is DocumentDetailsInteractorPartialState.Failure -> "FAILED: ${details.error}"
                            }
                        )
                    }
                }

            // The two dashboard paths a screenshot cannot reach, since `simctl` cannot synthesise the
            // tap that would open the side menu or select another tab. Both are new on iOS with the
            // shared `DashboardScreen`, and neither had ever run here before it.
            val strings = KoinPlatform.getKoin().get<StringCatalog>()
            val sideMenu = KoinPlatform.getKoin().get<DashboardInteractor>().getSideMenuOptions()
            onResult(
                "getSideMenuOptions -> ${sideMenu.size} item(s): " +
                        sideMenu.joinToString { option ->
                            val title = option.data.mainContentData
                            "${option.type}=" + when (title) {
                                is ListItemMainContentDataUi.Text -> title.text
                                else -> title::class.simpleName
                            }
                        }
            )

            // The History tab, now over multipaz's event log rather than an empty stub. What the
            // list shows is written by presenting and issuing, so this run reports whatever earlier
            // runs left behind — and the details path below is only reachable because of it.
            KoinPlatform.getKoin().get<TransactionsInteractor>().getTransactions().first()
                .let { state ->
                    onResult(
                        when (state) {
                            is TransactionInteractorGetTransactionsPartialState.Success ->
                                "getTransactions -> ${state.allTransactions.items.size} " +
                                        "transaction(s), dates=${state.availableDates}"

                            is TransactionInteractorGetTransactionsPartialState.Failure ->
                                "getTransactions FAILED: ${state.error}"
                        }
                    )
                }
            probeTransactionDetails(onResult)
            onResult("dashboard tab titles: ${BottomNavigationItem.entries.joinToString { strings.get(it.titleRes) }}")

            // The settings screen, two taps deep behind the side menu and so unreachable by
            // screenshot. What is worth seeing is that the list is honest — one row, because iOS has
            // no biometrics, no log files and no changelog URL — and that the row's preference is
            // REAL: toggling it moves the value the *documents* list reads, which is the whole point
            // of both bridges going through `IosPreferences`.
            val settings = KoinPlatform.getKoin().get<SettingsInteractor>()
            val documents = KoinPlatform.getKoin().get<DocumentsPlatformBridge>()
            onResult(
                "settings appVersion='${settings.getAppVersion()}' " +
                        "changelogUrl=${settings.getChangelogUrl()} " +
                        "rows=${settings.getSettingsItemsUi(settings.getChangelogUrl()).map { it.type }}"
            )

            val before = documents.showBatchIssuanceCounter()
            settings.toggleShowBatchIssuanceCounter()
            val afterToggle = documents.showBatchIssuanceCounter()
            settings.toggleShowBatchIssuanceCounter()
            onResult(
                "batch counter preference as the documents list sees it: " +
                        "$before -> toggled -> $afterToggle -> restored -> " +
                        "${documents.showBatchIssuanceCounter()}"
            )

            // The three issuance interactors, now shared. Probed rather than screenshotted because
            // `simctl` cannot synthesise the taps that reach these screens — and because two of the
            // three are expected to *refuse*, which is only meaningful if the refusal is visible.
            // Both flows the screen has: the first-document case, which offers PIDs only, and the
            // add-another case, which offers everything the configured issuers advertise. This is a live
            // network read of both EU dev issuers' metadata.
            val addDocument = KoinPlatform.getKoin().get<AddDocumentInteractor>()
            listOf(
                "PID only" to IssuanceFlowType.NoDocument,
                "any document" to IssuanceFlowType.ExtraDocument(formatType = null),
            ).forEach { (label, flowType) ->
                onResult(
                    "getAddDocumentOption($label) -> " + when (
                        val state = addDocument.getAddDocumentOption(flowType).first()
                    ) {
                        is AddDocumentInteractorScopedPartialState.Success ->
                            state.options.joinToString(prefix = "Success(", postfix = ")") { (issuer, items) ->
                                "$issuer: " + items.joinToString {
                                    (it.itemData.mainContentData as? ListItemMainContentDataUi.Text)?.text
                                        .orEmpty()
                                }
                            }

                        is AddDocumentInteractorScopedPartialState.NoOptions ->
                            "NoOptions(${state.errorMsg})"

                        // Reachable only with the registration check on, which is Android-only, so
                        // the probe should never print this. Named rather than swallowed by an
                        // `else` so it is obvious if it ever does.
                        is AddDocumentInteractorScopedPartialState.NoTrustedIssuers ->
                            "NoTrustedIssuers"

                        is AddDocumentInteractorScopedPartialState.Failure -> "Failure(${state.error})"
                    }
                )
            }

            val offer = KoinPlatform.getKoin().get<DocumentOfferInteractor>()
            onResult(
                "resolveDocumentOffer -> " + when (
                    val state = offer.resolveDocumentOffer("openid-credential-offer://probe").first()
                ) {
                    is ResolveDocumentOfferInteractorPartialState.Success ->
                        "Success(${state.documents.size} document(s), issuer=${state.issuerName})"

                    is ResolveDocumentOfferInteractorPartialState.NoDocument ->
                        "NoDocument(${state.issuerName})"

                    is ResolveDocumentOfferInteractorPartialState.IssuerNotTrusted -> "IssuerNotTrusted"
                    is ResolveDocumentOfferInteractorPartialState.Failure -> "Failure(${state.errorMessage})"
                }
            )

            // This one is expected to *work*: its platform half is the document-details bridge, which
            // iOS answers from multipaz. The ids are the fixture documents, standing in for a just-issued
            // batch.
            val success = KoinPlatform.getKoin().get<DocumentIssuanceSuccessInteractor>()
            onResult(
                "issuance success items -> " + when (
                    val state = success.getUiItems(cheap.map { it.id }).first()
                ) {
                    is DocumentIssuanceSuccessInteractorGetUiItemsPartialState.Success ->
                        "Success(${state.documentsUi.size} document(s): " +
                                state.documentsUi.joinToString {
                                    val name = (it.header.mainContentData as? ListItemMainContentDataUi.Text)?.text
                                    "$name/${it.nestedItems.size} claims"
                                } +
                                ", issuer=${state.headerConfig.relyingPartyData?.name})"

                    is DocumentIssuanceSuccessInteractorGetUiItemsPartialState.Failed ->
                        "Failed(${state.errorMessage})"
                }
            )

            // Revocation, end to end. iOS has no issuer that publishes a status list, so the fixture
            // supplies both halves: a document whose MSO points at a list, and the tokens that answer
            // it. The token is printed rather than served from here — there is no HTTP server on the
            // device — so the run is: launch once, copy the token to
            // `python3 -m http.server 8000` next to a `statuslist.jwt`, launch again.
            seedIosRevocableFixture()?.let { onResult("seeded revocable fixture $it") }
            onResult("--- revocation fixture token (REVOKED at index $REVOCATION_FIXTURE_INDEX) ---")
            onResult(revocationFixtureToken(revoked = true))
            onResult("--- revocation fixture token (VALID) ---")
            onResult(revocationFixtureToken(revoked = false))
            onResult("--- serve one of the above at $REVOCATION_FIXTURE_URI ---")

            val newlyRevoked = engineForRevocation.refreshRevocationStatuses { documentId, outcome ->
                onResult("  revocation check $documentId -> $outcome")
            }
            onResult(
                "refreshRevocationStatuses -> ${newlyRevoked.size} newly revoked " +
                        "${newlyRevoked.map { it.id }}; cached=${engineForRevocation.getRevokedDocumentIds()}"
            )

            // Before issuance, because issuance now consults it: the signer of the issuer's signed
            // metadata is checked against the EU PID Providers list.
            probeLoteTrustLists(onResult)

            onResult("--- issuance ---")
            probeAuthentication(onResult)
            probeQrScan(onResult)
            probeProximity(onResult)
            probeRemotePresentation(onResult)
            probeReIssuance(onResult)
            probeBackgroundReIssuance(onResult)
            probeIssuance(onResult)
            probeCredentialOffer(onResult)
            probeDcApi(onResult)

            onResult("OK")
        } catch (t: Throwable) {
            onResult("FAILED: ${t::class.simpleName}: ${t.message}")
        }
    }
}

private fun WalletDocument.describe(locale: String): String =
    "[$locale] $name / $formatType state=$issuanceState " +
            "credentials=$credentialsCount/$initialCredentialsCount low=$isLowOnCredentials " +
            "issued=$issuedAt expires=$expiresAt expired=$isExpired revoked=$isRevoked " +
            "issuer=$issuerName logo=$issuerLogoUri"

/**
 * How far proximity gets on a machine with no Bluetooth radio.
 *
 * Two things are worth seeing here and nowhere else. First, that the four interactors resolve — Koin
 * fails at the first `get()`, and the screens are three taps deep behind a card `simctl` cannot press,
 * so a missing definition would otherwise surface as a crash on a device. Second, what the QR screen
 * shows when advertising cannot start: the simulator has no radio, so this *should* report an error
 * rather than hang on a QR that never appears. On a device the same call publishes an `mdoc:` payload.
 */
/**
 * The document-provider extension's whole chain, against the real store, without iOS.
 *
 * This is the closest thing to a device run that exists today, and it is closer than it sounds: the
 * extension does exactly three things — take a raw ISO 18013-7 request, ask the user, hand back a
 * response — and only the first is Apple's. So driving [IosDocumentProviderBridge] with a request we
 * build ourselves exercises everything the extension owns, on documents a real issuer issued.
 *
 * ⚠️ What it cannot show is that iOS *routes* a request here, and nothing on the simulator can: the
 * provider entitlement is never authorised there, and `addRegistration` does not even refuse — it
 * hangs. That gap needs hardware and an `org-iso-mdoc` verifier, in that order.
 *
 * The consent step keeps **one** of the two requested claims, so the printed plaintext distinguishes a
 * wallet that honours the selection from one that sends everything and hides the rest.
 */
/** What the host script left for us: the verifier's request, and the origin it will bind. */
private class SuppliedDcApiRequest(val json: String, val origin: String)

/** `Documents/dcapi-request.json`, the same handover the authorization redirect uses. */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun suppliedDcApiRequest(): SuppliedDcApiRequest? {
    val path = NSHomeDirectory() + "/Documents/dcapi-request.json"
    if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return null
    val contents = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) ?: return null
    val parsed = Json.parseToJsonElement(contents).jsonObject
    val origin = parsed["origin"]?.jsonPrimitive?.content ?: return null
    val data = parsed["data"] ?: return null
    return SuppliedDcApiRequest(json = data.toString(), origin = origin)
}

/** Where the host script picks the answer up. Overwritten each run, like the redirect file. */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun writeDcApiResponse(responseJson: String) {
    val path = NSHomeDirectory() + "/Documents/dcapi-response.json"
    (responseJson as NSString).writeToFile(path, true, NSUTF8StringEncoding, null)
}

private suspend fun probeDcApi(onResult: (String) -> Unit) {
    onResult("--- DC API: the provider extension's chain, on real documents ---")

    // A request from the local `org-iso-mdoc` verifier if the host left one, otherwise one we build.
    // The verifier is the only thing that can drive this branch for real — Apple hands a provider ISO
    // 18013-7 requests and nothing else, and no deployed verifier speaks that — so answering *its*
    // request is what turns this probe from a self-test into an interop check.
    val supplied = suppliedDcApiRequest()
    val request = if (supplied == null) buildDcApiProbeRequest() else null
    val requestJson = supplied?.json ?: request!!.json
    val origin = supplied?.origin ?: DC_API_PROBE_ORIGIN
    onResult(
        if (supplied != null) "answering the verifier's request (origin $origin)"
        else "no verifier request on disk - answering one we built ourselves"
    )
    val bridge = IosDocumentProviderBridge.create()

    var offered: String? = null
    val result = bridge.present(
        protocol = "org-iso-mdoc",
        data = requestJson,
        origin = origin,
        consent = object : IosDcApiConsent {
            override suspend fun requestConsent(
                request: IosPresentmentRequest,
            ): List<IosPresentmentDisclosure>? {
                val documents = request.combinations.firstOrNull()?.documents.orEmpty()
                offered = "requester=${request.requesterName} trusted=${request.requesterIsTrusted} " +
                        "combinations=${request.combinations.size} " +
                        documents.joinToString(prefix = "[", postfix = "]") { document ->
                            "${document.documentName}/${document.docType}: " +
                                    document.claims.joinToString { it.displayName }
                        }
                // Keep the first claim only. Dropping the rest is the whole point: anything absent
                // here must be absent from the response.
                return documents.map { document ->
                    IosPresentmentDisclosure(
                        documentId = document.documentId,
                        credentialId = document.credentialId,
                        claims = document.claims.take(1).map { it.claim }.toSet(),
                    )
                }
            }
        },
    )

    onResult("consent saw: ${offered ?: "the screen was never reached"}")

    val responseJson = result.responseJson
    if (responseJson != null && supplied != null) {
        // Handed back the way the redirect comes in: a file the host script polls. The verifier
        // decrypts it, so nothing here has to be trusted to say the exchange worked.
        writeDcApiResponse(responseJson)
        onResult("wrote the response for the verifier (${responseJson.length} chars)")
    }
    if (responseJson == null) {
        onResult(
            "present -> no response " +
                    "(declined=${result.declined}, error=${result.errorMessage ?: "none"})"
        )
        return
    }

    onResult("present -> a response of ${responseJson.length} chars")
    if (request == null) {
        onResult("the verifier holds the key for this one - read its log for the verdict")
        return
    }
    val plaintext = request.decryptResponse(responseJson, origin)
        .decodeToString(throwOnInvalidSequence = false)
    onResult("decrypted ${plaintext.length} bytes — the session transcript matches multipaz's")

    // Named rather than dumped: the response is a whole mdoc, and what matters is which of the two
    // requested elements survived consent.
    val present = DC_API_PROBE_ELEMENTS.filter { it in plaintext }
    val absent = DC_API_PROBE_ELEMENTS - present.toSet()
    onResult("disclosed=$present withheld=$absent")
}

/**
 * A plausible verifier origin. It is never contacted — it only has to be the same string on both sides,
 * because it is bound into the session transcript the response is encrypted against.
 */
private const val DC_API_PROBE_ORIGIN = "https://verifier.example"

/** What [buildDcApiProbeRequest] asks for by default, in the order consent will see them. */
private val DC_API_PROBE_ELEMENTS = listOf("family_name", "given_name")

private suspend fun probeProximity(onResult: (String) -> Unit) {
    onResult("--- proximity: interactors and engagement ---")
    val koin = KoinPlatform.getKoin()

    // The gate Home puts in front of the whole flow. It answered `false` for longer than it should
    // have — a leftover from before proximity was shared — which made every route below unreachable
    // from the UI while the probe, which resolves interactors directly, saw nothing wrong.
    val home = koin.get<HomeInteractor>()
    onResult(
        "Home would ${if (home.isBleAvailable()) "start" else "REFUSE"} the proximity flow " +
                "(isBleAvailable=${home.isBleAvailable()}, " +
                "centralClientMode=${home.isBleCentralClientModeEnabled()})"
    )

    val qr = koin.get<ProximityQRInteractor>()
    koin.get<ProximityRequestInteractor>()
    koin.get<ProximityLoadingInteractor>()
    koin.get<ProximitySuccessInteractor>()
    onResult("proximity interactors resolved: QR, request, loading, success")

    qr.setConfig(RequestUriConfig(PresentationMode.Ble(DashboardRoute)))
    onResult("scopeId=${qr.presentationScopeId}")

    // Bounded: on a device the first state arrives as soon as the transport is advertising; here the
    // point is that *something* arrives rather than the screen waiting forever.
    val first = withTimeoutOrNull(PROXIMITY_PROBE_TIMEOUT) { qr.startQrEngagement().first() }
    onResult(
        when (first) {
            is ProximityQRPartialState.QrReady ->
                "startQrEngagement -> QR ready (${first.qrCode.take(24)}…)"

            is ProximityQRPartialState.Error -> "startQrEngagement -> error: ${first.error}"
            is ProximityQRPartialState.Connected -> "startQrEngagement -> connected"
            is ProximityQRPartialState.Disconnected -> "startQrEngagement -> disconnected"
            null -> "startQrEngagement -> nothing within $PROXIMITY_PROBE_TIMEOUT"
        }
    )
    qr.cancelTransfer()
}

// Longer than the presenter's own advertise timeout, so what shows up here is the presenter's
// answer rather than this probe giving up first.
private val PROXIMITY_PROBE_TIMEOUT = 30.seconds

/**
 * One real OpenID4VP exchange against the EUDI dev verifier, through the **production** interactors.
 *
 * This is the whole remote-presentation flow minus the taps: the request interactor is handed the link
 * a deep link would deliver, the combination it offers is accepted as a user pressing Share unchanged
 * would accept it, and the loading interactor sends. `simctl` cannot synthesise those taps, and unlike
 * proximity there is no radio in the way — so this is the one place the feature can actually be seen
 * working end to end on this machine.
 *
 * The verdict at the end is the *verifier's*, not ours. A wallet cannot tell whether the response it
 * built was acceptable, and this was the open question for the whole feature: multipaz implements
 * OpenID4VP 1.0 and the EUDI verifier is an independent implementation. Its event log settles it.
 */
private suspend fun probeRemotePresentation(onResult: (String) -> Unit) {
    onResult("--- remote presentation (OpenID4VP) ---")
    val koin = KoinPlatform.getKoin()

    val request = koin.get<PresentationRequestInteractor>()
    val loading = koin.get<PresentationLoadingInteractor>()
    val success = koin.get<PresentationSuccessInteractor>()
    onResult("presentation interactors resolved: request, loading, success")

    val transaction = createVerifierTransaction(onResult) ?: return
    onResult("verifier transaction ${transaction.transactionId}")
    onResult("client_id ${transaction.clientId}")

    // Exactly what the shared `DashboardViewModel` builds from a classified deep link.
    request.setConfig(
        config = RequestUriConfig(
            PresentationMode.OpenId4Vp(uri = transaction.authorizationUri, initiatorRoute = DashboardRoute)
        ),
        intentAction = null,
    )

    // Bounded, because the verifier is a live service: what should arrive is the consent state, and a
    // silence is worth reporting as one rather than hanging the probe.
    val asked = withTimeoutOrNull(PRESENTATION_PROBE_TIMEOUT) { request.getRequestDocuments().first() }
    val combination = when (asked) {
        is PresentationRequestInteractorPartialState.Success -> {
            onResult(
                "verifier '${asked.relyingParty.name}' " +
                        "(verified=${asked.relyingParty.isFullyVerified}) asked for " +
                        "${asked.combinationsUi.size} alternative(s), selectable=${asked.claimsAreSelectable}"
            )
            // A user picks the document they actually hold, not the first card. On a wallet that still
            // has seeded fixtures the first card *is* one, and a fixture's MSO is self-signed with no
            // x5chain — so presenting it proves only that the verifier validates, which is already
            // known. Preferring a real credential keeps the run meaningful on a fresh wallet.
            asked.combinationsUi.firstOrNull { combination ->
                combination.documents.none { it.domainPayload.docName.contains("fixture") }
            }?.also { first ->
                first.documents.forEach { document ->
                    onResult(
                        "  would share ${document.domainPayload.docName}: " +
                                document.domainPayload.docClaimsDomain.joinToString { it.key }
                    )
                }
                // Remembered for the re-issuance probe: every presentation permanently spends one
                // credential, so this is the document a refresh should have work to do on.
                lastPresentedDocumentId = first.documents.firstOrNull()?.domainPayload?.docId
                onResult("  (shared document id: $lastPresentedDocumentId)")
            }
        }

        is PresentationRequestInteractorPartialState.NoData -> {
            onResult(
                "verifier '${asked.relyingParty.name}' asked for nothing this wallet holds"
            )
            null
        }

        is PresentationRequestInteractorPartialState.Failure -> {
            onResult("getRequestDocuments FAILED: ${asked.error}")
            null
        }

        is PresentationRequestInteractorPartialState.VerifierNotTrusted -> {
            onResult("verifier not trusted")
            null
        }

        is PresentationRequestInteractorPartialState.Disconnect -> {
            onResult("the exchange ended before anything was asked")
            null
        }

        null -> {
            onResult("nothing arrived within $PRESENTATION_PROBE_TIMEOUT")
            null
        }
    }

    if (combination == null) {
        request.stopPresentation()
        return
    }

    // Pressing Share with every row left ticked, which is how the request screen starts.
    request.updateRequestedDocuments(combination)

    // The loading screen's two halves in the order its view-model runs them: observe first, then send
    // when `RequestReadyToBeSent` says the user has already consented.
    //
    // `first { }` rather than `collect { }`: the interactor publishes a StateFlow-backed stream that
    // never completes — which is right, since the screen keeps rendering — so a `collect` here would sit
    // until the timeout even after the answer arrived. It did, on the first run of this probe.
    val sent = withTimeoutOrNull(PRESENTATION_PROBE_TIMEOUT) {
        loading.observeResponse()
            .onEach { state ->
                if (state is PresentationLoadingObserveResponsePartialState.RequestReadyToBeSent) {
                    onResult("sendRequestedDocuments -> ${loading.sendRequestedDocuments()}")
                }
            }
            .first { it !is PresentationLoadingObserveResponsePartialState.RequestReadyToBeSent }
    }
    onResult(
        "observeResponse -> " + when (sent) {
            is PresentationLoadingObserveResponsePartialState.Success -> "Success"
            is PresentationLoadingObserveResponsePartialState.Redirect -> "Redirect(${sent.uri})"
            is PresentationLoadingObserveResponsePartialState.Failure -> "Failure(${sent.error})"
            is PresentationLoadingObserveResponsePartialState.UserAuthenticationRequired ->
                "UserAuthenticationRequired"

            is PresentationLoadingObserveResponsePartialState.IntentToSend -> "IntentToSend"
            is PresentationLoadingObserveResponsePartialState.RequestReadyToBeSent,
            null,
                -> "nothing within $PRESENTATION_PROBE_TIMEOUT"
        }
    )

    onResult(
        "success screen -> " + when (val items = success.getUiItems().first()) {
            is PresentationSuccessInteractorGetUiItemsPartialState.Success ->
                "${items.documentsUi.size} document(s): " +
                        items.documentsUi.joinToString {
                            val name = (it.header.mainContentData as? ListItemMainContentDataUi.Text)?.text
                            "$name/${it.nestedItems.size} claims"
                        } + ", verifier=${items.headerConfig.relyingPartyData?.name}" +
                        ", redirect=${success.redirectUri}"

            is PresentationSuccessInteractorGetUiItemsPartialState.Failed -> "Failed(${items.errorMessage})"
        }
    )

    // The verifier's own account, which is the only judgement that counts.
    onResult("--- the verifier's account of it ---")
    verifierEvents(transaction.transactionId).forEach { onResult("  $it") }

    success.stopPresentation()
}

// The verifier is a live service reached over the network; long enough for a request object fetch and a
// response POST, short enough that a probe run does not look hung.
private val PRESENTATION_PROBE_TIMEOUT = 60.seconds

/** What the presentation probe shared, so the re-issuance probe can refresh that same document. */
private var lastPresentedDocumentId: String? = null

/**
 * The transaction the History tab's newest row leads to.
 *
 * Probed rather than screenshotted because reaching it needs two taps `simctl` cannot make — the
 * History tab, then a row. Worth seeing rather than assuming: the claims it lists come back out of a
 * *stored* event, so this is the only check that what multipaz wrote at consent time survives being
 * read back later.
 */
private suspend fun probeTransactionDetails(onResult: (String) -> Unit) {
    val koin = KoinPlatform.getKoin()
    val transactions = koin.get<IosWalletEngine>().getTransactions()
    transactions.forEach { transaction ->
        onResult(
            "  logged: ${transaction.kind} at ${transaction.createdAt} " +
                    "party=${transaction.relyingPartyName} documents=${transaction.documentNames}"
        )
    }

    // The newest *presentation*, not simply the newest: an issuance shares nothing, so opening one
    // would exercise the details screen without ever exercising the claims it exists to show.
    val newest = transactions.firstOrNull { it.kind == IosTransactionKind.Presentation }
        ?: transactions.firstOrNull()
    if (newest == null) {
        onResult("transaction details -> nothing logged yet")
        return
    }

    when (val state = koin.get<TransactionDetailsInteractor>()
        .getTransactionDetails(newest.id).first()) {
        is TransactionDetailsInteractorPartialState.Success -> {
            val details = state.transactionDetailsUi
            onResult(
                "getTransactionDetails -> ${details.transactionDetailsCardUi.transactionTypeLabel}" +
                        " / ${details.transactionDetailsCardUi.transactionStatusLabel}" +
                        " on ${details.transactionDetailsCardUi.transactionDate}" +
                        " with ${details.transactionDetailsCardUi.relyingPartyName}"
            )
            details.transactionDetailsDataShared.dataSharedItems.forEach { document ->
                val name = (document.header.mainContentData as? ListItemMainContentDataUi.Text)?.text
                onResult("  shared $name: ${document.nestedItems.size} claim(s)")
            }
        }

        is TransactionDetailsInteractorPartialState.Failure ->
            onResult("getTransactionDetails FAILED: ${state.error}")
    }
}

/**
 * Drives a real issuance through the **production** [IosCredentialIssuer], substituting only the browser.
 *
 * That substitution is the whole reason this exists: nothing on the simulator can log into Keycloak, and
 * `simctl openurl` cannot deliver a custom-scheme redirect back (LaunchServices refuses it). So the
 * harness prints the authorization URL for a script to complete, and feeds the resulting redirect into
 * `IosAuthorizationRedirects` — the same channel the app delegate uses on a device. Everything else is the
 * shipping path: the same store, the same provisioning handler, the same compatibility HTTP client.
 *
 * On a device none of this is needed; the bridge's default opens Safari and the delegate answers.
 */
private suspend fun probeIssuance(onResult: (String) -> Unit) {
    val issuer = IosIssuerCatalog.issuers.last()
    // mdoc, because everything else in this probe (proximity, revocation, the credential counter) is
    // written against it. Swap in `eu.europa.ec.eudi.pid_vc_sd_jwt` to issue an SD-JWT VC PID instead —
    // worth doing after touching claim reading, since the two formats come from different multipaz
    // credentials, and the per-format report above then covers both.
    val configurationId = "eu.europa.ec.eudi.pid_mso_mdoc"
    val engine = KoinPlatform.getKoin().get<IosWalletEngine>()

    val issuing = IosCredentialIssuer(
        walletEngine = engine,
        openAuthorizationUrl = { url ->
            // On its own line and in full, so a script can pick it up.
            onResult("AUTHORIZE-HERE $url")
        },
    )

    onResult("--- issuance: ${issuer.issuerUrl} / $configurationId ---")
    coroutineScope {
        // Feeds whatever a host script drops in Documents into the redirect channel the issuer awaits.
        val redirects = launch { deliverRedirectFromFile(onResult) }
        val progress = issuing.issue(
            issuerId = issuer.issuerUrl,
            configurationIds = listOf(configurationId),
        ).first()
        redirects.cancel()

        onResult(
            when (progress) {
                is IosIssuanceProgress.Issued ->
                    "ISSUED ${progress.documentIds} failures=${progress.failures}"

                is IosIssuanceProgress.Failure -> "ISSUANCE FAILED: ${progress.message}"
            }
        )
    }

    // The point of the whole exercise: the document is in the wallet the UI reads, with its claims.
    engine.getAllDocumentsWithDetails(locale = "en").forEach {
        onResult(
            "  reader sees: ${it.name} / ${it.formatType} state=${it.issuanceState} " +
                    "credentials=${it.credentialsCount}/${it.initialCredentialsCount}"
        )
    }
}

/** The harness half: a host script writes the redirect here, and this hands it to the issuer. */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private suspend fun deliverRedirectFromFile(onResult: (String) -> Unit) {
    val path = NSHomeDirectory() + "/Documents/authorization-redirect.txt"
    val manager = NSFileManager.defaultManager
    // A file left from a previous run holds a spent authorization code.
    if (manager.fileExistsAtPath(path)) manager.removeItemAtPath(path, null)

    while (true) {
        delay(1.seconds)
        if (!manager.fileExistsAtPath(path)) continue
        val contents = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)?.trim()
        manager.removeItemAtPath(path, null)
        if (!contents.isNullOrEmpty()) {
            onResult("delivering the redirect from authorization-redirect.txt")
            IosAuthorizationRedirects.deliver(contents)
            return
        }
    }
}

/**
 * Reads and accepts a credential offer through the **production** bridge, with the same browser
 * substitution [probeIssuance] uses.
 *
 * The offer is built here rather than scanned: the deep-link path that would normally deliver one
 * (`openid-credential-offer://` → app delegate → `IosDeepLinks` → the dashboard's shared view-model) cannot
 * be driven on a simulator, since LaunchServices refuses to hand a custom scheme to `simctl openurl`. What
 * that path delivers *is* a link like this one, so everything downstream of it is what runs here.
 */
private suspend fun probeCredentialOffer(onResult: (String) -> Unit) {
    val issuer = IosIssuerCatalog.issuers.last()
    val offerJson = """{"credential_issuer":"${issuer.issuerUrl}",""" +
            """"credential_configuration_ids":["eu.europa.ec.eudi.pid_mso_mdoc"]}"""
    val offerUri = "openid-credential-offer://?credential_offer=" + offerJson.percentEncoded()

    // The deep-link slot the app delegate writes to, checked here because the delegate itself cannot be
    // exercised on a simulator.
    IosDeepLinks.deliver(offerUri)
    onResult("--- credential offer: pending=${IosDeepLinks.takePending() != null} ---")

    val bridge = IosDocumentOfferPlatformBridge(
        offers = KoinPlatform.getKoin().get<IosCredentialOfferReader>(),
        credentialIssuer = IosCredentialIssuer(
            walletEngine = KoinPlatform.getKoin().get<IosWalletEngine>(),
            openAuthorizationUrl = { url -> onResult("AUTHORIZE-HERE $url") },
        ),
    )

    when (val resolution = bridge.resolveOffer(offerUri, bridge.localeTag())) {
        is PlatformOfferResolution.Success -> onResult(
            "offer resolved: ${resolution.documentNames} from '${resolution.issuerName}' " +
                    "pid=${resolution.containsPid} txCode=${resolution.txCodeLength}"
        )

        is PlatformOfferResolution.NoDocuments -> onResult("offer resolved with no documents")
        is PlatformOfferResolution.IssuerNotTrusted -> onResult("offer issuer not trusted")
        is PlatformOfferResolution.Failure -> {
            onResult("offer FAILED to resolve: ${resolution.errorMessage}")
            return
        }
    }

    coroutineScope {
        val redirects = launch { deliverRedirectFromFile(onResult) }
        val state = bridge.issueResolvedOffer(offerUri = offerUri, txCode = null).first()
        redirects.cancel()
        onResult("offer issuance -> $state")
    }
}

/** Percent-encodes a query-parameter value; the offer travels inside one. */
private fun String.percentEncoded(): String = buildString {
    this@percentEncoded.encodeToByteArray().forEach { byte ->
        val char = byte.toInt().toChar()
        if (char.isLetterOrDigit() || char in "-_.~") append(char)
        else append('%').append(byte.toInt().and(0xFF).toString(16).uppercase().padStart(2, '0'))
    }
}

/**
 * Exercises the **real** Keychain-backed PIN store, which unit tests cannot: a test binary has no
 * keychain-access entitlement and `SecItemAdd` answers -34018 there. In the app it has one, so this is the
 * only place `IosPinStorage`'s storage half can be shown to work.
 *
 * It also sets a PIN when the wallet has none, so a simulator run reaches the unlock screen on the next
 * launch — `simctl` cannot type into the PIN field, and this stands in for that.
 */
private suspend fun probeAuthentication(onResult: (String) -> Unit) {
    val koin = KoinPlatform.getKoin()
    val storage = koin.get<PinStorageController>()
    val quickPin = koin.get<QuickPinInteractor>()

    if (!storage.hasPin()) {
        storage.setPin(securePinOf(PROBE_PIN))
        onResult("no PIN was set; stored one so the next launch reaches the unlock screen")
    }

    onResult(
        "PIN store: hasPin=${storage.hasPin()} " +
                "correctPinAccepted=${storage.isPinValid(securePinOf(PROBE_PIN))} " +
                "wrongPinRejected=${!storage.isPinValid(securePinOf("000000"))}"
    )
    onResult("PIN lockout state: ${quickPin.getPinLockoutState()}")
    onResult("after-splash route: ${koin.get<SplashInteractor>().getAfterSplashRoute()::class.simpleName}")

    // Biometric login. The prompt itself cannot be answered from here — `simctl` has no way to press
    // "Use Face ID" — but everything around it can be seen: whether the device offers biometrics,
    // whether the Keychain will accept the access-control policy at all, and whether the switch's
    // state follows the item rather than a preference.
    //
    // 📌 On a REAL device these reads must not prompt at all. If a Touch ID prompt appears while this
    // section runs, `IosBiometricGate.isEnabled` has lost its `kSecUseAuthenticationUISkip` — see the
    // trap recorded there.
    val biometrics = koin.get<BiometricInteractor>()
    val settings = koin.get<SettingsPlatformBridge>()
    onResult("biometrics availability: ${biometrics.getBiometricsAvailability()}")
    onResult("biometric login enabled: ${biometrics.getBiometricUserSelection()}")

    val wasEnabled = biometrics.getBiometricUserSelection()
    biometrics.storeBiometricsUsageDecision(shouldUseBiometrics = true)
    val enabled = biometrics.getBiometricUserSelection()
    onResult(
        "enabling it -> $enabled" +
                if (enabled) " (the Keychain accepted a biometry-gated item)"
                // 🪤 Not "the Keychain refused the policy", which this used to claim. On a device that
                // reported biometrics available, the policy was accepted and the *read-back* prompted
                // and was cancelled — so the honest message names both possibilities and points at the
                // log, where `IosBiometricGate` records the actual OSStatus.
                else " (no item afterwards: either SecItemAdd was refused or the read-back failed —" +
                        " see the IosBiometricGate OSStatus in the log)"
    )
    // The settings switch must agree with the login screen, since both read the same item.
    onResult("settings row agrees: ${settings.isBiometricsEnabled() == enabled}")

    // The prompt itself. `simctl` cannot press a button, but it *can* tell the simulator's biometric
    // sensor that a face matched — so this is answerable here, unlike every other system prompt.
    if (enabled) {
        // The gate rather than the interactor, for one reason only: `authenticateWithBiometrics`
        // takes a `PlatformContext`, which is uninhabited on iOS and so cannot be constructed here.
        // The interactor's own body is one `when` over exactly this call's result.
        onResult("BIOMETRIC-PROMPT-NOW")
        val outcome = withTimeoutOrNull(BIOMETRIC_PROBE_TIMEOUT) {
            koin.get<IosBiometricGate>().authenticate(reason = "Probe run")
        }
        onResult(
            "authenticate -> " + when (outcome) {
                is IosBiometricOutcome.Success ->
                    "Success (the Keychain released the gated item, so the system authenticated)"

                is IosBiometricOutcome.Cancelled -> "Cancelled"
                is IosBiometricOutcome.Failed -> "Failed(${outcome.message})"
                null -> "nothing within $BIOMETRIC_PROBE_TIMEOUT"
            }
        )
    }

    biometrics.storeBiometricsUsageDecision(shouldUseBiometrics = false)
    onResult("disabling it -> ${biometrics.getBiometricUserSelection()}")
    // Left as it was found, so a run does not change what the next launch's login screen offers.
    if (wasEnabled) biometrics.storeBiometricsUsageDecision(shouldUseBiometrics = true)
}

/** Long enough for a host script to notice the prompt and answer it; short enough not to hang a run. */
private val BIOMETRIC_PROBE_TIMEOUT = 20.seconds

/**
 * As far as the QR scanner can be taken on a machine with no camera.
 *
 * Which is not far, and that is the point of running it: the screen is three taps deep, and what can be
 * seen from here is that the interactor resolves, that its validity rule accepts the links this wallet
 * is actually opened with, and that it refuses what it should. The camera itself is unreachable —
 * `AVCaptureDevice.defaultDeviceWithMediaType` returns null on the simulator — so the surface reports
 * no access and the screen shows its framing brackets over black.
 */
private suspend fun probeQrScan(onResult: (String) -> Unit) {
    onResult("--- QR scan ---")
    val interactor = KoinPlatform.getKoin().get<QrScanInteractor>()

    // The three kinds of link a wallet QR actually carries, plus two that should be refused.
    listOf(
        "openid4vp://?client_id=x509_hash%3Aabc&request_uri=https%3A%2F%2Fv.test%2Fr" to true,
        "haip-vp://?request_uri=https%3A%2F%2Fv.test%2Fr" to true,
        "openid-credential-offer://?credential_offer=%7B%7D" to true,
        // No query: nothing for the wallet to act on, whatever the scheme says.
        "openid4vp://verifier.test" to false,
        "just some text on a poster" to false,
    ).forEach { (link, expected) ->
        val actual = interactor.isScannedQrValid(link)
        onResult("  ${if (actual == expected) "ok " else "WRONG"} valid=$actual for ${link.take(46)}")
    }
}

/**
 * Re-issuance against the real issuer, for a document this wallet actually provisioned.
 *
 * The interesting case cannot be faked: a refresh needs the authorization multipaz stored at issuance,
 * so only a genuinely issued document has one — a seeded fixture does not, and the unit tests cover
 * that refusal. This runs the real thing, which means the credential counter should go up.
 */
private suspend fun probeBackgroundReIssuance(onResult: (String) -> Unit) {
    onResult("--- background re-issuance ---")

    // The sweep `BGTaskScheduler` runs, driven directly. The scheduler itself cannot be exercised here
    // — the simulator has no scheduler at all (`BGTaskSchedulerErrorDomain` code 1 on every submit), and
    // firing a task needs the Xcode debugger or a device — so this proves the half that is testable:
    // that the sweep reads the store, applies each document's own policy, and reports honestly.
    //
    // "due=0" is a pass, not a gap: it means every document's issuer-advertised threshold says there is
    // nothing to top up, which is the expected state right after `probeReIssuance` has just refreshed.
    val summary = withTimeoutOrNull(RE_ISSUANCE_PROBE_TIMEOUT) { runBackgroundReIssuance() }
    if (summary == null) {
        onResult("runBackgroundReIssuance timed out")
        return
    }
    onResult(
        "runBackgroundReIssuance -> $summary. iOS decides when the real task runs, so nothing may " +
                "depend on it having happened; every foreground path still tops up on its own."
    )
}

private suspend fun probeReIssuance(onResult: (String) -> Unit) {
    onResult("--- re-issuance ---")
    val engine = KoinPlatform.getKoin().get<IosWalletEngine>()

    // The document the presentation just shared, which is the case this feature exists for: every
    // presentation permanently spends one credential (multipaz's `keyBoundCredentialMaxUses` is 1), so
    // a refresh should have exactly one replacement to fetch.
    //
    // The visible counter will not move, and that is not a bug: it counts *certified* credentials, and
    // a spent one stays until a replacement certifies. What proves the refresh did work is the number
    // multipaz reports fetching, which the log line below carries.
    val refreshable = engine.getAllDocumentsWithDetails(locale = "en")
        .filter { engine.getIssuerReference(it.id)?.issuerId?.startsWith("https://dev.") == true }
    val target = refreshable.firstOrNull { it.id == lastPresentedDocumentId }
        ?: refreshable.firstOrNull()
    if (target == null) {
        onResult("no issuer-provisioned document to refresh (only fixtures?)")
        return
    }

    val issuer = engine.getIssuerReference(target.id)
    onResult(
        "refreshing ${target.name} (${target.id}, presented=${target.id == lastPresentedDocumentId}): " +
                "${target.credentialsCount}/${target.initialCredentialsCount} credentials, " +
                "${engine.credentialsNeeded(target.id)} needing replacement, " +
                "issuer=${issuer?.issuerId} config=${issuer?.documentConfigId}"
    )

    when (val progress = withTimeoutOrNull(RE_ISSUANCE_PROBE_TIMEOUT) {
        engine.refreshCredentials(target.id)
    }) {
        is IosIssuanceProgress.Issued -> {
            val after = engine.getAllDocumentsWithDetails(locale = "en").first { it.id == target.id }
            onResult(
                "refreshCredentials -> Issued; fetched ${progress.credentialsFetched} credential(s), " +
                        "counter ${target.credentialsCount} -> ${after.credentialsCount} " +
                        "(no browser, no consent). Fetching zero is the right answer for a batch that " +
                        "is still fresh: multipaz replaces only credentials that are used up or near " +
                        "expiry."
            )
        }

        is IosIssuanceProgress.Failure -> onResult("refreshCredentials -> Failure(${progress.message})")
        null -> onResult("refreshCredentials -> nothing within $RE_ISSUANCE_PROBE_TIMEOUT")
    }

    // And the other half: a document with nothing spent must not open a session at all. That guard is
    // the whole reason a refresh can be offered more than once — opening one costs the document's
    // rotating refresh token, and multipaz only writes the rotated token back when it fetched
    // something.
    val untouched = refreshable.firstOrNull { engine.credentialsNeeded(it.id) == 0 }
    if (untouched == null) {
        onResult("no fresh-batch document to check the no-op guard against")
        return
    }
    onResult(
        "guard: ${untouched.name} (${untouched.id}) needs " +
                "${engine.credentialsNeeded(untouched.id)} -> " +
                "${engine.refreshCredentials(untouched.id)}"
    )
}

/** A token request and a batch of credentials, over the network; no user step to wait for. */
private val RE_ISSUANCE_PROBE_TIMEOUT = 60.seconds

/** The PIN a simulator run ends up with, since nothing can type one in. */
private const val PROBE_PIN = "123456"
