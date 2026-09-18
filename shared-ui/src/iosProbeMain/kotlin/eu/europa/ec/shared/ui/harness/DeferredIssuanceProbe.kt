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

package eu.europa.ec.shared.ui.harness

import eu.europa.ec.dashboardfeature.interactor.DocumentInteractorRetryIssuingDeferredDocumentsPartialState
import eu.europa.ec.dashboardfeature.interactor.DocumentsInteractor
import eu.europa.ec.shared.wallet.WalletDocumentIssuanceState
import eu.europa.ec.shared.wallet.multipaz.DeferredCollection
import eu.europa.ec.shared.wallet.multipaz.IosCredentialIssuer
import eu.europa.ec.shared.wallet.multipaz.IosIssuanceProgress
import eu.europa.ec.shared.wallet.multipaz.IosIssuerCatalog
import eu.europa.ec.shared.wallet.multipaz.IosWalletEngine
import eu.europa.ec.shared.wallet.multipaz.collectDeferredDocument
import eu.europa.ec.shared.wallet.multipaz.deferredHandleOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform
import kotlin.time.Duration.Companion.seconds

/**
 * The whole deferred-issuance journey against a live issuer, end to end.
 *
 * Every piece of this flow is unit-tested against a `MockEngine`, and the DPoP half was proven live by
 * `--dpop-probe`. What none of that covers is the assembled thing meeting a real issuer: that the
 * issuer really answers `202` for a `*_deferred` configuration, that the document parks with its
 * pending credentials intact, and that the credential which eventually arrives certifies against the
 * keys multipaz made before the request.
 *
 * ```
 * xcrun simctl launch --console-pty <device> <bundle-id> --deferred-probe
 * python3 keycloak-login-script.py <log> <device-udid>
 * ```
 *
 * The login watcher answers the `AUTHORIZE-HERE` line, exactly as it does for `--wallet-probe`.
 *
 * ⚠️ It issues a **real document** against the dev issuer, into the real store. That is the point — a
 * fixture would prove nothing here — but it is why this is a probe and not something a launch does.
 */
fun probeDeferredIssuance(sdJwt: Boolean, onResult: (String) -> Unit) {
    CoroutineScope(Dispatchers.Main).launch {
        try {
            runDeferredIssuanceProbe(onResult, sdJwt)
        } catch (throwable: Throwable) {
            onResult("FAILED: ${throwable::class.simpleName}: ${throwable.message}")
        }
        onResult(PROBE_DONE)
    }
}

private suspend fun runDeferredIssuanceProbe(onResult: (String) -> Unit, sdJwt: Boolean) {
    val issuer = IosIssuerCatalog.issuers.last()
    // Which of the issuer's four `*_deferred` configurations to use. The mdoc PID twin by default;
    // `--deferred-probe sdjwt` picks the SD-JWT one, which takes the OTHER branch of the completer's
    // encoding — an mdoc arrives base64url and is stored decoded, an SD-JWT is stored as its compact
    // text — and which no live run had covered.
    val configurationId = if (sdJwt) {
        "eu.europa.ec.eudi.pid_vc_sd_jwt_deferred"
    } else {
        "eu.europa.ec.eudi.pid_mso_mdoc_deferred"
    }
    val engine = KoinPlatform.getKoin().get<IosWalletEngine>()

    onResult("--- deferred issuance: ${issuer.issuerUrl} / $configurationId ---")

    val issuing = IosCredentialIssuer(
        walletEngine = engine,
        // On its own line and in full, so the login script can pick it up.
        openAuthorizationUrl = { url -> onResult("AUTHORIZE-HERE $url") },
    )

    val documentId = coroutineScope {
        val redirects = launch { deliverRedirectFromFile(onResult) }
        val progress = issuing.issue(
            issuerId = issuer.issuerUrl,
            configurationIds = listOf(configurationId),
        ).first()
        redirects.cancel()
        when (progress) {
            is IosIssuanceProgress.Issued -> {
                onResult("issuance returned ${progress.documentIds} failures=${progress.failures}")
                progress.documentIds.firstOrNull()
            }

            is IosIssuanceProgress.Failure -> {
                // The interesting failure: before this work, a deferred issuance ended exactly here.
                onResult("ISSUANCE FAILED: ${progress.message}")
                null
            }
        }
    }
    if (documentId == null) return

    // Did it park? A deferred document is one with no credential and the issuer's handle.
    val parked = engine.getAllDocumentsWithDetails(locale = "en").firstOrNull { it.id == documentId }
    onResult(
        "parked: state=${parked?.issuanceState} credentials=${parked?.credentialsCount}" +
                " (expected Pending / 0)"
    )
    if (parked?.issuanceState != WalletDocumentIssuanceState.Pending) {
        onResult("NOT PARKED — the issuer did not defer, or the document was completed immediately")
        return
    }

    onResult("parked handle: ${engine.deferredHandleOf(documentId)}")

    // Polls the way a wallet would: on the issuer's own `interval`, a few times. This issuer answers
    // `interval: 48`, so a single impatient attempt proves nothing either way.
    // A plain loop, not `repeat`: `return@repeat` is `continue`, so a probe built on it would keep
    // polling after it had already collected the credential.
    var interval = FIRST_DELAY
    for (attempt in 1..MAX_POLLS) {
        val wait = interval
        onResult("waiting ${wait.inWholeSeconds}s, then poll $attempt of $MAX_POLLS")
        delay(wait)

        when (val collected = engine.collectDeferredDocument(documentId)) {
            is DeferredCollection.Issued -> {
                onResult("COLLECTED ${collected.credentials.size} credential(s)")
                break
            }

            is DeferredCollection.StillPending -> {
                onResult(
                    "still pending, interval=${collected.retryAfterSeconds}s " +
                            "handle now ${engine.deferredHandleOf(documentId)}"
                )
                interval = (collected.retryAfterSeconds ?: DEFAULT_INTERVAL_SECONDS).seconds
            }

            is DeferredCollection.Abandoned -> {
                onResult("ABANDONED: the issuer refused the handle"); break
            }

            is DeferredCollection.AuthorizationExpired -> {
                onResult("AUTHORIZATION EXPIRED"); break
            }

            is DeferredCollection.Unsupported -> {
                onResult("UNSUPPORTED: ${collected.reason}"); break
            }

            is DeferredCollection.Failed -> {
                onResult("FAILED: ${collected.reason}"); break
            }
        }
    }

    // Readable, not merely stored: certifying proves the bytes parsed far enough to yield a validity
    // window, which an encoding mistake could still survive. Claims are what the user actually sees.
    runCatching {
        val mdoc = engine.getNamespacedClaims(documentId)
        val sdJwt = engine.getJsonClaims(documentId)
        onResult("claims readable: mdoc=${mdoc.size} sd-jwt=${sdJwt.size}")
    }.onFailure { onResult("claims could NOT be read: ${it::class.simpleName}: ${it.message}") }

    // The verdict the UI would show.
    engine.getAllDocumentsWithDetails(locale = "en")
        .firstOrNull { it.id == documentId }
        ?.let {
            onResult(
                "after collection: ${it.name} state=${it.issuanceState} " +
                        "credentials=${it.credentialsCount} issuedAt=${it.issuedAt != null}"
            )
        }
}

/** Matches `MULTIPAZ-ENGINE: OK`'s role for the other probe: the line a watcher stops on. */
private const val PROBE_DONE = "OK"

/** Before the first poll. The issuer needs a moment even to register the transaction. */
private val FIRST_DELAY = 10.seconds

/** Used only if the issuer gives no `interval` of its own. */
private const val DEFAULT_INTERVAL_SECONDS = 15

/** Enough to outlast an `interval: 48` twice over without turning a probe into a vigil. */
private const val MAX_POLLS = 4

/**
 * Sweeps every parked document **through the app's own wiring**, as the documents screen does.
 *
 * The difference from [probeDeferredIssuance] matters more than it looks: that one calls
 * `collectDeferredDocument` on the engine directly, which proves the protocol but skips **Koin, the
 * platform bridge and the interactor** — the exact layer that changed when the bridge gained its
 * `collectDeferred` and `documentName` parameters. A DI graph that cannot construct the bridge would
 * leave every unit test passing and the screen broken.
 *
 * What it still cannot reach is Compose itself: the screen's own trigger and the "ready" sheet. That
 * needs a tap, and this machine has no `Simulator.app` to send one to (Xcode 27 removed it).
 *
 * ```
 * xcrun simctl launch --console-pty <device> <bundle-id> --deferred-sweep
 * ```
 */
fun probeDeferredSweep(onResult: (String) -> Unit) {
    CoroutineScope(Dispatchers.Main).launch {
        try {
            val engine = KoinPlatform.getKoin().get<IosWalletEngine>()
            val pending = engine.getAllDocumentsWithDetails(locale = "en")
                .filter { it.issuanceState == WalletDocumentIssuanceState.Pending }
            onResult("parked documents: ${pending.map { it.id }}")
            if (pending.isEmpty()) {
                onResult("nothing to sweep — park one with --deferred-probe and kill it before it polls")
                onResult(PROBE_DONE)
                return@launch
            }

            // Resolved from Koin exactly as the documents screen's view-model gets it.
            val interactor = KoinPlatform.getKoin().get<DocumentsInteractor>()
            onResult("interactor resolved from Koin: ${interactor::class.simpleName}")

            val deferred = pending.associate { it.id to it.formatType }
            interactor.tryIssuingDeferredDocumentsFlow(deferred).collect { state ->
                when (state) {
                    is DocumentInteractorRetryIssuingDeferredDocumentsPartialState.Result -> onResult(
                        // Quoted and counted: a single blank name prints as `[]`, exactly like an
                        // empty list, which is how a missing-name bug hid here on 2026-09-17.
                        "sweep result: issued=${state.successfullyIssuedDeferredDocuments.size} " +
                                state.successfullyIssuedDeferredDocuments.joinToString { "'${it.docName}'" } + " " +
                                "failed=${state.failedIssuedDeferredDocuments} " +
                                "retryAfter=${state.retryAfterSeconds}s"
                    )

                    is DocumentInteractorRetryIssuingDeferredDocumentsPartialState.Failure ->
                        onResult("sweep FAILED: ${state.errorMessage}")
                }
            }

            engine.getAllDocumentsWithDetails(locale = "en")
                .filter { it.id in deferred.keys }
                .forEach {
                    onResult(
                        "after sweep: ${it.name} state=${it.issuanceState} " +
                                "credentials=${it.credentialsCount}"
                    )
                }
        } catch (throwable: Throwable) {
            onResult("FAILED: ${throwable::class.simpleName}: ${throwable.message}")
        }
        onResult(PROBE_DONE)
    }
}
