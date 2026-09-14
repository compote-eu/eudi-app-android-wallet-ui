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

// Unlike IosProximityPresentmentTest's BLE wire, this one machine-checkable claim about the NFC
// wire IS reachable from the Simulator: CoreNFC's own "can this device do this at all" gate
// (NFCReaderSession.readingAvailable / CardSession.isSupported / CardSession.isEligible) runs for
// real here, it just always answers false, because the Simulator has no NFC radio. That is enough to
// exercise the whole Kotlin -> NfcHceBridge -> CardSession round trip end to end without a reader —
// everything past that gate (an actual APDU exchange) needs real hardware, see wiki/IOS_NFC_PLAN.md
// §6 and phase 6.
package eu.europa.ec.shared.wallet.multipaz

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.multipaz.nfc.CommandApdu
import org.multipaz.nfc.Nfc
import org.multipaz.nfc.ResponseApdu
import org.multipaz.util.fromHex
import platform.Foundation.NSData
import platform.Foundation.create
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IosNfcHceTransportTest {

    // Findings A/D of the current-state audit: the Simulator has no NFC radio, so both
    // `IosNfcHceTransport.isSupported()` and a real `start()` attempt must answer "not supported" —
    // the same real CoreNFC gate (`NFCReaderSession.readingAvailable` / `CardSession.isSupported`),
    // not a mocked one; see the file-level comment above for why this particular claim is reachable
    // from the Simulator.
    @Test
    fun `isSupported reports false on a device with no NFC hardware`() {
        assertFalse(IosNfcHceTransport.isSupported())
    }

    @Test
    fun `start reports NotSupported on a device with no NFC hardware`() = runTest {
        val transport = IosNfcHceTransport()
        val started = CompletableDeferred<NfcStartResult>()

        transport.start { started.complete(it) }

        assertEquals(NfcStartResult.NotSupported, started.await())
    }

    // Finding C of the current-state audit: a malformed/truncated command APDU — untrusted bytes,
    // straight from an NFC reader, before any authentication — must not crash the app. multipaz's
    // own `CommandApdu.decode` throws on fewer than 4 bytes; this is the shortest input that
    // reproduces that, deliberately, rather than a hand-picked "realistic" bad APDU.
    @Test
    fun `a malformed command APDU does not throw and gets a diagnostic status word back`() {
        val delegate = IosNfcHceTransport.ApduDelegate()
        val response = CompletableDeferred<ByteArray>()

        // No try/catch here: this is the assertion. If processCommandApdu still let the decode
        // failure propagate, this call itself would throw and fail the test.
        delegate.processCommandApdu(byteArrayOf(0x00).toNSData()) { responseApdu ->
            assertNotNull(responseApdu, "a diagnostic response, not silence, is the fix")
            response.complete(responseApdu.toByteArray())
        }

        val decoded = ResponseApdu.decode(response.getCompleted())
        assertEquals(Nfc.RESPONSE_STATUS_ERROR_NO_PRECISE_DIAGNOSIS, decoded.status)
    }

    // wiki/IOS_NFC_PLAN.md §3.4: NfcTransportMdoc.failTransport crashes the whole process on any
    // rejected/unexpected APDU, including a wrong-AID SELECT (NfcError, caught by processApdu's own
    // catch-all, which then calls failTransport without holding its required lock). This is the
    // fix's actual assertion: NfcTransportMdoc.processCommandApdu — the one place that bug lives —
    // must never run at all for a non-mdoc AID, not just "the response happens to be right."
    // forwardToMultipaz is the seam that makes this observable without a mocking framework: it's the
    // real NfcTransportMdoc.processCommandApdu by default, and only a test ever substitutes it.
    @Test
    fun `a SELECT for a non-mdoc AID is rejected before multipaz ever sees it`() {
        var multipazWasCalled = false
        val delegate = IosNfcHceTransport.ApduDelegate(
            forwardToMultipaz = { _, _ -> multipazWasCalled = true },
        )
        val selectForNdefAid = CommandApdu(
            cla = 0x00,
            ins = Nfc.INS_SELECT,
            p1 = Nfc.INS_SELECT_P1_APPLICATION,
            p2 = 0x00,
            payload = ByteString("D2760000850101".fromHex()),
            le = 0,
        ).encode()
        val response = CompletableDeferred<ByteArray>()

        delegate.processCommandApdu(selectForNdefAid.toNSData()) { responseApdu ->
            assertNotNull(responseApdu, "a rejection, not silence, is the fix")
            response.complete(responseApdu.toByteArray())
        }

        assertFalse(multipazWasCalled, "multipaz's own NfcTransportMdoc must never see a non-mdoc AID SELECT")
        val decoded = ResponseApdu.decode(response.getCompleted())
        assertEquals(Nfc.RESPONSE_STATUS_ERROR_FILE_OR_APPLICATION_NOT_FOUND, decoded.status)
    }

    // The necessary counterpart to the test above: confirms the filter targets the wrong-AID case
    // specifically, not `SELECT` in general — a legitimate mdoc-AID `SELECT` must still reach
    // multipaz, or this fix would silently break real presentation instead of just closing a crash.
    @Test
    fun `a SELECT for the mdoc AID is still forwarded to multipaz`() {
        var multipazWasCalled = false
        val delegate = IosNfcHceTransport.ApduDelegate(
            forwardToMultipaz = { _, sendResponse ->
                multipazWasCalled = true
                sendResponse(ResponseApdu(status = Nfc.RESPONSE_STATUS_SUCCESS).encode())
            },
        )
        val selectForMdocAid = CommandApdu(
            cla = 0x00,
            ins = Nfc.INS_SELECT,
            p1 = Nfc.INS_SELECT_P1_APPLICATION,
            p2 = 0x00,
            payload = Nfc.ISO_MDOC_NFC_DATA_TRANSFER_APPLICATION_ID,
            le = 0,
        ).encode()
        val response = CompletableDeferred<ByteArray>()

        delegate.processCommandApdu(selectForMdocAid.toNSData()) { responseApdu ->
            assertNotNull(responseApdu)
            response.complete(responseApdu.toByteArray())
        }

        assertTrue(multipazWasCalled, "the real mdoc AID must still reach multipaz")
        val decoded = ResponseApdu.decode(response.getCompleted())
        assertEquals(Nfc.RESPONSE_STATUS_SUCCESS, decoded.status)
    }
}

// A file-private copy of the same NSData<->ByteArray idiom `IosNfcHceTransport.kt` itself uses —
// deliberately not reused from there: that file's own copies are `private` (file-scoped) on
// purpose, because widening either to `internal` was tried for this test and reverted — it collided
// with an unrelated, identically-shaped `private` extension in `KeychainWalletStorage.kt`, in this
// same package ("Overload resolution ambiguity"). `private` here is file-scoped too, so this copy
// coexists with both of those without any collision.
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
