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
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodNfc
import org.multipaz.mdoc.nfc.MdocNfcEngagementHelper
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
    // must never run at all for an AID this app doesn't serve, not just "the response happens to be
    // right." A genuinely unsupported AID is used here (neither the mdoc AID nor the NDEF AID, both
    // of which are legitimately routed — see the tests below), so this test isn't accidentally
    // re-testing AID routing instead of AID rejection.
    @Test
    fun `a SELECT for an unsupported AID is rejected before either helper ever sees it`() {
        var multipazWasCalled = false
        var engagementHelperWasCalled = false
        val delegate = IosNfcHceTransport.ApduDelegate(
            forwardToMultipaz = { _, _ -> multipazWasCalled = true },
            forwardToEngagementHelper = { _, _ -> engagementHelperWasCalled = true },
        )
        val selectForUnsupportedAid = CommandApdu(
            cla = 0x00,
            ins = Nfc.INS_SELECT,
            p1 = Nfc.INS_SELECT_P1_APPLICATION,
            p2 = 0x00,
            payload = ByteString("A0000000000000".fromHex()),
            le = 0,
        ).encode()
        val response = CompletableDeferred<ByteArray>()

        delegate.processCommandApdu(selectForUnsupportedAid.toNSData()) { responseApdu ->
            assertNotNull(responseApdu, "a rejection, not silence, is the fix")
            response.complete(responseApdu.toByteArray())
        }

        assertFalse(multipazWasCalled, "multipaz's own NfcTransportMdoc must never see an unsupported AID SELECT")
        assertFalse(engagementHelperWasCalled, "the engagement helper must never see an unsupported AID SELECT")
        val decoded = ResponseApdu.decode(response.getCompleted())
        assertEquals(Nfc.RESPONSE_STATUS_ERROR_FILE_OR_APPLICATION_NOT_FOUND, decoded.status)
    }

    // The necessary counterpart to the test above: confirms the filter targets the unsupported-AID
    // case specifically, not `SELECT` in general — a legitimate mdoc-AID `SELECT` must still reach
    // multipaz, and only multipaz, or this fix would silently break real presentation instead of just
    // closing a crash.
    @Test
    fun `a SELECT for the mdoc AID is forwarded to multipaz and never to the engagement helper`() {
        var multipazWasCalled = false
        var engagementHelperWasCalled = false
        val delegate = IosNfcHceTransport.ApduDelegate(
            forwardToMultipaz = { _, sendResponse ->
                multipazWasCalled = true
                sendResponse(ResponseApdu(status = Nfc.RESPONSE_STATUS_SUCCESS).encode())
            },
            forwardToEngagementHelper = { _, _ -> engagementHelperWasCalled = true },
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
        assertFalse(engagementHelperWasCalled, "the mdoc AID must never reach the engagement helper")
        val decoded = ResponseApdu.decode(response.getCompleted())
        assertEquals(Nfc.RESPONSE_STATUS_SUCCESS, decoded.status)
    }

    // wiki/IOS_NFC_PLAN.md §9 Stage 2: the mirror image of the mdoc-AID test above — a SELECT for the
    // NDEF AID (Annex C cold-tap engagement) must reach the engagement helper, and only the engagement
    // helper, never NfcTransportMdoc. This is the routing this stage adds; the AID itself used to be
    // rejected outright (§3.4) before any engagement logic existed to serve it.
    @Test
    fun `a SELECT for the NDEF AID is forwarded to the engagement helper and never to multipaz`() {
        var multipazWasCalled = false
        var engagementHelperWasCalled = false
        val delegate = IosNfcHceTransport.ApduDelegate(
            forwardToMultipaz = { _, _ -> multipazWasCalled = true },
            forwardToEngagementHelper = { _, sendResponse ->
                engagementHelperWasCalled = true
                sendResponse(ResponseApdu(status = Nfc.RESPONSE_STATUS_SUCCESS))
            },
        )
        val selectForNdefAid = CommandApdu(
            cla = 0x00,
            ins = Nfc.INS_SELECT,
            p1 = Nfc.INS_SELECT_P1_APPLICATION,
            p2 = 0x00,
            payload = Nfc.NDEF_APPLICATION_ID,
            le = 0,
        ).encode()
        val response = CompletableDeferred<ByteArray>()

        delegate.processCommandApdu(selectForNdefAid.toNSData()) { responseApdu ->
            assertNotNull(responseApdu)
            response.complete(responseApdu.toByteArray())
        }

        assertTrue(engagementHelperWasCalled, "the NDEF AID must reach the engagement helper")
        assertFalse(multipazWasCalled, "the NDEF AID must never reach NfcTransportMdoc")
        val decoded = ResponseApdu.decode(response.getCompleted())
        assertEquals(Nfc.RESPONSE_STATUS_SUCCESS, decoded.status)
    }

    // Confirms the routing is sticky for the rest of the session, not just the SELECT itself: a
    // READ_BINARY that follows a NDEF-AID SELECT carries no AID of its own (ISO 7816-4 has no such
    // field on it), so if selectedApplication weren't tracked across calls this would have nowhere
    // correct to go.
    @Test
    fun `a READ_BINARY following a NDEF-AID SELECT still routes to the engagement helper`() {
        var engagementHelperCallCount = 0
        val delegate = IosNfcHceTransport.ApduDelegate(
            forwardToMultipaz = { _, _ -> error("must not be called") },
            forwardToEngagementHelper = { _, sendResponse ->
                engagementHelperCallCount++
                sendResponse(ResponseApdu(status = Nfc.RESPONSE_STATUS_SUCCESS))
            },
        )
        val selectForNdefAid = CommandApdu(
            cla = 0x00,
            ins = Nfc.INS_SELECT,
            p1 = Nfc.INS_SELECT_P1_APPLICATION,
            p2 = 0x00,
            payload = Nfc.NDEF_APPLICATION_ID,
            le = 0,
        ).encode()
        val readBinary = CommandApdu(
            cla = 0x00,
            ins = Nfc.INS_READ_BINARY,
            p1 = 0x00,
            p2 = 0x00,
            payload = ByteString(),
            le = 15,
        ).encode()

        delegate.processCommandApdu(selectForNdefAid.toNSData()) { }
        val response = CompletableDeferred<ByteArray>()
        delegate.processCommandApdu(readBinary.toNSData()) { responseApdu ->
            response.complete(responseApdu!!.toByteArray())
        }

        assertEquals(2, engagementHelperCallCount, "both the SELECT and the READ_BINARY must reach it")
        val decoded = ResponseApdu.decode(response.getCompleted())
        assertEquals(Nfc.RESPONSE_STATUS_SUCCESS, decoded.status)
    }

    // The other side of "sticky routing": an APDU that arrives before any SELECT APPLICATION at all
    // (no AID chosen yet) must be rejected gracefully, reaching neither helper — there is nothing to
    // route it to.
    @Test
    fun `an APDU before any application is selected reaches neither helper`() {
        var multipazWasCalled = false
        var engagementHelperWasCalled = false
        val delegate = IosNfcHceTransport.ApduDelegate(
            forwardToMultipaz = { _, _ -> multipazWasCalled = true },
            forwardToEngagementHelper = { _, _ -> engagementHelperWasCalled = true },
        )
        val readBinary = CommandApdu(
            cla = 0x00,
            ins = Nfc.INS_READ_BINARY,
            p1 = 0x00,
            p2 = 0x00,
            payload = ByteString(),
            le = 15,
        ).encode()
        val response = CompletableDeferred<ByteArray>()

        delegate.processCommandApdu(readBinary.toNSData()) { responseApdu ->
            response.complete(responseApdu!!.toByteArray())
        }

        assertFalse(multipazWasCalled)
        assertFalse(engagementHelperWasCalled)
        val decoded = ResponseApdu.decode(response.getCompleted())
        assertEquals(Nfc.RESPONSE_STATUS_ERROR_INSTRUCTION_NOT_SUPPORTED_OR_INVALID, decoded.status)
    }

    // wiki/IOS_NFC_PLAN.md §9: real-device finding, revised twice. First finding: MdocNfcEngagementHelper
    // has no protection against being called again after it has already completed a handover — nothing
    // in its own state machine remembers "onHandoverComplete already fired" — so a reader that re-SELECTs
    // the NDEF file again (observed on real hardware) re-runs its construction logic and crashes on an
    // assumption that only held the first time. The first fix cleared IosNfcHceTransport.engagementHelper
    // entirely the moment onHandoverComplete fired — but a SECOND real-device test showed that was too
    // broad: a real reader legitimately sends a trailing READ_BINARY on the NDEF file after handover
    // completes, before it notices engagement is done and switches AIDs itself, and clearing the whole
    // helper rejected that harmless request too, aborting the whole tap. MdocNfcEngagementHelper's own
    // processReadBinary is a pure, side-effect-free read of already-built state — safe to keep answering
    // regardless of how many times it's called — so the corrected fix (ndefHandoverCompleted) keeps the
    // real helper wired for exactly that, and only blocks a repeat SELECT — the actual crash trigger.
    //
    // Uses a REAL MdocNfcEngagementHelper (not a spy) end to end, proving both halves of the corrected
    // fix against the actual crash-prone Multipaz class, not just ApduDelegate's own routing logic.
    @Test
    fun `after handover completes a trailing READ_BINARY still succeeds but a repeat SELECT FILE is rejected`() =
        runTest {
            var engagementHelper: MdocNfcEngagementHelper? = null
            var ndefHandoverCompleted = false
            val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
            engagementHelper = MdocNfcEngagementHelper(
                eDeviceKey = eDeviceKey.publicKey,
                staticHandoverMethods = listOf(
                    MdocConnectionMethodNfc(
                        commandDataFieldMaxLength = 0xffff,
                        responseDataFieldMaxLength = 0x10000,
                    ),
                ),
                onHandoverComplete = { _, _, _ ->
                    // Mirrors IosProximityPresenter.onColdTapHandoverComplete's corrected fix: keep the
                    // helper wired (don't null it out) and only gate future SELECTs against it.
                    ndefHandoverCompleted = true
                },
                onError = { },
            )
            val delegate = IosNfcHceTransport.ApduDelegate(
                engagementHelperProvider = { engagementHelper },
                ndefHandoverCompletedProvider = { ndefHandoverCompleted },
            )

            suspend fun send(command: CommandApdu): ByteArray {
                val response = CompletableDeferred<ByteArray>()
                delegate.processCommandApdu(command.encode().toNSData()) { responseApdu ->
                    response.complete(responseApdu!!.toByteArray())
                }
                return response.await()
            }

            fun selectFile(fileId: Int) = CommandApdu(
                cla = 0x00,
                ins = Nfc.INS_SELECT,
                p1 = Nfc.INS_SELECT_P1_FILE,
                p2 = Nfc.INS_SELECT_P2_FILE,
                payload = ByteString((fileId shr 8).toByte(), (fileId and 0xff).toByte()),
                le = 0,
            )

            // The same sequence a real Type 4 Tag read performs once: select the NDEF application, the
            // capability container, then the NDEF file itself — the last one is where static handover
            // completes and onHandoverComplete fires (MdocNfcEngagementHelper.processSelectFile's 0xe104
            // branch; that literal is Multipaz's own magic number, not yet a named constant there either).
            send(
                CommandApdu(
                    cla = 0x00,
                    ins = Nfc.INS_SELECT,
                    p1 = Nfc.INS_SELECT_P1_APPLICATION,
                    p2 = 0x00,
                    payload = Nfc.NDEF_APPLICATION_ID,
                    le = 0,
                ),
            )
            send(selectFile(Nfc.NDEF_CAPABILITY_CONTAINER_FILE_ID))
            send(selectFile(0xe104))

            assertEquals(true, ndefHandoverCompleted)
            assertNotNull(engagementHelper, "unlike the earlier, over-broad fix, the helper stays wired")

            // The real-device regression this fix resolves: a reader's trailing READ_BINARY, sent after
            // handover completes but before it switches AIDs itself, must still be answered correctly —
            // not rejected — since MdocNfcEngagementHelper's own read logic is safe to keep serving.
            val readBinary = CommandApdu(
                cla = 0x00,
                ins = Nfc.INS_READ_BINARY,
                p1 = 0x00,
                p2 = 0x00,
                payload = ByteString(),
                le = 15,
            )
            val trailingRead = ResponseApdu.decode(send(readBinary))
            assertEquals(
                Nfc.RESPONSE_STATUS_SUCCESS,
                trailingRead.status,
                "a harmless trailing read must not be rejected",
            )

            // This morning's original crash protection must still hold: a repeat SELECT FILE is rejected
            // before ever reaching the (still-wired, but now gated) helper again — not throwing here is
            // itself part of the assertion, same as the malformed-APDU test above.
            val secondSelect = ResponseApdu.decode(send(selectFile(0xe104)))
            assertEquals(Nfc.RESPONSE_STATUS_ERROR_FILE_OR_APPLICATION_NOT_FOUND, secondSelect.status)
        }

    // The necessary counterpart: ndefHandoverCompleted is NDEF-specific state, read only inside the
    // SelectedApplication.NDEF sticky-routing branch — a SELECT for the mdoc AID must be completely
    // unaffected by it, still routing to multipaz exactly as it always has.
    @Test
    fun `a SELECT for the mdoc AID still routes to multipaz even when ndefHandoverCompleted is true`() {
        var multipazWasCalled = false
        var engagementHelperWasCalled = false
        val delegate = IosNfcHceTransport.ApduDelegate(
            forwardToMultipaz = { _, sendResponse ->
                multipazWasCalled = true
                sendResponse(ResponseApdu(status = Nfc.RESPONSE_STATUS_SUCCESS).encode())
            },
            forwardToEngagementHelper = { _, _ -> engagementHelperWasCalled = true },
            ndefHandoverCompletedProvider = { true },
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

        assertTrue(multipazWasCalled, "the mdoc AID must reach multipaz regardless of ndefHandoverCompleted")
        assertFalse(engagementHelperWasCalled)
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
