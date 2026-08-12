package eu.europa.ec.shared.ui.spike

import eu.europa.ec.shared.wallet.WalletDocument
import eu.europa.ec.shared.wallet.WalletEngine
import eu.europa.ec.shared.wallet.multipaz.createIosWalletEngine
import eu.europa.ec.dashboardfeature.interactor.DocumentDetailsInteractor
import eu.europa.ec.dashboardfeature.interactor.DocumentDetailsInteractorPartialState
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

            onResult("revoked=${engine.getRevokedDocumentIds()} (revocation not wired up on iOS)")

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
