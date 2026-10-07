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

package eu.europa.ec.shared.wallet.multipaz

import eu.europa.ec.shared.wallet.trust.IosEtsiTrust
import eu.europa.ec.shared.wallet.trust.ReaderTrustSource
import eu.europa.ec.shared.wallet.trust.certChain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Simple
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.engagement.EngagementGenerator
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.transport.MdocTransportFactory
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.mdoc.transport.advertise
import org.multipaz.mdoc.transport.waitForConnection
import org.multipaz.presentment.ConsentData
import org.multipaz.presentment.CredentialQueryResult
import org.multipaz.presentment.CredentialSelection
import org.multipaz.presentment.PresentmentCanceledException
import org.multipaz.presentment.PresentmentCannotSatisfyRequestException
import org.multipaz.request.Requester
import org.multipaz.request.TrustedRequesterIdentity
import org.multipaz.util.Logger
import org.multipaz.util.UUID
import org.multipaz.util.toBase64Url
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Where a proximity presentation has got to, as the screens need to see it. */
sealed interface IosProximityState {

    data object Idle : IosProximityState

    /** Engagement is advertised; [qrPayload] is the `mdoc:` URI the reader scans. */
    data class Engaging(val qrPayload: String) : IosProximityState

    /** A reader has asked for something and the user has not answered yet. */
    data class Requesting(val request: IosPresentmentRequest) : IosProximityState

    data object Sending : IosProximityState

    /** The response went out. [sharedDocuments] names what was actually released. */
    data class Sent(val sharedDocuments: List<String>) : IosProximityState

    data class Failed(val message: String) : IosProximityState

    /**
     * The reader asked for nothing this wallet holds; multipaz has already ended the session.
     *
     * ⚠️ **Not a [Failed]**, for the reason [IosRemotePresentationState.NothingToShare] is not: the request
     * was understood and answered, and the honest reply is that there is nothing to show. The request
     * screen's `NoData` says so the way Android and the official iOS wallet do — the requested document is
     * not in this wallet — where [Failed] put a "something went wrong" heading and a Retry over it.
     */
    data object NothingToShare : IosProximityState

    /**
     * Blocked: the reader authenticated, and nothing this wallet trusts vouches for its certificate, or the
     * trust lists could not say ([isUntrustedReader]). The request was never shown and the session was
     * ended with no response. Android is as strict but shows consent and answers with status 10 instead.
     */
    data object VerifierNotTrusted : IosProximityState
}

/**
 * ISO 18013-5 proximity presentation on iOS: QR engagement, BLE, and the mdoc response.
 *
 * multipaz owns the protocol. [iosIso18013Presentment] (multipaz's `Iso18013Presentment`, copied so a request
 * is matched the way Android matches it) runs the exchange. What this adds is the two things multipaz
 * deliberately leaves to the app: which credentials may be offered, and a consent step that waits for a
 * *person* rather than answering itself. [state] is what a screen renders; [accept] and [decline] are what
 * a screen calls back.
 *
 * **The BLE half is written but unproven.** multipaz ships `BlePeripheralManagerIos`, so the transport is
 * there, but the iOS Simulator has no Bluetooth radio (nor NFC), and multipaz has no other mdoc transport —
 * so nothing between [startQrEngagement] and a connected reader can be exercised without a device and a
 * verifier. What *is* covered by tests is everything after the request arrives: matching, consent and the
 * response, which [iosMdocPresentment] performs with no transport involved. When a device is available, the
 * thing to watch is the connection, not the CBOR.
 */
class IosProximityPresenter internal constructor(
    private val walletEngine: IosWalletEngine,
    /** Where the wallet's own credentials live; anything else in the store is not offered. */
    private val credentialDomain: String,
    /**
     * Where the exchange runs. 🚩 **It must dispatch on the main thread.** multipaz's iOS BLE peripheral
     * takes CoreBluetooth's callbacks on the main queue and hands them to its sender through one
     * unsynchronized wait slot. Off the main thread, a "ready to write" callback can arrive before the
     * sender waits for it, and the transfer then stalls for good. Watched 2026-10-02: a 23.8 KB response
     * stopped after 25 of its 47 chunks, twice, while small responses never fill CoreBluetooth's queue.
     */
    internal val scope: CoroutineScope,
    /**
     * Who the verifier is. Null answers "unknown" without asking anyone — which is what a presentment
     * test wants, since a real check would make it pass or fail with the network.
     *
     * No default *here* on purpose: two constructors both accepting three arguments would be an
     * ambiguous overload, so the public one below is the single place the production value is chosen.
     */
    private val readerTrust: ReaderTrustSource?,
    /** Whether the user asked for registration certificates to be checked; read on every request. */
    private val isRegistrationCheckEnabled: suspend () -> Boolean,
) {

    /**
     * The constructor `:shared-ui` and Swift use.
     *
     * Reader trust is deliberately not a parameter of it: [IosEtsiTrust] hands back a multipaz
     * `TrustMetadata`, and this module's boundary is that nothing above it names a multipaz type. So
     * the primary constructor is `internal` and this one supplies the production value.
     */
    constructor(
        walletEngine: IosWalletEngine,
        /**
         * The user's registration-check setting. No default: the stored value lives in `:shared-ui`,
         * and a silent `false` here would switch the check off for any caller that forgot it.
         */
        isRegistrationCheckEnabled: suspend () -> Boolean,
        credentialDomain: String = MultipazWalletStore.DEFAULT_DOCUMENT_MANAGER_ID,
        scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
    ) : this(walletEngine, credentialDomain, scope, IosEtsiTrust(), isRegistrationCheckEnabled)

    private val mutableState = MutableStateFlow<IosProximityState>(IosProximityState.Idle)
    val state: StateFlow<IosProximityState> = mutableState.asStateFlow()

    private var presentmentJob: Job? = null
    private var transport: MdocTransport? = null
    private var pendingConsent: CompletableDeferred<CredentialSelection?>? = null

    /** The request being consented to, kept so [accept] can turn the app's answer back into matches. */
    private var pendingData: CredentialQueryResult? = null

    /** What the user agreed to share, remembered so the success state can name it. */
    private var sharedDocuments: List<String> = emptyList()

    /** The request being consented to, as the reader sent it — where its registration certificate is. */
    private var deviceRequest: DeviceRequest? = null

    /** The History row of the current exchange; see [IosPresentationLog]. */
    private var history: IosPresentationLog? = null

    /**
     * Advertises this wallet over BLE and publishes the QR the reader scans.
     *
     * Returns as soon as the QR is available; the exchange continues in the background and shows up in
     * [state].
     */
    suspend fun startQrEngagement() {
        cancel()

        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val connectionMethod = bleConnectionMethod()
        val engagement = deviceEngagement(eDeviceKey.publicKey, connectionMethod)

        try {
            // `advertise` is the extension on *connection methods*: it creates the transports and starts
            // them advertising in one step, which is also what keeps their states consistent.
            //
            // Bounded, because it does not fail when Bluetooth is unavailable — it waits. Observed on
            // the simulator, which has no radio: the call simply never returns, so the QR screen would
            // show its spinner for ever with nothing to explain it. The same wait is what a user who
            // denies the system Bluetooth prompt would get on a real phone. The timeout is long enough
            // to answer that prompt and short enough to end in a message instead of a hang.
            val transports = withTimeoutOrNull(ADVERTISE_TIMEOUT) {
                listOf(connectionMethod).advertise(
                    role = MdocRole.MDOC,
                    transportFactory = MdocTransportFactory.Default,
                    options = MdocTransportOptions(bleUseL2CAP = true),
                )
            }
            if (transports == null) {
                mutableState.value = IosProximityState.Failed(message = BLUETOOTH_UNAVAILABLE)
                return
            }

            mutableState.value = IosProximityState.Engaging(qrPayload = engagement.toQrPayload())

            presentmentJob = scope.launch {
                runPresentment(transports, eDeviceKey, engagement)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            fail(t)
        }
    }

    /**
     * Peripheral-server mode: the wallet advertises and the reader connects to it, which is the mode an
     * iOS app can actually offer — CoreBluetooth lets an app be a peripheral, and scanning as a central is
     * the reader's job anyway.
     */
    internal fun bleConnectionMethod() = MdocConnectionMethodBle(
        supportsPeripheralServerMode = true,
        supportsCentralClientMode = false,
        peripheralServerModeUuid = UUID.randomUUID(),
        centralClientModeUuid = null,
    )

    /** The device engagement a reader scans, as raw CBOR. `internal` so a test can parse it back. */
    internal fun deviceEngagement(
        eSenderKey: org.multipaz.crypto.EcPublicKey,
        connectionMethod: MdocConnectionMethodBle,
    ): ByteArray = EngagementGenerator(
        eSenderKey = eSenderKey,
        version = ENGAGEMENT_VERSION,
    ).addConnectionMethods(listOf(connectionMethod)).generate()

    /**
     * Answers the consent step with what the user chose to share.
     *
     * Releasing nothing — an empty list, or a list whose every claim was unchecked — is a refusal
     * rather than an empty response, which is also how multipaz reads a null selection.
     */
    fun accept(disclosures: List<IosPresentmentDisclosure>) {
        val selection = pendingData?.toSelection(disclosures)
        if (selection == null || selection.matches.isEmpty()) {
            decline()
            return
        }

        sharedDocuments = selection.matches.map { match ->
            match.credential.document.localizedName() ?: match.credential.document.identifier
        }.distinct()
        mutableState.value = IosProximityState.Sending
        pendingConsent?.complete(selection)
        pendingConsent = null
    }

    /** Answers the consent step with a refusal; the reader is told nothing was shared. */
    fun decline() {
        pendingConsent?.complete(null)
        pendingConsent = null
        pendingData = null
    }

    /** Stops advertising and closes any connection — the back button, and every terminal state. */
    fun cancel() {
        // Its own coroutine, as the exchange's is cancelled below; a row already ended is left as it is.
        history?.let { ending -> scope.launch { ending.stopped() } }
        history = null
        pendingConsent?.complete(null)
        pendingConsent = null
        pendingData = null
        deviceRequest = null
        presentmentJob?.cancel()
        presentmentJob = null
        scope.launch { runCatching { transport?.close() } }
        transport = null
        mutableState.value = IosProximityState.Idle
    }

    private suspend fun runPresentment(
        transports: List<MdocTransport>,
        eDeviceKey: EcPrivateKey,
        engagement: ByteArray,
    ) {
        val history = IosPresentationLog { walletEngine.store() }.also { this.history = it }
        try {
            val connected = transports.waitForConnection(eSenderKey = eDeviceKey.publicKey)
            transport = connected

            iosIso18013Presentment(
                transport = connected,
                eDeviceKey = eDeviceKey,
                deviceEngagement = ByteString(engagement).toDataItem(),
                handover = Simple.NULL,
                source = presentmentSource(walletEngine.store()),
                keyAgreementPossible = listOf(EcCurve.P256),
                timeout = PROXIMITY_REQUEST_TIMEOUT,
                onSendingResponse = { mutableState.value = IosProximityState.Sending },
                onDeviceRequest = { deviceRequest = it },
            )
            mutableState.value = IosProximityState.Sent(sharedDocuments = sharedDocuments)
            history.completed()
        } catch (e: CancellationException) {
            throw e
        } catch (canceled: PresentmentCanceledException) {
            mutableState.value = endedBy(canceled)
            history.stopped()
        } catch (unsatisfiable: PresentmentCannotSatisfyRequestException) {
            mutableState.value = endedBy(unsatisfiable)
            history.nothingToShare(requesterName = null, party = null)
        } catch (t: Throwable) {
            mutableState.value = endedBy(t)
            // An untrusted reader is refused before consent, so no request was shown and there is no row
            // for this to end.
            history.failed(t)
        }
    }

    /**
     * Where an exchange that did not send ends up. Three of multipaz's outcomes are answers rather than
     * errors, and the screens show them differently — learned from the presentment tests, not the docs.
     */
    internal fun endedBy(cause: Throwable): IosProximityState = when {
        // The user declined. Nothing was shared and nothing went wrong.
        cause is PresentmentCanceledException -> IosProximityState.Idle

        // An answer, not an error — see [IosProximityState.NothingToShare].
        cause is PresentmentCannotSatisfyRequestException -> IosProximityState.NothingToShare

        cause.isUntrustedVerifierRefusal() -> {
            Logger.w(TAG, "blocked: the reader's certificate is not trusted")
            IosProximityState.VerifierNotTrusted
        }

        else -> failure(cause)
    }

    /**
     * See [walletPresentmentSource], which holds every decision this shares with the other paths.
     * `internal`, and given the store, so a test can run it through [iosMdocPresentment] over a seeded store —
     * the half of the exchange that needs no transport.
     */
    internal suspend fun presentmentSource(store: MultipazWalletStore) = walletPresentmentSource(
        store = store,
        credentialDomain = credentialDomain,
        readerTrust = readerTrust,
        // ISO 18013-5 has no SD-JWT, so there is nothing to offer.
        offersSdJwt = false,
        showConsent = { requester, trustedRequesterIdentity, data ->
            awaitConsent(
                requester = requester,
                trustedRequesterIdentity = trustedRequesterIdentity,
                data = data,
            )
        },
    )

    /**
     * Publishes the request and suspends until a screen answers.
     *
     * This is the whole reason the app supplies a presentment source at all: multipaz's default answers
     * immediately, which would release documents without asking anyone.
     */
    private suspend fun awaitConsent(
        requester: Requester,
        trustedRequesterIdentity: TrustedRequesterIdentity?,
        data: ConsentData,
    ): CredentialSelection? {
        // Before the request is published, so a screen never shows it. multipaz ends the session on the
        // way out and builds no response.
        if (isUntrustedReader(requester, trustedRequesterIdentity)) throw UntrustedVerifierException()
        val registration = readerRegistrationOutcome(
            isRegistrationCheckEnabled = isRegistrationCheckEnabled,
            deviceRequest = deviceRequest,
            readerTrust = readerTrust,
            reader = requester.certChain?.certificates?.firstOrNull(),
        )

        val consent = CompletableDeferred<CredentialSelection?>()
        pendingConsent = consent
        pendingData = data.credentialQueryResult
        // A name without trust behind it is still worth showing — but only the trust decision marks it
        // verified, and over BLE there is usually neither.
        val name = trustedRequesterIdentity?.trustMetadata?.displayName ?: requester.appId
        history?.requestReceived(name, registration.partyRecord(), data.credentialQueryResult)
        mutableState.value = IosProximityState.Requesting(
            request = data.credentialQueryResult.toPresentmentRequest(
                requesterName = name,
                requesterIsTrusted = trustedRequesterIdentity != null,
                relyingPartyRegistration = registration,
            ),
        )

        // Bounded: a reader that is handed nothing eventually times out anyway, and leaving the BLE
        // connection open forever is worse than telling it no.
        val selection = withTimeoutOrNull(CONSENT_TIMEOUT) { consent.await() }
        if (selection != null) {
            history?.presented(selection)
            mutableState.value = IosProximityState.Sending
        }
        return selection
    }

    private fun fail(cause: Throwable) {
        mutableState.value = failure(cause)
    }

    private fun failure(cause: Throwable): IosProximityState.Failed {
        Logger.w(TAG, "proximity presentation failed: ${cause.message}")
        return IosProximityState.Failed(
            message = cause.message ?: cause::class.simpleName ?: "Sharing failed."
        )
    }

    private companion object {
        const val TAG = "IosProximityPresenter"

        /** ISO 18013-5 device engagement version, as multipaz's own samples use. */
        const val ENGAGEMENT_VERSION = "1.0"

        val CONSENT_TIMEOUT = 2.minutes

        /** Long enough for the user to answer iOS's Bluetooth prompt, short enough not to read as a hang. */
        val ADVERTISE_TIMEOUT = 20.seconds

        const val BLUETOOTH_UNAVAILABLE =
            "Could not start sharing over Bluetooth. Check that Bluetooth is on and that this app is " +
                    "allowed to use it."

    }
}

/**
 * How long a reader that has connected gets to send its request: multipaz's own default for that wait.
 *
 * It is also how long a reader that connects and then leaves keeps the QR on screen. multipaz's iOS BLE
 * peripheral does not handle `didUnsubscribeFrom`, CoreBluetooth's only sign that a central went away,
 * so nothing but this bound ends the wait. A reader sends its request as soon as it connects, so a longer
 * bound only makes that wait longer.
 */
internal val PROXIMITY_REQUEST_TIMEOUT = 15.seconds

/** The `mdoc:` URI a reader scans, per ISO 18013-5 §8.2.2.3. */
internal fun ByteArray.toQrPayload(): String = "mdoc:" + toBase64Url()

private fun ByteString.toDataItem(): DataItem =
    org.multipaz.cbor.Cbor.decode(this.toByteArray())
