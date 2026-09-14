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
 * ISO 18013-5 Annex 8 NFC data retrieval: feeds APDU bytes between CoreNFC and multipaz's
 * [NfcTransportMdoc], and nothing else.
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
 * multipaz's [NfcTransportMdoc] already implements the ISO 18013-5 NFC data-retrieval protocol
 * (chunked ENVELOPE/GET RESPONSE APDU handling, session state) in platform-neutral `commonMain` code
 * — Android reaches it the same way, from `multipaz-compose`'s `NfcApduService`. This class is the
 * iOS half of that same seam: nothing here parses an APDU, tracks session state, or touches CBOR —
 * multipaz owns all of that on both platforms.
 *
 * ## Scope
 *
 * This is transport glue only, deliberately self-contained: it does not construct an
 * [NfcTransportMdoc] instance, advertise `MdocConnectionMethodNfc`, or touch [IosProximityPresenter].
 * Opening a transport instance needs the device-engagement key the presentment flow generates, which
 * is §5 phase 3's job (wiring this in alongside the existing BLE path). What this class answers is
 * narrower: once *some* [NfcTransportMdoc] instance is open, route the raw bytes CoreNFC hands over to
 * multipaz's static dispatcher, and back.
 */
@OptIn(ExperimentalForeignApi::class)
class IosNfcHceTransport {

    private val delegate = ApduDelegate()
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
     * [NfcTransportMdoc]'s reply — asynchronous; it dispatches its own coroutine — is what answers
     * [completion].
     *
     * [forwardToMultipaz] exists only so a test can substitute a spy in place of the real
     * [NfcTransportMdoc.processCommandApdu] and assert it was never called — this class has no other
     * reason to take it as a parameter, and every real caller relies on the default.
     *
     * ## Why the `try`/`catch`
     *
     * [commandApdu] is untrusted: it comes straight from an NFC reader, over the air, before any
     * authentication. Multipaz's own [org.multipaz.nfc.CommandApdu.decode] — the first thing
     * [NfcTransportMdoc.processCommandApdu] does, synchronously, before it ever reaches a coroutine —
     * throws (`IllegalArgumentException`/`IllegalStateException`, verified directly against that
     * function's source) on malformed or truncated bytes. Uncaught, that exception would cross back
     * out through the `NfcHceBridgeDelegate` Objective-C protocol call from Swift into here — a
     * boundary this seam's own `.def` declares as a plain, non-`throws` method — which is undefined/
     * fatal, not a graceful Swift error. There is no existing precedent for guarding this in Multipaz
     * itself: its Android platform binding
     * (`multipaz-compose`'s `MdocNfcDataTransferService`/`CombinedNfcService`) forwards raw bytes to
     * the very same [NfcTransportMdoc.processCommandApdu] with no try/catch of its own either, so
     * Android has this exact same gap — this fix does not inherit a pattern, it establishes one.
     *
     * On a decode failure, [Nfc.RESPONSE_STATUS_ERROR_NO_PRECISE_DIAGNOSIS] is sent back rather than
     * silently dropping the APDU (leaving it to `CardSession`'s own timeout) or, worse, letting the
     * exception propagate: it is the exact status word [NfcTransportMdoc]'s own `processApdu` catch-all
     * already replies with for a *later*-stage unexpected failure — see its `catch (error: Exception)`
     * branch — so a decode failure now answers with the same "something went wrong, no precise
     * diagnosis" signal Multipaz's own code already uses for the equivalent case one step later.
     *
     * ## Why the AID check, and why it lives here rather than in `NfcHceBridge.swift`
     *
     * See `wiki/IOS_NFC_PLAN.md` §3.4: [NfcTransportMdoc.failTransport] asserts a lock two of its own
     * call sites never acquire, turning *any* rejected/unexpected APDU into an uncaught exception that
     * crashes the whole process — not a bug this seam can fix (it's private to Multipaz's own class),
     * but one whose most common trigger (a reader `SELECT`ing an AID this app doesn't serve) can be
     * kept from ever reaching Multipaz at all. A `SELECT`-by-AID for anything but the mdoc AID is
     * answered with [Nfc.RESPONSE_STATUS_ERROR_FILE_OR_APPLICATION_NOT_FOUND] (SW `6A82` — the same
     * status [NfcTransportMdoc.processSelectApplication] itself would eventually answer with, had it
     * not crashed first) directly, without [forwardToMultipaz] ever running.
     *
     * This is genuinely defense in depth, not redundant with the entitlement mitigation already in place: the
     * entitlement narrowing in `iosApp/project.yml` (§3.4) is what actually keeps a *different* AID's
     * `SELECT` from reaching this app on real hardware at all — `com.apple.developer.nfc.hce
     * .iso7816.select-identifier-prefixes`'s own name says *prefixes*, i.e. iOS matches by prefix, not
     * exact equality, so a reader presenting a longer AID that merely *starts with* the mdoc AID's
     * seven bytes would still be routed here by iOS, then fail Multipaz's own exact-equality check
     * (`NfcTransportMdoc.processSelectApplication`) and hit the same unlocked `failTransport()` path —
     * a case the entitlement alone does not close. This check does, because it mirrors Multipaz's own
     * exact-equality comparison rather than iOS's prefix one.
     *
     * Done here, in Kotlin, rather than in `NfcHceBridge.swift`: this needs [CommandApdu.decode] and
     * [Nfc]'s AID/status-word constants to parse the header and compare it correctly, and both are
     * Multipaz Kotlin APIs with no Swift-visible equivalent — reimplementing ISO 7816-4 SELECT parsing
     * in Swift would mean a second, independently-maintained parser next to Multipaz's own, which is
     * exactly the "no CBOR, crypto, or mdoc-session logic" line `wiki/IOS_NFC_PLAN.md` §3.1 draws for
     * the Swift side of this seam. This only inspects a header Multipaz's own decoder already parsed;
     * it doesn't reimplement anything Multipaz owns.
     *
     * This only ever gates the *initial* `SELECT`: once the mdoc AID is genuinely selected, a later
     * duplicate `SELECT`, an out-of-order `ENVELOPE`/`GET_RESPONSE`, or any other protocol violation
     * within that same legitimate session still reaches [forwardToMultipaz] and still hits the same
     * unlocked `failTransport()` bug — this class has no visibility into Multipaz's own internal
     * session state (`applicationSelected`, `_state`) to gate on, and duplicating it here would mean
     * tracking Multipaz's private state a second time, badly. That residual exposure is closed only by
     * Multipaz fixing the missing lock — see §3.2's upstream list.
     */
    internal class ApduDelegate(
        private val forwardToMultipaz: (
            commandApdu: ByteArray,
            sendResponse: (responseApdu: ByteArray) -> Unit,
        ) -> Unit = { commandApdu, sendResponse ->
            NfcTransportMdoc.processCommandApdu(commandApdu = commandApdu, sendResponse = sendResponse)
        },
    ) : NSObject(), NfcHceBridgeDelegateProtocol {
        override fun processCommandApdu(commandApdu: NSData, completion: (NSData?) -> Unit) {
            try {
                val bytes = commandApdu.toByteArray()
                val command = CommandApdu.decode(bytes)
                if (isSelectForAnotherAid(command)) {
                    Logger.w(TAG, "Rejecting SELECT for a non-mdoc AID before multipaz ever sees it")
                    completion(
                        ResponseApdu(status = Nfc.RESPONSE_STATUS_ERROR_FILE_OR_APPLICATION_NOT_FOUND)
                            .encode()
                            .toNSData(),
                    )
                    return
                }
                forwardToMultipaz(bytes) { responseApdu -> completion(responseApdu.toNSData()) }
            } catch (e: Exception) {
                Logger.w(TAG, "Malformed command APDU, replying with a diagnostic status word: ${e.message}")
                completion(ResponseApdu(status = Nfc.RESPONSE_STATUS_ERROR_NO_PRECISE_DIAGNOSIS).encode().toNSData())
            }
        }

        private fun isSelectForAnotherAid(command: CommandApdu): Boolean =
            command.ins == Nfc.INS_SELECT &&
                command.p1 == Nfc.INS_SELECT_P1_APPLICATION &&
                command.payload != Nfc.ISO_MDOC_NFC_DATA_TRANSFER_APPLICATION_ID
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
