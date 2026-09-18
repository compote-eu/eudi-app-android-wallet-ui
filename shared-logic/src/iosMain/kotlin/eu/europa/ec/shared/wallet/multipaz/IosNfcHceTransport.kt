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

import eu.europa.ec.shared.wallet.nfc.NfcHceBridge
import eu.europa.ec.shared.wallet.nfc.NfcHceBridgeDelegateProtocol
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.multipaz.mdoc.nfc.MdocNfcEngagementHelper
import org.multipaz.mdoc.transport.NfcTransportMdoc
import org.multipaz.nfc.CommandApdu
import org.multipaz.nfc.Nfc
import org.multipaz.nfc.ResponseApdu
import org.multipaz.util.Logger
import platform.Foundation.NSData
import platform.Foundation.create
import platform.darwin.NSObject

/**
 * Why [IosNfcHceTransport.start] did or didn't start a session, decoded from the plain `NSInteger`
 * codes `NfcHceBridge.swift`'s `StartResult` sends across the cinterop seam — see
 * `NfcHceBridge.def`'s `startWithCompletion:` doc comment for why an int, not a shared enum type,
 * crosses that boundary, and for what each code below means on the Swift side.
 */
sealed interface NfcStartResult {
    data object Started : NfcStartResult
    data object NotSupported : NfcStartResult
    data object NotEligible : NfcStartResult
    data object AccessNotAccepted : NfcStartResult
    data object TransientFailure : NfcStartResult

    companion object {
        fun fromCode(code: Long): NfcStartResult = when (code) {
            0L -> Started
            1L -> NotSupported
            2L -> NotEligible
            3L -> AccessNotAccepted
            // Any other value is unreached by the Swift side today, but a fresh, undocumented value
            // is exactly what "no precise diagnosis" is for, matching this file's ApduDelegate.
            else -> TransientFailure
        }
    }
}

/**
 * ISO 18013-5 NFC over CoreNFC: routes APDUs between CoreNFC and whichever of multipaz's two NFC
 * helpers actually owns the currently-selected AID — [NfcTransportMdoc] (mdoc-AID data retrieval,
 * Annex 8) or [MdocNfcEngagementHelper] (NDEF-AID engagement, Annex C) — and nothing else.
 *
 * ## Why this class exists at all
 *
 * CoreNFC's `CardSession` — the iOS 17.4+ Host Card Emulation API — is Swift-only. Verified directly
 * against this project's own SDK, not assumed: `CardSession` is declared only in the
 * `CoreNFC.swiftmodule` swiftinterface files, never in the Objective-C header cinterop parses, so
 * Kotlin/Native cannot reach it. `iosApp/NfcHceBridge` is the small vendored Swift package (the same
 * shape as `PKIXBridge`) that owns the real `CardSession` and its event loop; [NfcHceBridge] is the
 * cinterop-generated Kotlin binding to the Objective-C-visible seam it exposes. See
 * `wiki/IOS_NFC_PLAN.md` §3.1 for the full reasoning.
 *
 * multipaz's [NfcTransportMdoc] and [MdocNfcEngagementHelper] already implement their respective
 * ISO 18013-5 protocols (chunked ENVELOPE/GET RESPONSE APDU handling for the former, the NDEF virtual
 * filesystem and Handover Select construction for the latter) in platform-neutral `commonMain` code —
 * Android reaches both the same way, from `multipaz-compose`'s `MdocNfcDataTransferService` and
 * `MdocNdefService` respectively, one `HostApduService` per AID, with Android's own OS doing the
 * AID-based routing between them. iOS has no such OS-level router: CoreNFC hands every APDU for every
 * AID this app registers to the same delegate, regardless of which was selected — so *this* class is
 * where that routing has to happen instead. See `wiki/IOS_NFC_PLAN.md` §9 for the investigation behind
 * this design; the one-dispatcher, AID-based-state shape mirrors the pattern (not the code) of
 * `pagopa/iso18013-ios`'s `NFCDataTransfer.swift`, which solves the identical iOS-side problem by
 * hand-rolling both AIDs' logic itself — here, each AID's actual protocol logic still belongs to
 * multipaz, not this class.
 *
 * ## Scope
 *
 * This is transport/routing glue only, deliberately self-contained: it does not construct an
 * [NfcTransportMdoc] instance, advertise `MdocConnectionMethodNfc`, decide when cold-tap engagement
 * should be armed, or touch [IosProximityPresenter] — that lifecycle (when a [MdocNfcEngagementHelper]
 * exists at all, what `eDeviceKey` and handover callbacks it's built with) is `wiki/IOS_NFC_PLAN.md`
 * §9 Stage 3's job. What this class answers is narrower: once *something* is listening for a given
 * AID — an open [NfcTransportMdoc] instance, or a [engagementHelper] set by whoever owns that
 * lifecycle — route the raw bytes CoreNFC hands over to the right one, and back.
 */
@OptIn(ExperimentalForeignApi::class)
class IosNfcHceTransport {

    /**
     * The current cold-tap (Annex C) engagement helper, or `null` if none is currently offered.
     *
     * Owned and set by whoever manages engagement lifecycle (`wiki/IOS_NFC_PLAN.md` §9 Stage 3 —
     * `IosProximityPresenter`, armed for as long as the "Share over NFC" switch is on and the
     * proximity screen is open, independent of any QR-specific event). `null` here means an
     * NDEF-AID `SELECT` is answered with [Nfc.RESPONSE_STATUS_ERROR_FILE_OR_APPLICATION_NOT_FOUND]
     * rather than routed anywhere — the same "nothing is listening" answer a reader would get if this
     * AID weren't registered at all.
     */
    var engagementHelper: MdocNfcEngagementHelper? = null

    /**
     * Set once cold-tap handover has completed on [engagementHelper] — from then on, `ApduDelegate`
     * refuses to route any further `SELECT` (application or file) on the NDEF AID to it, but keeps
     * routing `READ_BINARY`/`UPDATE_BINARY` through normally. Real-device finding (`wiki/IOS_NFC_PLAN.md`
     * §9): [MdocNfcEngagementHelper] has no defense against being re-entered via a repeat `SELECT` —
     * that re-runs its handover construction logic from scratch and crashes on an assumption that only
     * held the first time — but a `READ_BINARY` is a pure, side-effect-free read of already-built state
     * and answers correctly regardless of how many times a reader repeats it. Whoever owns engagement
     * lifecycle (`IosProximityPresenter`) sets this, and must reset it alongside `engagementHelper = null`
     * everywhere that already happens — a stale `true` left over from a previous tap would silently
     * reject legitimate `SELECT`s the next time cold-tap arms.
     */
    var ndefHandoverCompleted: Boolean = false

    private val delegate = ApduDelegate(
        engagementHelperProvider = { engagementHelper },
        ndefHandoverCompletedProvider = { ndefHandoverCompleted },
    )
    private var bridge: NfcHceBridge? = null

    /**
     * Starts CoreNFC card emulation. [onStarted] answers *why*, not just whether — see
     * [NfcStartResult]. Findings A/B of the current-state audit: before this, the Swift side
     * collapsed every reason `CardSession` can refuse into one boolean, and a `false` here only
     * ever reached [org.multipaz.util.Logger.w] — nothing distinguished "this device can never do
     * this" from "not approved yet" from "transient", and nothing reached the UI at all.
     */
    fun start(onStarted: (NfcStartResult) -> Unit) {
        val bridge = NfcHceBridge(delegate = delegate)
        this.bridge = bridge
        bridge.startWithCompletion { code -> onStarted(NfcStartResult.fromCode(code)) }
    }

    /**
     * Stops card emulation and tells multipaz the transport went away, mirroring what CoreNFC's own
     * `.sessionInvalidated` event does on the Swift side when the reader walks away instead.
     */
    fun stop() {
        bridge?.stop()
        bridge = null
        NfcTransportMdoc.onDeactivated()
    }


    /**
     * The Objective-C-visible half of the seam: Swift calls [processCommandApdu] once per APDU, and
     * whichever helper answers — [NfcTransportMdoc] (asynchronous; dispatches its own coroutine
     * internally) or [MdocNfcEngagementHelper] (`suspend`; bridged onto [scope] here) — is what
     * answers [completion].
     *
     * [forwardToMultipaz] and [forwardToEngagementHelper] exist only so a test can substitute a spy in
     * place of the real forwarding target and assert it was, or wasn't, called — this class has no
     * other reason to take either as a parameter, and every real caller relies on the defaults.
     *
     * ## Why the `try`/`catch`
     *
     * [commandApdu] is untrusted: it comes straight from an NFC reader, over the air, before any
     * authentication. Multipaz's own [org.multipaz.nfc.CommandApdu.decode] — the first thing this
     * method does, synchronously, before any routing decision — throws
     * (`IllegalArgumentException`/`IllegalStateException`, verified directly against that function's
     * source) on malformed or truncated bytes. Uncaught, that exception would cross back out through
     * the `NfcHceBridgeDelegate` Objective-C protocol call from Swift into here — a boundary this
     * seam's own `.def` declares as a plain, non-`throws` method — which is undefined/fatal, not a
     * graceful Swift error. There is no existing precedent for guarding this in Multipaz itself: its
     * Android platform bindings (`multipaz-compose`'s `MdocNfcDataTransferService`/`MdocNdefService`)
     * forward raw bytes to their respective processing entry points with no try/catch of their own
     * either, so Android has this exact same gap for both AIDs — this fix does not inherit a pattern,
     * it establishes one, and it now covers both AIDs since the decode happens before either branch.
     *
     * On a decode failure, [Nfc.RESPONSE_STATUS_ERROR_NO_PRECISE_DIAGNOSIS] is sent back rather than
     * silently dropping the APDU (leaving it to `CardSession`'s own timeout) or, worse, letting the
     * exception propagate: it is the exact status word both [NfcTransportMdoc.processApdu] and
     * [MdocNfcEngagementHelper.processApdu]'s own catch-alls already reply with for a *later*-stage
     * unexpected failure, so a decode failure now answers with the same signal Multipaz's own code
     * already uses for the equivalent case one step later, regardless of which AID it would have been.
     *
     * ## Two AIDs, one piece of state neither helper can see
     *
     * See `wiki/IOS_NFC_PLAN.md` §3.4/§9: [NfcTransportMdoc.failTransport] asserts a lock two of its
     * own call sites never acquire, turning *any* rejected/unexpected APDU on the mdoc AID into an
     * uncaught exception that crashes the whole process — not a bug this seam can fix (it's private to
     * Multipaz's own class), but one whose most common trigger (a reader `SELECT`ing an AID this app
     * doesn't serve) can be kept from ever reaching Multipaz at all. [MdocNfcEngagementHelper] has no
     * equivalent bug — its own `processApdu` catch-all converts any exception to a status word without
     * an unguarded lock assertion anywhere — but the same "don't let a wrong AID reach either helper"
     * shape still applies for symmetry and because a genuinely unsupported third AID should never
     * reach either one's internal state at all.
     *
     * A `SELECT APPLICATION` for anything other than the mdoc AID or the NDEF AID is answered with
     * [Nfc.RESPONSE_STATUS_ERROR_FILE_OR_APPLICATION_NOT_FOUND] (SW `6A82`) directly, before either
     * helper ever sees it. For the two AIDs this app does serve, [SelectedApplication] — set only by a
     * successful `SELECT APPLICATION`, read by every subsequent APDU — is what decides where a
     * non-`SELECT APPLICATION` command goes next: neither [NfcTransportMdoc] nor
     * [MdocNfcEngagementHelper] carries any AID-level identity of its own (a `READ_BINARY` or
     * `ENVELOPE` command carries no AID field at all — that's implicit, tied to whichever application
     * was last selected), so this one bit of state has to live here, one layer above both helpers,
     * under either design this investigation considered (`wiki/IOS_NFC_PLAN.md` §9's own comparison
     * against `pagopa/iso18013-ios`'s single hand-rolled dispatcher makes the same point: whichever
     * side owns AID routing needs exactly this, regardless of whether the per-AID protocol logic below
     * it is hand-rolled or, as here, Multipaz's own).
     *
     * This is genuinely defense in depth for the mdoc AID, not redundant with the entitlement
     * mitigation already in place: the entitlement narrowing in `iosApp/project.yml` (§3.4) is what
     * actually keeps a *different* AID's `SELECT` from reaching this app on real hardware at all —
     * `com.apple.developer.nfc.hce.iso7816.select-identifier-prefixes`'s own name says *prefixes*,
     * i.e. iOS matches by prefix, not exact equality, so a reader presenting a longer AID that merely
     * *starts with* either registered AID's bytes would still be routed here by iOS, then fail this
     * check's exact-equality comparison — a case the entitlement alone does not close.
     *
     * Done here, in Kotlin, rather than in `NfcHceBridge.swift`: this needs [CommandApdu.decode] and
     * [Nfc]'s AID/status-word constants to parse the header and compare it correctly, and both are
     * Multipaz Kotlin APIs with no Swift-visible equivalent — reimplementing ISO 7816-4 SELECT parsing
     * in Swift would mean a second, independently-maintained parser next to Multipaz's own, which is
     * exactly the "no CBOR, crypto, or mdoc-session logic" line `wiki/IOS_NFC_PLAN.md` §3.1 draws for
     * the Swift side of this seam. This only inspects a header Multipaz's own decoder already parsed;
     * it doesn't reimplement anything Multipaz owns.
     *
     * This only ever gates the *initial* `SELECT APPLICATION` for an unsupported AID. Once the mdoc AID
     * is genuinely selected, a later duplicate `SELECT`, an out-of-order `ENVELOPE`/`GET_RESPONSE`, or
     * any other protocol violation within that same legitimate session still reaches [forwardToMultipaz]
     * and still hits the same unlocked `failTransport()` bug — this class has no visibility into
     * Multipaz's own internal session state (`applicationSelected`, `_state`) to gate on beyond
     * [selectedApplication] itself, and duplicating more of it here would mean tracking Multipaz's
     * private state a second time, badly. That residual exposure is closed only by Multipaz fixing the
     * missing lock — see §3.2's upstream list.
     *
     * [MdocNfcEngagementHelper] has a narrower, *precisely* gated version of the same shape, not the
     * unlocked-crash kind — see [IosNfcHceTransport.ndefHandoverCompleted]'s own doc comment for the
     * full trace. In short: a repeat `SELECT` on the NDEF AID after handover has already completed
     * re-enters that helper's construction logic and crashes on an assumption that only held the first
     * time — real-device finding, `wiki/IOS_NFC_PLAN.md` §9. Unlike the mdoc-AID case above, this one
     * *is* precisely closed, not just narrowed: [ndefHandoverCompletedProvider] distinguishes exactly
     * the dangerous re-entry (`SELECT`) from the harmless one (`READ_BINARY`/`UPDATE_BINARY`, a pure
     * read of already-built state that answers correctly no matter how many times it's repeated) —
     * an earlier version of this fix rejected all NDEF-AID traffic once handover completed, which
     * over-corrected and broke a reader's legitimate trailing read; this is the corrected, narrower
     * version.
     */
    internal class ApduDelegate(
        private val forwardToMultipaz: (
            commandApdu: ByteArray,
            sendResponse: (responseApdu: ByteArray) -> Unit,
        ) -> Unit = { commandApdu, sendResponse ->
            NfcTransportMdoc.processCommandApdu(commandApdu = commandApdu, sendResponse = sendResponse)
        },
        /** See [IosNfcHceTransport.engagementHelper] — `null` until Stage 3 arms cold-tap engagement. */
        private val engagementHelperProvider: () -> MdocNfcEngagementHelper? = { null },
        /** See [IosNfcHceTransport.ndefHandoverCompleted] — gates the sticky NDEF routing branch below. */
        private val ndefHandoverCompletedProvider: () -> Boolean = { false },
        /**
         * Where [MdocNfcEngagementHelper.processApdu] — a `suspend` function, unlike
         * [NfcTransportMdoc.processCommandApdu]'s own callback shape — gets bridged onto a
         * completion-style call. Matches this codebase's existing convention for launching a suspend
         * call from a synchronous, Swift-facing entry point (`IosProximityPresenter`'s and
         * `IosRemotePresenter`'s own `scope: CoroutineScope = CoroutineScope(Dispatchers.Default)`
         * constructor parameter), rather than `IosDocumentProviderBridge.kt`'s convention — that one
         * *exports* a suspend method to Swift directly via cinterop, a different problem from bridging
         * *into* an already-synchronous callback method here.
         */
        private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
        private val forwardToEngagementHelper: (
            command: CommandApdu,
            sendResponse: (ResponseApdu) -> Unit,
        ) -> Unit = { command, sendResponse ->
            val helper = engagementHelperProvider()
            if (helper == null) {
                Logger.w(TAG, "NDEF AID selected but no engagement helper is armed")
                sendResponse(ResponseApdu(status = Nfc.RESPONSE_STATUS_ERROR_FILE_OR_APPLICATION_NOT_FOUND))
            } else {
                scope.launch { sendResponse(helper.processApdu(command)) }
            }
        },
    ) : NSObject(), NfcHceBridgeDelegateProtocol {

        /** Which application the last successful `SELECT APPLICATION` chose; see this class's own doc. */
        private enum class SelectedApplication { NONE, MDOC, NDEF }

        private var selectedApplication = SelectedApplication.NONE

        override fun processCommandApdu(commandApdu: NSData, completion: (NSData?) -> Unit) {
            try {
                val bytes = commandApdu.toByteArray()
                val command = CommandApdu.decode(bytes)
                // Real-device correlation logging (wiki/IOS_NFC_PLAN.md §9): this line alone proves
                // whether the wallet ever received ANY APDU from the reader — if the system's own
                // Wallet/contactless picker intercepted the tap before this app's CardSession, this
                // line never fires at all, timestamp included via Logger's own formatting.
                Logger.i(
                    TAG,
                    "APDU received: ins=0x${command.ins.toString(16)} p1=0x${command.p1.toString(16)} " +
                        "payloadLen=${command.payload.size} currentApplication=$selectedApplication",
                )

                if (command.ins == Nfc.INS_SELECT && command.p1 == Nfc.INS_SELECT_P1_APPLICATION) {
                    when (command.payload) {
                        Nfc.ISO_MDOC_NFC_DATA_TRANSFER_APPLICATION_ID -> {
                            selectedApplication = SelectedApplication.MDOC
                            Logger.i(TAG, "SELECT mdoc AID: routing to NfcTransportMdoc")
                            forwardToMultipaz(bytes) { responseApdu -> completion(responseApdu.toNSData()) }
                        }

                        Nfc.NDEF_APPLICATION_ID -> {
                            selectedApplication = SelectedApplication.NDEF
                            Logger.i(TAG, "SELECT NDEF AID: routing to the engagement helper")
                            forwardToEngagementHelper(command) { responseApdu ->
                                completion(responseApdu.encode().toNSData())
                            }
                        }

                        else -> {
                            Logger.w(TAG, "Rejecting SELECT for an unsupported AID before either helper ever sees it")
                            completion(
                                ResponseApdu(status = Nfc.RESPONSE_STATUS_ERROR_FILE_OR_APPLICATION_NOT_FOUND)
                                    .encode()
                                    .toNSData(),
                            )
                        }
                    }
                    return
                }

                when (selectedApplication) {
                    SelectedApplication.MDOC -> {
                        Logger.i(TAG, "Routing to NfcTransportMdoc (mdoc AID already selected)")
                        forwardToMultipaz(bytes) { responseApdu -> completion(responseApdu.toNSData()) }
                    }

                    SelectedApplication.NDEF -> {
                        if (ndefHandoverCompletedProvider() && command.ins == Nfc.INS_SELECT) {
                            // Real-device finding (wiki/IOS_NFC_PLAN.md §9): a repeat SELECT (application
                            // or file) here would re-enter MdocNfcEngagementHelper's handover construction
                            // and crash — reject it before it ever reaches the helper. READ_BINARY/
                            // UPDATE_BINARY are unaffected by this check (see the `&& command.ins ==
                            // Nfc.INS_SELECT` above) and keep routing through normally below: they're
                            // pure reads of already-built state, safe no matter how many times repeated.
                            Logger.w(TAG, "Rejecting a repeat SELECT on the NDEF AID after handover already completed")
                            completion(
                                ResponseApdu(status = Nfc.RESPONSE_STATUS_ERROR_FILE_OR_APPLICATION_NOT_FOUND)
                                    .encode()
                                    .toNSData(),
                            )
                        } else {
                            Logger.i(TAG, "Routing to the engagement helper (NDEF AID already selected)")
                            forwardToEngagementHelper(command) { responseApdu ->
                                completion(responseApdu.encode().toNSData())
                            }
                        }
                    }

                    SelectedApplication.NONE -> {
                        Logger.w(TAG, "APDU received before any application was selected")
                        completion(
                            ResponseApdu(status = Nfc.RESPONSE_STATUS_ERROR_INSTRUCTION_NOT_SUPPORTED_OR_INVALID)
                                .encode()
                                .toNSData(),
                        )
                    }
                }
            } catch (e: Exception) {
                Logger.w(TAG, "Malformed command APDU, replying with a diagnostic status word: ${e.message}")
                completion(ResponseApdu(status = Nfc.RESPONSE_STATUS_ERROR_NO_PRECISE_DIAGNOSIS).encode().toNSData())
            }
        }
    }

    companion object {
        private const val TAG = "IosNfcHceTransport"

        /**
         * The synchronous device/OS support check — deliberately not Apple's `async
         * CardSession.isEligible` region gate; see the Swift-side doc comment on
         * `NfcHceBridge.isSupported()` for why. Finding D of the current-state audit:
         * `isNfcDataRetrievalAvailable()` used to answer `true` unconditionally, regardless of
         * whether the device could ever actually start a session.
         */
        fun isSupported(): Boolean = NfcHceBridge.isSupported()
    }
}

// Identical to the private helpers in IosKeychain.kt (eu.europa.ec.authenticationlogic.storage) —
// and, it turns out, in KeychainWalletStorage.kt too, in this very package: widening either of these
// to `internal` for IosNfcHceTransportTest to reuse was tried and reverted — it collides with
// KeychainWalletStorage.kt's own same-named, same-signature private extension in this same package
// ("Overload resolution ambiguity"), confirming the comment below was right the first time. The test
// has its own small copy instead. Worth promoting to one shared utility if this collision itself
// gets in the way of something real, not just for a fourth call site.

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val bytes = ByteArray(size)
    if (size > 0) {
        bytes.usePinned { pinned ->
            platform.posix.memcpy(pinned.addressOf(0), this.bytes, length)
        }
    }
    return bytes
}
