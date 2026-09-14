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
 * ISO 18013-5 proximity presentation on iOS: QR engagement, BLE and NFC data retrieval, and the
 * mdoc response.
 *
 * multipaz owns the protocol — `Iso18013Presentment` runs the exchange and `SimplePresentmentSource`
 * matches the reader's request against the wallet's documents. What this adds is the two things multipaz
 * deliberately leaves to the app: which credentials may be offered, and a consent step that waits for a
 * *person* rather than answering itself. [state] is what a screen renders; [accept] and [decline] are what
 * a screen calls back.
 *
 * Both connection methods end up in the same engagement CBOR and the same [waitForConnection] call
 * (multipaz's own, connection-method-agnostic — it takes whichever transport the reader actually
 * connects over), but [startQrEngagement] gets each transport there differently. BLE goes through
 * multipaz's [advertise] extension against `MdocTransportFactory.Default`, same as before. NFC does
 * not: that factory's iOS actual throws for [MdocConnectionMethodNfc] + [MdocRole.MDOC] — the wallet
 * role is simply never implemented there, only [MdocRole.MDOC_READER] is — so this class constructs
 * multipaz's own [NfcTransportMdoc] directly instead of going through the factory at all; see
 * `wiki/IOS_NFC_PLAN.md` §3.3. Kept as a separate step (and a separate try/catch, see
 * [createNfcTransport]) specifically so a construction failure there can never take the already-
 * succeeded BLE transport down with it — the two used to share one [advertise] call and one
 * `try`/`catch`, which is exactly what let NFC's exception abort BLE mid-flight on a real device.
 * NFC additionally needs [nfcTransport] started, since answering a tap is not something constructing
 * a transport does by itself; see `wiki/IOS_NFC_PLAN.md` §3.1 for why that half is a separate
 * Swift-owned piece rather than more multipaz plumbing.
 *
 * **The BLE half is written but unproven, and so is NFC's.** multipaz ships `BlePeripheralManagerIos`,
 * so the transport is there, but the iOS Simulator has no Bluetooth radio (nor NFC), and multipaz has
 * no other mdoc transport — so nothing between [startQrEngagement] and a connected reader can be
 * exercised without a device and a verifier. What *is* covered by tests is everything after the request
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

    private val mutableState = MutableStateFlow<IosProximityState>(IosProximityState.Idle)
    val state: StateFlow<IosProximityState> = mutableState.asStateFlow()

    /**
     * One-shot, human-readable notices about NFC specifically — not modeled as an [IosProximityState]
     * because a failed NFC start is not a failed *presentation*: BLE was advertised in the same
     * [startQrEngagement] call and keeps working, so there is nothing here for [state] itself to fail
     * on. Finding A/B of the current-state audit: before this, a failed [nfcTransport] start reached
     * only [org.multipaz.util.Logger.w] — nothing surfaced to a screen at all.
     *
     * `extraBufferCapacity = 1` rather than `replay`: a subscriber that starts collecting after a
     * notice fires should not see it (it is stale by then), but a slow one should not lose it either
     * — [IosProximityCoordinator.qrEvents] subscribes before [startQrEngagement] runs specifically so
     * a real reader of this never has to be the slow case.
     */
    private val mutableNfcNotice = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val nfcNotice: SharedFlow<String> = mutableNfcNotice.asSharedFlow()

    /**
     * Owns CoreNFC's `CardSession` for as long as engagement is advertised. Separate from
     * [transport]: advertising the NFC connection method only tells multipaz to accept a connection
     * if one arrives, it does not itself answer a tap — started late, from [runPresentment], not from
     * [startQrEngagement] where the connection method itself is declared; see the comment there.
     */
    private val nfcTransport = IosNfcHceTransport()

    /**
     * Whether the next [startQrEngagement] offers NFC data retrieval alongside BLE. Defaults to
     * `false` — deliberately: nothing on iOS calls [setNfcEngagementEnabled] yet (that is phase 4's
     * job, see `wiki/IOS_NFC_PLAN.md`), so a `true` default would mean CoreNFC's `CardSession` starts
     * unconditionally on every real device the moment this screen opens, with no way to turn it off.
     * `false` keeps NFC inert until something real opts it in.
     */
    // False until phase 4 wires a real user-facing toggle: flipping this to `true` makes CoreNFC's
    // CardSession start automatically and unconditionally on real hardware, with no way to disable it.
    private var nfcEngagementEnabled = false

    private var presentmentJob: Job? = null
    private var transport: MdocTransport? = null
    private var pendingConsent: CompletableDeferred<CredentialPresentmentSelection?>? = null

    /** The request being consented to, kept so [accept] can turn the app's answer back into matches. */
    private var pendingData: CredentialPresentmentData? = null

    /** What the user agreed to share, remembered so the success state can name it. */
    private var sharedDocuments: List<String> = emptyList()

    /**
     * Advertises this wallet over BLE and NFC and publishes the QR the reader scans.
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

            // NFC's own transport.advertise() call is a no-op (see NfcTransportMdoc's own doc comment:
            // the platform is expected to call it when APDUs actually arrive), so — unlike BLE's radio
            // setup — it can never contribute to a hang; no timeout needed here. Starting the actual NFC
            // radio is [runPresentment]'s job now, not this method's; see the comment there for why.
            val transports = bleTransports + listOfNotNull(
                if (nfcEngagementEnabled) createNfcTransport() else null,
            )

            // Built from the transports that actually exist, not from what was merely requested: a
            // [createNfcTransport] failure means NFC is quietly dropped from both [transports] and the
            // engagement CBOR together, so a reader is never told a connection method is available that
            // nothing here can actually answer.
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
     * Constructs multipaz's own wallet-role NFC transport directly, bypassing
     * `MdocTransportFactory.Default` entirely — see this class's own doc comment and
     * `wiki/IOS_NFC_PLAN.md` §3.3 for why: multipaz's iOS platform factory throws for
     * [MdocConnectionMethodNfc] + [MdocRole.MDOC], so nothing routes through it here. [NfcTransportMdoc]
     * is the same public class that factory would have returned had it been implemented — this doesn't
     * reimplement any protocol logic, it just does the one construction step the factory was supposed
     * to do.
     *
     * Returns `null`, never throws, and reports failure through [nfcNotice] instead — the same
     * "NFC-specific, BLE keeps working" channel [runPresentment]'s NFC-start failures already use (see
     * this class's own doc comment on [mutableNfcNotice]). [startQrEngagement]'s BLE transport is
     * already live by the time this runs; nothing here may fail that call.
     */
    private suspend fun createNfcTransport(): MdocTransport? = try {
        NfcTransportMdoc(
            role = MdocRole.MDOC,
            options = MdocTransportOptions(),
            connectionMethod = nfcConnectionMethod(),
        ).also { it.advertise() }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Logger.w(TAG, "Could not create NFC transport: ${t.message}")
        mutableNfcNotice.tryEmit(NFC_TRANSPORT_UNAVAILABLE)
        null
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
     * Whether the *next* [startQrEngagement] offers NFC data retrieval alongside BLE. Takes effect on
     * the next call, not the current exchange — there is no live toggle mid-engagement, only a decision
     * made the next time engagement starts.
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
        nfcTransport.stop()
        mutableState.value = IosProximityState.Idle
    }

    private suspend fun runPresentment(
        transports: List<MdocTransport>,
        eDeviceKey: EcPrivateKey,
        engagement: ByteArray,
    ) {
        // Late NFC initialization, adapted from pagopa/iso18013-ios (MIT-licensed;
        // IOWalletProximity/ISO18013.swift's `isNfcLateEngagement` flag / `lateNfcInitialization()`
        // method — see wiki/IOS_NFC_PLAN.md's phase 5 notes). Their shape: declare NFC as an available
        // retrieval method immediately (already true here — it went into [engagement]'s CBOR back in
        // [startQrEngagement]), but defer actually starting the radio to a later, separate call. Their
        // own trigger point for that later call is caller-decided and not part of the ported API
        // surface, so the trigger here is this app's own choice, not pagopa's: once the QR is genuinely
        // on screen and BLE has started advertising ([startQrEngagement] only reaches this point after
        // both), not the instant the screen opens. Saves battery and an idle radio during the QR
        // screen's own setup work (key generation, engagement CBOR, BLE radio bring-up).
        //
        // Gated on an actual NfcTransportMdoc being in [transports], not on [nfcEngagementEnabled]
        // directly: [createNfcTransport] can fail (rare — see its own doc comment) and drop NFC from
        // both [transports] and the engagement CBOR together. Starting CardSession anyway in that case
        // would recreate this fix's own original bug from the other direction — a real tap arriving
        // with no NfcTransportMdoc instance registered to answer it (`NfcTransportMdoc.instances` only
        // ever gains an entry from [MdocTransport.open], called below on whatever is in [transports]).
        if (transports.any { it is NfcTransportMdoc }) {
            nfcTransport.start { result ->
                if (result != NfcStartResult.Started) {
                    Logger.w(TAG, "NFC card emulation did not start: $result")
                    mutableNfcNotice.tryEmit(nfcStartFailureMessage(result))
                }
            }
        }

        try {
            // [nfcTransport.start] above only kicks off CardSession activation — a real Swift/CoreNFC
            // round trip that only resolves, asynchronously, via its own completion callback — it does
            // not suspend until CardSession is actually ready to receive a tap. [waitForConnection]
            // below calls [MdocTransport.open] on every transport in [transports], including the NFC
            // one: a synchronous, mutex-guarded `instances.add(this)` (see NfcTransportMdoc.open's own
            // source) with no real I/O, scheduled the moment control reaches this line — i.e.
            // immediately after the call above returns, not after CardSession finishes activating. So
            // NfcTransportMdoc's registration into its own static `instances` list — what
            // IosNfcHceTransport's processCommandApdu/onDeactivated calls dispatch against — is
            // guaranteed to finish well before CardSession can plausibly finish becoming ready for a
            // physical tap, let alone before an actual tap can happen. This is what makes
            // `NfcTransportMdoc.processCommandApdu` finding a live, open instance possible at all on
            // this platform for the first time; see wiki/IOS_NFC_PLAN.md §3.3.
            val connected = transports.waitForConnection(eSenderKey = eDeviceKey.publicKey)
            transport = connected

            Iso18013Presentment(
                transport = connected,
                eDeviceKey = eDeviceKey,
                deviceEngagement = ByteString(engagement).toDataItem(),
                handover = Simple.NULL,
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

        /** See [createNfcTransport]. */
        const val NFC_TRANSPORT_UNAVAILABLE =
            "Could not prepare NFC data retrieval for this session. Sharing continues over Bluetooth."
    }
}

/** The `mdoc:` URI a reader scans, per ISO 18013-5 §8.2.2.3. */
internal fun ByteArray.toQrPayload(): String = "mdoc:" + toBase64Url()

private fun ByteString.toDataItem(): DataItem =
    org.multipaz.cbor.Cbor.decode(this.toByteArray())
