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
import eu.europa.ec.shared.resources.StringCatalog
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import eu.europa.ec.shared.wallet.multipaz.spike.probeIssuerMetadata
import eu.europa.ec.shared.wallet.multipaz.spike.probeWalletProvider
import eu.europa.ec.shared.wallet.multipaz.spike.REVOCATION_FIXTURE_INDEX
import eu.europa.ec.shared.wallet.multipaz.spike.REVOCATION_FIXTURE_URI
import eu.europa.ec.shared.wallet.multipaz.spike.revocationFixtureToken
import eu.europa.ec.shared.wallet.multipaz.spike.seedIosRevocableFixture
import eu.europa.ec.shared.wallet.multipaz.spike.seedIosWalletFixture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
            probeIssuerMetadata("https://ec.dev.issuer.eudiw.dev", onResult)
            probeIssuerMetadata("https://dev.issuer-backend.eudiw.dev", onResult)
            probeWalletProvider(onResult = onResult)

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
