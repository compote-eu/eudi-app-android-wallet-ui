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

package eu.europa.ec.commonfeature.ui.qr_scan

import eu.europa.ec.commonfeature.config.QrScanFlow
import eu.europa.ec.commonfeature.config.QrScanUiConfig
import eu.europa.ec.commonfeature.interactor.QrScanInteractor
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.shared.platform.testPlatformContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The scanner's dispatch, which used to be gated on a platform handle that only one of its three
// flows has ever read.
//
// `Event.OnQrScanned` carries a `PlatformContext` because the signature flow hands it to the RQES
// SDK, which is an Android library. Presentation and issuance never touch it — so gating the
// dispatch applied the signature flow's limitation to the other two, and every scan on iOS did
// nothing at all. These pin that a scan is acted on with no handle, and that the signature flow
// still reaches its platform seam rather than being silently skipped.
internal class FakeQrScanInteractor(
    private val valid: Boolean = true,
) : QrScanInteractor {

    var rqesLaunches: Int = 0
        private set
    var lastRqesContext: PlatformContext? = null
        private set
    var lastRqesUri: String? = null
        private set

    override suspend fun isScannedQrValid(qr: String): Boolean = valid

    override fun launchRqesSdk(context: PlatformContext?, uri: String) {
        rqesLaunches++
        lastRqesContext = context
        lastRqesUri = uri
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class QrScanViewModelPlatformHandleTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private val context: PlatformContext = testPlatformContext()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModelFor(flow: QrScanFlow, interactor: FakeQrScanInteractor) =
        QrScanViewModel(interactor, QrScanUiConfig(qrScanFlow = flow))

    @Test
    fun a_presentation_qr_is_acted_on_without_a_platform_handle() = runTest(mainDispatcher) {
        // The regression: this is the scanner's main use, and it needs no handle whatsoever.
        val interactor = FakeQrScanInteractor()
        val viewModel = viewModelFor(QrScanFlow.Presentation, interactor)

        val effect = async { viewModel.effect.first() }
        viewModel.setEvent(
            Event.OnQrScanned(context = null, resultQr = "openid4vp://?request_uri=https://rp.test")
        )
        advanceUntilIdle()

        assertIs<Effect.Navigation.SwitchScreen>(effect.await())
        // Nothing on this path should have reached the Android-only signing seam.
        assertEquals(0, interactor.rqesLaunches)
    }

    @Test
    fun a_signature_qr_still_reaches_its_platform_seam_when_there_is_no_handle() =
        runTest(mainDispatcher) {
            // The one flow that reads the handle. It is passed on as it comes rather than used as a
            // gate, so the platform decides what to do with nothing — iOS logs that it has no remote
            // signing, which is a better answer than a scan that vanished.
            val interactor = FakeQrScanInteractor()
            val viewModel = viewModelFor(QrScanFlow.Signature, interactor)

            viewModel.setEvent(Event.OnQrScanned(context = null, resultQr = "rqes://sign?doc=1"))
            advanceUntilIdle()

            assertEquals(1, interactor.rqesLaunches)
            assertNull(interactor.lastRqesContext)
            assertEquals("rqes://sign?doc=1", interactor.lastRqesUri)
        }

    @Test
    fun a_signature_qr_forwards_the_handle_when_there_is_one() = runTest(mainDispatcher) {
        val interactor = FakeQrScanInteractor()
        val viewModel = viewModelFor(QrScanFlow.Signature, interactor)

        viewModel.setEvent(Event.OnQrScanned(context = context, resultQr = "rqes://sign?doc=2"))
        advanceUntilIdle()

        assertEquals(1, interactor.rqesLaunches)
        assertEquals(context, interactor.lastRqesContext)
    }

    @Test
    fun an_invalid_qr_is_counted_as_a_failed_scan_rather_than_navigated() = runTest(mainDispatcher) {
        val interactor = FakeQrScanInteractor(valid = false)
        val viewModel = viewModelFor(QrScanFlow.Presentation, interactor)

        viewModel.setEvent(Event.OnQrScanned(context = null, resultQr = "not-a-url"))
        advanceUntilIdle()

        // Scanning resumes, and the validity rule was what rejected it — not a missing handle.
        assertEquals(1, viewModel.viewState.value.failedScanAttempts)
        assertTrue(!viewModel.viewState.value.finishedScanning)
        assertEquals(0, interactor.rqesLaunches)
    }
}
