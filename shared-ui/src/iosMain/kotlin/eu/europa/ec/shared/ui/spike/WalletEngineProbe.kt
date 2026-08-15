package eu.europa.ec.shared.ui.spike

import eu.europa.ec.shared.wallet.WalletDocument
import eu.europa.ec.shared.wallet.WalletEngine
import eu.europa.ec.shared.wallet.multipaz.IosWalletEngine
import eu.europa.ec.shared.wallet.multipaz.createIosWalletEngine
import eu.europa.ec.dashboardfeature.interactor.DashboardInteractor
import eu.europa.ec.dashboardfeature.interactor.DocumentDetailsInteractor
import eu.europa.ec.dashboardfeature.interactor.DocumentDetailsInteractorPartialState
import eu.europa.ec.dashboardfeature.interactor.DocumentsPlatformBridge
import eu.europa.ec.dashboardfeature.interactor.SettingsInteractor
import eu.europa.ec.dashboardfeature.interactor.TransactionInteractorGetTransactionsPartialState
import eu.europa.ec.dashboardfeature.interactor.TransactionsInteractor
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
import eu.europa.ec.shared.wallet.multipaz.spike.REVOCATION_FIXTURE_INDEX
import eu.europa.ec.shared.wallet.multipaz.spike.REVOCATION_FIXTURE_URI
import eu.europa.ec.shared.wallet.multipaz.spike.revocationFixtureToken
import eu.europa.ec.shared.wallet.multipaz.spike.seedIosRevocableFixture
import eu.europa.ec.shared.wallet.multipaz.spike.seedIosWalletFixture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform

/**
 * SPIKE: calls a [WalletEngine] from Kotlin coroutine code, so a Swift implementation can be driven
 * through the same suspend surface the shared view-models use.
 */
fun probeWalletEngine(engine: WalletEngine, onResult: (String) -> Unit) {
    CoroutineScope(Dispatchers.Main).launch {
        try {
            val cheap = engine.getAllDocuments()
            val detailed = engine.getAllDocumentsWithDetails(locale = "en")
            val revoked = engine.isDocumentRevoked(documentId = "swift-1")
            onResult(
                "cheap=${cheap.size} detailed=${detailed.size} " +
                        "name=${detailed.firstOrNull()?.name} " +
                        "credentials=${detailed.firstOrNull()?.credentialsCount} revoked=$revoked"
            )
        } catch (t: Throwable) {
            onResult("FAILED: ${t::class.simpleName}: ${t.message}")
        }
    }
}

/**
 * Console probe for the **real** iOS `WalletEngine` — the Kotlin-over-multipaz document layer.
 *
 * There is no other way to see it work yet: iOS cannot issue a document (the OpenID4VCI library is
 * JVM-only) and the Documents screen is not shared, so this seeds a fixture document straight into
 * multipaz's store and then reads it back through the engine, reporting each line to [onResult].
 *
 * Retire it once iOS can issue and the Documents screen renders there — at that point the screen is
 * the better probe.
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

            // iOS writes no transaction log (nothing issues, presents or signs there yet), so an
            // *empty* success is the honest answer rather than a stub — what matters is that the
            // History tab's interactor runs at all.
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

            // SPIKE: OpenID4VCI issuer metadata through multipaz, from iOS.
            onResult("--- issuance spike: reading issuer metadata ---")
            probeAuthentication(onResult)
            probeIssuance(onResult)
            probeCredentialOffer(onResult)

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
}

/** The PIN a simulator run ends up with, since nothing can type one in. */
private const val PROBE_PIN = "123456"
