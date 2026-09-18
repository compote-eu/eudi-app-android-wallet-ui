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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Simple
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodNfc
import org.multipaz.mdoc.engagement.EngagementGenerator
import org.multipaz.mdoc.nfc.MdocNfcEngagementHelper
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.transport.MdocTransportFactory
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.mdoc.transport.NfcTransportMdoc
import org.multipaz.mdoc.transport.advertise
import org.multipaz.mdoc.transport.waitForConnection
import org.multipaz.presentment.CredentialPresentmentData
import org.multipaz.presentment.CredentialPresentmentSelection
import org.multipaz.presentment.Iso18013Presentment
import org.multipaz.presentment.PresentmentCanceledException
import org.multipaz.presentment.PresentmentCannotSatisfyRequestException
import org.multipaz.request.Requester
import org.multipaz.trustmanagement.TrustMetadata
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
}

/**
 * ISO 18013-5 proximity presentation on iOS: QR engagement, cold-tap NFC engagement (Annex C), BLE
 * and NFC data retrieval, and the mdoc response.
 *
 * multipaz owns the protocol — `Iso18013Presentment` runs the exchange and `SimplePresentmentSource`
 * matches the reader's request against the wallet's documents. What this adds is the two things multipaz
 * deliberately leaves to the app: which credentials may be offered, and a consent step that waits for a
 * *person* rather than answering itself. [state] is what a screen renders; [accept] and [decline] are what
 * a screen calls back.
 *
 * There are two independent ways an exchange can start, each with its own `eDeviceKey`/`DeviceEngagement`
 * (never shared — see `wiki/IOS_NFC_PLAN.md` §9), both ending up in the same [runPresentment] since
 * multipaz's [waitForConnection] is connection-method-agnostic — it takes whichever transport the reader
 * actually connects over:
 *
 * - **QR** ([startQrEngagement]): builds `DeviceEngagement` up front and advertises BLE only, through
 *   multipaz's [advertise] extension against `MdocTransportFactory.Default`. No NFC connection method is
 *   offered here any more — see [armColdTapEngagement] for where that moved and why.
 * - **Cold NFC tap** ([onScreenEntered]/[armColdTapEngagement], ISO 18013-5 Annex C): armed for as long as
 *   the "Share over NFC" switch ([nfcEngagementEnabled]) is on and the proximity screen is open,
 *   independent of whether QR engagement has started, restarted, or is even showing. A tap selects the
 *   NDEF AID first; `MdocNfcEngagementHelper` (routed there by `IosNfcHceTransport.ApduDelegate`) builds
 *   its own `DeviceEngagement`/handover, and the *same physical tap* then continues straight into the
 *   mdoc AID for data transfer — [onColdTapHandoverComplete] wires that continuation into
 *   [runPresentment]. multipaz's own [NfcTransportMdoc] is constructed fresh only once a tap actually
 *   completes handover, never pre-armed the way an earlier version of this class did for QR: at most one
 *   instance is ever registered at a time, which matters because Multipaz's own `NfcTransportMdoc`
 *   dispatch (`instances`) has no way to arbitrate between two.
 *
 * **The BLE half is written but unproven, and so is NFC's.** multipaz ships `BlePeripheralManagerIos`,
 * so the transport is there, but the iOS Simulator has no Bluetooth radio (nor NFC), and multipaz has
 * no other mdoc transport — so nothing between engagement and a connected reader can be exercised
 * without a device and a verifier. What *is* covered by tests is everything after the request
 * arrives: matching, consent and the response, which `mdocPresentment` performs with no transport
 * involved. When a device is available, the thing to watch is the connection, not the CBOR.
 */
class IosProximityPresenter internal constructor(
    private val walletEngine: IosWalletEngine,
    /** Where the wallet's own credentials live; anything else in the store is not offered. */
    private val credentialDomain: String,
    private val scope: CoroutineScope,
    /**
     * Who the verifier is. Null answers "unknown" without asking anyone — which is what a presentment
     * test wants, since a real check would make it pass or fail with the network.
     *
     * No default *here* on purpose: two constructors both accepting three arguments would be an
     * ambiguous overload, so the public one below is the single place the production value is chosen.
     */
    private val readerTrust: ReaderTrustSource?,
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
        credentialDomain: String = MultipazWalletStore.DEFAULT_DOCUMENT_MANAGER_ID,
        scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    ) : this(walletEngine, credentialDomain, scope, IosEtsiTrust())

    /** `internal` so a test can drive [isPresentmentActuallyInProgress] without a real connection. */
    internal val mutableState = MutableStateFlow<IosProximityState>(IosProximityState.Idle)
    val state: StateFlow<IosProximityState> = mutableState.asStateFlow()

    /**
     * One-shot, human-readable notices about NFC specifically — not modeled as an [IosProximityState]
     * because a failed NFC start is not a failed *presentation*: QR/BLE engagement is independent of
     * cold-tap arming (see [armColdTapEngagement]) and keeps working regardless, so there is nothing
     * here for [state] itself to fail on. Finding A/B of the current-state audit: before this, a failed
     * [nfcTransport] start reached only [org.multipaz.util.Logger.w] — nothing surfaced to a screen at
     * all.
     *
     * `extraBufferCapacity = 1` rather than `replay`: a subscriber that starts collecting after a
     * notice fires should not see it (it is stale by then), but a slow one should not lose it either
     * — [IosProximityCoordinator.qrEvents] subscribes before [startQrEngagement] runs specifically so
     * a real reader of this never has to be the slow case.
     */
    private val mutableNfcNotice = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val nfcNotice: SharedFlow<String> = mutableNfcNotice.asSharedFlow()

    /**
     * Owns CoreNFC's `CardSession` for as long as cold-tap (Annex C) engagement is armed — entirely
     * independent of QR engagement. Started/stopped from [armColdTapEngagement]/[disarmColdTapEngagement],
     * themselves called from [onScreenEntered]/[onScreenExited] — not tied to [startQrEngagement], which
     * no longer offers NFC as a connection method at all; see this class's own doc comment and
     * `wiki/IOS_NFC_PLAN.md` §9.
     */
    private val nfcTransport = IosNfcHceTransport()

    /**
     * Whether [nfcTransport] currently has a successfully-started [MdocNfcEngagementHelper] armed.
     * Deliberately not inferred from `nfcTransport.engagementHelper != null` alone — see
     * [armColdTapEngagement]'s own doc comment for why that would break retrying after a failed start.
     */
    private var engagementHelperArmed = false

    /**
     * Whether cold-tap (Annex C) NFC engagement is armed for as long as the proximity screen is open —
     * the "Share over NFC" switch. Defaults to `false` — deliberately: flipping it to `true` makes
     * CoreNFC's `CardSession` start, so it stays inert until something real opts it in. See
     * [onScreenEntered]/[armColdTapEngagement] for how this is reconciled on every engagement (re)start,
     * and `wiki/IOS_NFC_PLAN.md` §9 for why this used to gate a different feature — NFC as a transport
     * alongside QR-established BLE, retired in favor of this one.
     */
    private var nfcEngagementEnabled = false

    private var presentmentJob: Job? = null
    private var transport: MdocTransport? = null
    private var pendingConsent: CompletableDeferred<CredentialPresentmentSelection?>? = null

    /** The request being consented to, kept so [accept] can turn the app's answer back into matches. */
    private var pendingData: CredentialPresentmentData? = null

    /** What the user agreed to share, remembered so the success state can name it. */
    private var sharedDocuments: List<String> = emptyList()

    /**
     * Advertises this wallet over BLE and publishes the QR the reader scans.
     *
     * Returns as soon as the QR is available; the exchange continues in the background and shows up in
     * [state].
     */
    suspend fun startQrEngagement() {
        cancel()

        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)

        try {
            // `advertise` is the extension on *connection methods*: it creates the transports and starts
            // them advertising in one step, which is also what keeps their states consistent. Only BLE
            // goes through it — see this class's own doc comment for why NFC is deliberately not in this
            // same list/call.
            //
            // Bounded, because it does not fail when Bluetooth is unavailable — it waits. Observed on
            // the simulator, which has no radio: the call simply never returns, so the QR screen would
            // show its spinner for ever with nothing to explain it. The same wait is what a user who
            // denies the system Bluetooth prompt would get on a real phone. The timeout is long enough
            // to answer that prompt and short enough to end in a message instead of a hang.
            val bleTransports = withTimeoutOrNull(ADVERTISE_TIMEOUT) {
                listOf(bleConnectionMethod()).advertise(
                    role = MdocRole.MDOC,
                    transportFactory = MdocTransportFactory.Default,
                    options = MdocTransportOptions(bleUseL2CAP = true),
                )
            }
            if (bleTransports == null) {
                mutableState.value = IosProximityState.Failed(message = BLUETOOTH_UNAVAILABLE)
                return
            }

            // BLE only — NFC is no longer offered as a connection method here at all; see this class's
            // own doc comment for where NFC engagement/transport now lives (cold-tap, Annex C).
            val transports = bleTransports
            val connectionMethods = transports.map { it.connectionMethod }
            val engagement = deviceEngagement(eDeviceKey.publicKey, connectionMethods)

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

    /**
     * ISO 18013-5 Annex 8 NFC data retrieval. The field-length values are multipaz's own sample-app
     * defaults (`samples/testapp`'s `IsoMdocProximitySharingScreen.kt`), not something this app measured
     * independently — there is exactly one `MdocConnectionMethodNfc` shape multipaz's own code
     * demonstrates, and this is it.
     *
     * `MdocConnectionMethodNfcV2` does not exist in the multipaz version this project pins (`0.99.0`,
     * verified against that exact tag) — it is a later addition upstream. Only the V1 shape is used here.
     */
    internal fun nfcConnectionMethod() = MdocConnectionMethodNfc(
        commandDataFieldMaxLength = 0xffff,
        responseDataFieldMaxLength = 0x10000,
    )

    /** The device engagement a reader scans, as raw CBOR. `internal` so a test can parse it back. */
    internal fun deviceEngagement(
        eSenderKey: org.multipaz.crypto.EcPublicKey,
        connectionMethods: List<MdocConnectionMethod>,
    ): ByteArray = EngagementGenerator(
        eSenderKey = eSenderKey,
        version = ENGAGEMENT_VERSION,
    ).addConnectionMethods(connectionMethods).generate()

    /**
     * Whether cold-tap engagement is armed the next time engagement (re)starts. That is sooner than it
     * sounds: [IosProximityCoordinator.qrEvents] calls [onScreenEntered] on every restart within the
     * current screen visit too, including the one `ProximityQRViewModel.restartEngagementForNfcToggle`
     * triggers right after this is called — so flipping the switch reconciles immediately, not only on
     * the next screen visit.
     */
    fun setNfcEngagementEnabled(enabled: Boolean) {
        nfcEngagementEnabled = enabled
    }

    /**
     * The current value [setNfcEngagementEnabled] last set. Finding E of the current-state audit:
     * this flag lives here, on a Koin `@Single` that outlives any one screen visit, but had no
     * reader — a fresh [eu.europa.ec.proximityfeature.ui.qr.ProximityQRViewModel.State] on
     * re-entering the QR screen always started the switch back at its own default, out of sync
     * with what [startQrEngagement] would actually advertise next.
     */
    fun isNfcEngagementEnabled(): Boolean = nfcEngagementEnabled

    /**
     * Whether this device can ever start NFC card emulation at all — device/OS support only, not
     * whether the *next* [startQrEngagement] will actually succeed at it (Apple's own async,
     * region-gated `CardSession.isEligible` check, and the HCE entitlement's own approval state,
     * are answered only by actually trying; see [nfcNotice]). Finding D of the current-state audit:
     * this used to be a hard-coded `true` one layer up, in `IosProximityQRInteractor`.
     */
    fun isNfcDataRetrievalSupported(): Boolean = IosNfcHceTransport.isSupported()

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
            match.credential.document.displayName ?: match.credential.document.identifier
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
        pendingConsent?.complete(null)
        pendingConsent = null
        pendingData = null
        presentmentJob?.cancel()
        presentmentJob = null
        scope.launch { runCatching { transport?.close() } }
        transport = null
        mutableState.value = IosProximityState.Idle
    }

    /**
     * Reconciles cold-tap (Annex C) engagement with the current "Share over NFC" switch
     * ([nfcEngagementEnabled]). Idempotent and safe to call every time engagement (re)starts within one
     * screen visit — not just once — since [IosProximityCoordinator.qrEvents] calls this on every
     * [startQrEngagement] (re)start, including the one `ProximityQRViewModel.restartEngagementForNfcToggle`
     * triggers when the switch itself changes mid-visit. `wiki/IOS_NFC_PLAN.md` §9 Stage 3.
     */
    suspend fun onScreenEntered() {
        Logger.i(TAG, "onScreenEntered: nfcEngagementEnabled=$nfcEngagementEnabled")
        if (nfcEngagementEnabled) armColdTapEngagement() else disarmColdTapEngagement()
    }

    /**
     * Unconditional teardown of cold-tap engagement — the whole proximity exchange for this screen visit
     * is over. Called from [IosProximityCoordinator.cancel] alongside [cancel] itself, but deliberately
     * kept out of [cancel]: that one also runs on every [startQrEngagement] retry within the same visit,
     * and cold-tap listening must survive a QR retry, not just a screen exit.
     */
    fun onScreenExited() {
        Logger.i(TAG, "onScreenExited")
        disarmColdTapEngagement()
    }

    /**
     * Whether a real mdoc exchange — a reader actually connected, a request received or a response
     * being prepared — is genuinely underway right now, regardless of which origin (QR or cold-tap)
     * started it. Deliberately narrower than "[presentmentJob] exists and hasn't finished": that job is
     * assigned the instant [startQrEngagement] launches it and stays active for the entire time QR is
     * displayed with no reader connected yet (`waitForConnection` has no timeout), which is not a real
     * exchange to protect against racing — only [IosProximityState.Requesting]/[IosProximityState.Sending]
     * are. A real device log confirmed the coarser, job-liveness check blocked cold-tap arming on
     * essentially every attempt, not just the actual race case; see `wiki/IOS_NFC_PLAN.md` §9.
     *
     * `internal`, not `private`: driving [armColdTapEngagement]/[onColdTapHandoverComplete] end to end
     * on the Simulator can't distinguish "skipped here" from "proceeded, then stopped at the next guard
     * down" (no NFC hardware either way) — this is the actual piece of logic the fix changed, and a
     * test needs to reach it directly, with [mutableState] set without a real connection.
     */
    internal fun isPresentmentActuallyInProgress(): Boolean =
        mutableState.value is IosProximityState.Requesting || mutableState.value is IosProximityState.Sending

    /**
     * Arms cold-tap (Annex C) engagement: constructs a fresh [MdocNfcEngagementHelper] — a new
     * `eDeviceKey`, independent of any QR engagement's own — wires it into [nfcTransport], and starts
     * CoreNFC card emulation. A no-op if already armed, so repeated [onScreenEntered] calls within one
     * visit don't reconstruct the helper or restart `CardSession` needlessly.
     *
     * [engagementHelperArmed] guards this, not `nfcTransport.engagementHelper != null`: a failed
     * [IosNfcHceTransport.start] clears both back out (see the `else` branch below) specifically so a
     * later [onScreenEntered] call — the next retry, or the switch toggled off and back on — gets to try
     * again, rather than being silently stuck believing it's already armed.
     *
     * Also skipped while a presentment is actually in progress ([isPresentmentActuallyInProgress]),
     * same guard as [onColdTapHandoverComplete] uses and for the same reason: restarting `CardSession`
     * while it's already carrying an active mdoc conversation — QR-originated or cold-tap's own — would
     * disrupt it. Found during real-device testing of the fix below: clearing [engagementHelperArmed]
     * there means a later [onScreenEntered] (e.g. a QR retry) would otherwise try to re-arm mid-conversation.
     *
     * **Real-device finding, corrected**: this guard originally checked `presentmentJob?.isActive`
     * instead — coroutine liveness, not actual exchange progress. [presentmentJob] is assigned the
     * instant [startQrEngagement] launches it, and `waitForConnection` inside it has no timeout, so it
     * stays "active" for the entire time QR is on screen with nothing connected yet — which is most of
     * a typical cold-tap visit. A real device log confirmed this blocked NFC arming on essentially
     * every attempt, not just the intended race case; see `wiki/IOS_NFC_PLAN.md` §9.
     *
     * Static handover, not negotiated: cold-tap only ever has one connection method to offer (the NFC
     * link the tap itself is), and negotiated handover exists to let the reader choose among several —
     * doesn't apply here. See `wiki/IOS_NFC_PLAN.md` §9 for the full comparison.
     */
    private suspend fun armColdTapEngagement() {
        if (engagementHelperArmed) {
            Logger.i(TAG, "armColdTapEngagement: already armed, no-op")
            return
        }
        if (isPresentmentActuallyInProgress()) {
            Logger.i(TAG, "armColdTapEngagement: a presentment is already in progress, skipping")
            return
        }
        if (!IosNfcHceTransport.isSupported()) {
            Logger.i(TAG, "armColdTapEngagement: NFC HCE not supported on this device, skipping")
            return
        }

        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
        nfcTransport.engagementHelper = MdocNfcEngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            staticHandoverMethods = listOf(nfcConnectionMethod()),
            onHandoverComplete = { connectionMethods, encodedDeviceEngagement, handover ->
                Logger.i(
                    TAG,
                    "onHandoverComplete: cold-tap handover completed with " +
                        "${connectionMethods.size} connection method(s)",
                )
                onColdTapHandoverComplete(eDeviceKey, connectionMethods, encodedDeviceEngagement, handover)
            },
            onError = { error -> Logger.w(TAG, "onError: cold-tap engagement failed: ${error.message}") },
        )
        // Critical timing marker: this is what triggers NfcHceBridge.swift's startCardSession(), which
        // logs the exact moment it calls NFCPresentmentIntentAssertion.acquire() — see that method's
        // own doc comment. Compare this line's (Logger-supplied) timestamp against the physical tap's
        // wall-clock time to check whether the assertion's 15-second window had already expired.
        Logger.i(TAG, "armColdTapEngagement: requesting CoreNFC start (NFCPresentmentIntentAssertion + CardSession)")
        nfcTransport.start { result ->
            if (result == NfcStartResult.Started) {
                engagementHelperArmed = true
                Logger.i(TAG, "armColdTapEngagement: CoreNFC started, cold-tap engagement armed")
            } else {
                nfcTransport.engagementHelper = null
                nfcTransport.ndefHandoverCompleted = false
                Logger.w(TAG, "armColdTapEngagement: NFC card emulation did not start: $result")
                mutableNfcNotice.tryEmit(nfcStartFailureMessage(result))
            }
        }
    }

    /**
     * Stops routing NDEF-AID APDUs to the current engagement helper entirely, without touching
     * `CardSession` itself — safe to call whether or not a mdoc-AID continuation over the same physical
     * tap is about to follow. Full teardown: [disarmColdTapEngagement] (screen exit / switch off) is the
     * only caller — [onColdTapHandoverComplete] no longer calls this on a successful handover; see its
     * own doc comment for why a full clear there was too broad and what replaced it.
     */
    private fun clearEngagementHelper() {
        Logger.i(TAG, "clearEngagementHelper: no longer routing NDEF-AID APDUs to the engagement helper")
        engagementHelperArmed = false
        nfcTransport.engagementHelper = null
        nfcTransport.ndefHandoverCompleted = false
    }

    /** Reverses [armColdTapEngagement]; safe to call whether or not anything was ever armed. */
    private fun disarmColdTapEngagement() {
        clearEngagementHelper()
        Logger.i(TAG, "disarmColdTapEngagement: stopping CoreNFC")
        nfcTransport.stop()
    }

    /**
     * Cold-tap engagement completed: the reader now has `DeviceEngagement`/handover for a connection
     * method this same physical tap continues into. Builds the one [NfcTransportMdoc] this exchange uses
     * — [connectionMethods] holds exactly one entry, since [armColdTapEngagement] only ever offers one via
     * static handover — and feeds it into the same [runPresentment] QR already uses, just with cold-tap's
     * own key and a real `handover` CBOR instead of QR's [Simple.NULL].
     *
     * **`nfcTransport.ndefHandoverCompleted = true` first, unconditionally, before anything else** —
     * found via real-device testing, not anticipated at design time, and revised once more after a
     * *second* round of real-device testing. [MdocNfcEngagementHelper] has no protection against being
     * called again via a repeat `SELECT` (application or file) after it has already completed a
     * handover: nothing in its own state machine remembers "`onHandoverComplete` already fired," so a
     * reader that re-selects the NDEF file again (observed on real hardware) re-runs its handover
     * construction logic from scratch and crashes on an assumption that only held the first time. This
     * is not a bug in that class so much as an assumption baked into its design: on Android,
     * `MdocNfcEngagementHelper` is only ever reached from `MdocNdefService`, a `HostApduService` bound
     * to the NDEF AID alone — the OS's own AID-based routing between separate services is what
     * guarantees a reader that moves on to the mdoc AID never reaches this instance again, for free.
     * iOS has no such OS-level router: `ApduDelegate` does that routing itself
     * (`wiki/IOS_NFC_PLAN.md` §9 Stage 2), so *we* are the ones who have to provide the guarantee
     * Android gets from its platform.
     *
     * **This used to call [clearEngagementHelper] here instead — found to be too broad, via a second
     * real-device test.** A real reader legitimately sends one or more trailing `READ_BINARY`s on the
     * NDEF file after handover completes, before it notices engagement is done and switches AIDs itself
     * (plausibly Android's own `NfcAdapter.enableReaderMode()` performing its own OS-level NDEF check
     * before invoking the app's callback — not something to "fix" on the reader's side, since serving
     * valid content for a still-selected file is the correct Type 4 Tag behavior regardless of why the
     * read happened). [MdocNfcEngagementHelper]'s own `processReadBinary` is a pure, side-effect-free
     * function of already-built state — safe to keep answering no matter how many times it's called —
     * so clearing the whole helper rejected a request it would have answered correctly. Setting
     * [IosNfcHceTransport.ndefHandoverCompleted] instead keeps the helper wired for exactly those safe
     * reads, while `ApduDelegate`'s own gate (see its doc comment on `ndefHandoverCompletedProvider`)
     * blocks only the dangerous repeat-`SELECT` case — the actual crash trigger, not the whole AID.
     * Nothing needs to explicitly stop routing to this helper once the reader *does* move on: a `SELECT`
     * for the mdoc AID is checked unconditionally by `ApduDelegate`, regardless of `selectedApplication`'s
     * current value, so it reaches `NfcTransportMdoc` and this helper simply stops being reachable.
     *
     * Guards against a real race rather than assuming it away: QR and cold-tap engagement are
     * simultaneously armed by design (`wiki/IOS_NFC_PLAN.md` §9), so a tap can complete while a QR-
     * originated exchange is already actually in progress ([isPresentmentActuallyInProgress]). Without
     * this check, [presentmentJob] would be silently overwritten — leaking the first coroutine and
     * racing two [runPresentment] calls over the same single-instance state ([transport],
     * [pendingConsent], [pendingData], [sharedDocuments]). First engagement to actually reach here wins;
     * the loser is dropped, not cancelled — multipaz's own `NfcTransportMdoc` was already built by the
     * time this runs, so there is nothing to un-build.
     *
     * **Real-device finding, corrected** — see [armColdTapEngagement]'s own doc comment for the same
     * fix and why `presentmentJob?.isActive` was the wrong check here too: QR merely advertising and
     * waiting for a connection (`IosProximityState.Engaging`) is not a real exchange to protect against
     * racing, only `Requesting`/`Sending` are.
     */
    private fun onColdTapHandoverComplete(
        eDeviceKey: EcPrivateKey,
        connectionMethods: List<MdocConnectionMethod>,
        encodedDeviceEngagement: ByteString,
        handover: DataItem,
    ) {
        Logger.i(TAG, "onColdTapHandoverComplete: handover done, NDEF AID stays answerable for trailing reads")
        nfcTransport.ndefHandoverCompleted = true

        if (isPresentmentActuallyInProgress()) {
            Logger.w(TAG, "onColdTapHandoverComplete: a presentment is already in progress; ignoring")
            return
        }

        Logger.i(TAG, "onColdTapHandoverComplete: accepted, starting presentment over the mdoc AID")
        val transport = NfcTransportMdoc(
            role = MdocRole.MDOC,
            options = MdocTransportOptions(),
            connectionMethod = connectionMethods.first(),
        )
        presentmentJob = scope.launch {
            runPresentment(
                transports = listOf(transport),
                eDeviceKey = eDeviceKey,
                engagement = encodedDeviceEngagement.toByteArray(),
                handover = handover,
            )
        }
    }

    private suspend fun runPresentment(
        transports: List<MdocTransport>,
        eDeviceKey: EcPrivateKey,
        engagement: ByteArray,
        handover: DataItem = Simple.NULL,
    ) {
        try {
            // [waitForConnection] calls [MdocTransport.open] on every transport in [transports] — for the
            // [NfcTransportMdoc] case (only ever reached via [onColdTapHandoverComplete] now, never from
            // QR's own call site), a synchronous, mutex-guarded `instances.add(this)` (see
            // NfcTransportMdoc.open's own source) with no real I/O. CoreNFC's CardSession is trivially
            // already active whenever this runs for the cold-tap case — the reader's own tap is what
            // triggered [onColdTapHandoverComplete] in the first place — so this registration into
            // NfcTransportMdoc's static `instances` list (what IosNfcHceTransport's
            // processCommandApdu/onDeactivated calls dispatch against) is guaranteed to finish before the
            // reader's next APDU (the mdoc-AID SELECT continuing the same tap) can arrive; see
            // wiki/IOS_NFC_PLAN.md §3.3/§9.
            val connected = transports.waitForConnection(eSenderKey = eDeviceKey.publicKey)
            transport = connected

            Iso18013Presentment(
                transport = connected,
                eDeviceKey = eDeviceKey,
                deviceEngagement = ByteString(engagement).toDataItem(),
                handover = handover,
                source = presentmentSource(),
                keyAgreementPossible = listOf(EcCurve.P256),
                timeout = ENGAGEMENT_TIMEOUT,
                onSendingResponse = { mutableState.value = IosProximityState.Sending },
            )
            mutableState.value = IosProximityState.Sent(sharedDocuments = sharedDocuments)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Two of multipaz's outcomes are answers rather than errors, and the screens show them
            // differently — both learned from the presentment tests rather than from the docs.
            when (t) {
                is PresentmentCanceledException -> {
                    // The user declined. Nothing was shared and nothing went wrong.
                    mutableState.value = IosProximityState.Idle
                }

                is PresentmentCannotSatisfyRequestException -> {
                    mutableState.value = IosProximityState.Failed(
                        message = "This wallet holds nothing the reader asked for."
                    )
                }

                else -> fail(t)
            }
        }
    }

    /** See [walletPresentmentSource], which holds every decision this shares with the other paths. */
    private suspend fun presentmentSource() = walletPresentmentSource(
        store = walletEngine.store(),
        credentialDomain = credentialDomain,
        readerTrust = readerTrust,
        // ISO 18013-5 has no SD-JWT, so there is nothing to offer.
        offersSdJwt = false,
        showConsent = { requester, trustMetadata, data ->
            awaitConsent(
                requester = requester,
                trustMetadata = trustMetadata,
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
        trustMetadata: TrustMetadata?,
        data: CredentialPresentmentData,
    ): CredentialPresentmentSelection? {
        val consent = CompletableDeferred<CredentialPresentmentSelection?>()
        pendingConsent = consent
        pendingData = data
        mutableState.value = IosProximityState.Requesting(
            request = data.toPresentmentRequest(
                // A name without trust behind it is still worth showing — but only the trust decision
                // marks it verified, and over BLE there is usually neither.
                requesterName = trustMetadata?.displayName ?: requester.appId,
                requesterIsTrusted = trustMetadata != null,
            ),
        )

        // Bounded: a reader that is handed nothing eventually times out anyway, and leaving the BLE
        // connection open forever is worse than telling it no.
        val selection = withTimeoutOrNull(CONSENT_TIMEOUT) { consent.await() }
        if (selection != null) {
            mutableState.value = IosProximityState.Sending
        }
        return selection
    }

    private fun fail(cause: Throwable) {
        Logger.w(TAG, "proximity presentation failed: ${cause.message}")
        mutableState.value = IosProximityState.Failed(
            message = cause.message ?: cause::class.simpleName ?: "Sharing failed."
        )
    }

    /**
     * [result] is never [NfcStartResult.Started] here — the one call site only invokes this after
     * checking that. BLE is still advertised regardless of which failure this is, so every message
     * below says so: this is a degraded-NFC notice, not a presentation failure.
     */
    private fun nfcStartFailureMessage(result: NfcStartResult): String = when (result) {
        NfcStartResult.Started ->
            error("nfcStartFailureMessage called for a successful start; see this method's own doc comment")

        NfcStartResult.NotSupported -> NFC_NOT_SUPPORTED
        NfcStartResult.NotEligible -> NFC_NOT_ELIGIBLE
        NfcStartResult.AccessNotAccepted -> NFC_ACCESS_NOT_ACCEPTED
        NfcStartResult.TransientFailure -> NFC_TRANSIENT_FAILURE
    }

    private companion object {
        const val TAG = "IosProximityPresenter"

        /** ISO 18013-5 device engagement version, as multipaz's own samples use. */
        const val ENGAGEMENT_VERSION = "1.0"

        val ENGAGEMENT_TIMEOUT = 1.minutes
        val CONSENT_TIMEOUT = 2.minutes

        /** Long enough for the user to answer iOS's Bluetooth prompt, short enough not to read as a hang. */
        val ADVERTISE_TIMEOUT = 20.seconds

        const val BLUETOOTH_UNAVAILABLE =
            "Could not start sharing over Bluetooth. Check that Bluetooth is on and that this app is " +
                    "allowed to use it."

        const val NFC_NOT_SUPPORTED =
            "This device or iOS version doesn't support NFC data retrieval. Sharing continues over " +
                    "Bluetooth."
        const val NFC_NOT_ELIGIBLE =
            "NFC data retrieval isn't available for this device right now. Sharing continues over " +
                    "Bluetooth."
        const val NFC_ACCESS_NOT_ACCEPTED =
            "NFC data retrieval isn't enabled for this app yet. Sharing continues over Bluetooth."
        const val NFC_TRANSIENT_FAILURE =
            "Could not start NFC data retrieval. Sharing continues over Bluetooth."
    }
}

/** The `mdoc:` URI a reader scans, per ISO 18013-5 §8.2.2.3. */
internal fun ByteArray.toQrPayload(): String = "mdoc:" + toBase64Url()

private fun ByteString.toDataItem(): DataItem =
    org.multipaz.cbor.Cbor.decode(this.toByteArray())
