package eu.europa.ec.shared.ui.spike

import eu.europa.ec.shared.wallet.WalletEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
