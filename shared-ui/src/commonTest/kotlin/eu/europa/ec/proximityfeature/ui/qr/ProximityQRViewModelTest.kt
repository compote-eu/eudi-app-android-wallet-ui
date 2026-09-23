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

package eu.europa.ec.proximityfeature.ui.qr

import eu.europa.ec.commonfeature.config.PresentationMode
import eu.europa.ec.commonfeature.config.RequestUriConfig
import eu.europa.ec.proximityfeature.interactor.ProximityQRInteractor
import eu.europa.ec.proximityfeature.interactor.ProximityQRPartialState
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.navigation.ProximityRequestRoute
import eu.europa.ec.shared.platform.PlatformActivity
import eu.europa.ec.shared.resources.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 3b: the first view-model to be shared via the platform-handle layer, so this is also the test
 * that documents the layer's one cost.
 *
 * `Event.NfcEngagement` is **not** covered, and cannot be: it carries a [PlatformActivity], which is an
 * `actual typealias` for `ComponentActivity` on Android (not constructible in a JVM unit test) and an
 * uninhabited class on iOS. That is acceptable precisely because the handle is opaque — the view-model
 * cannot do anything with it but forward it, so there is no shared logic to test. Everything the
 * view-model actually decides is covered below.
 *
 * Koin is started empty because `cleanUp()` calls `getOrNullKoinScope`, which goes through
 * `KoinPlatform.getKoin()` and throws if no application is running.
 */
@OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher, setMain, advanceUntilIdle
class ProximityQRViewModelTest {

    private class FakeProximityQRInteractor(
        private val emissions: List<ProximityQRPartialState> = emptyList(),
        private val nfcDataRetrievalAvailable: Boolean = false,
        // Finding E of the current-state audit: this is what a real platform singleton (iOS's
        // IosProximityPresenter) would already hold before the view-model ever asked — a fresh
        // view-model must read this back, not assume its own state default.
        initialNfcDataRetrievalEnabled: Boolean = false,
    ) : ProximityQRInteractor {
        override var presentationScopeId: String = "DefaultPresentationScopeId"
            private set

        var configuredWith: RequestUriConfig? = null
            private set
        var cancelTransferCalls: Int = 0
            private set
        var nfcToggles: MutableList<Boolean> = mutableListOf()
            private set
        var nfcDataRetrievalToggles: MutableList<Boolean> = mutableListOf()
            private set

        /** Incremented on every collection, i.e. every real engagement attempt — see Finding A/B/D/E
         * part 4's restart-on-toggle test. */
        var startQrEngagementCalls: Int = 0
            private set

        private var nfcDataRetrievalEnabled: Boolean = initialNfcDataRetrievalEnabled

        override fun setScopeId(scopeId: String) {
            presentationScopeId = scopeId
        }

        override fun setConfig(config: RequestUriConfig) {
            configuredWith = config
            setScopeId(config.presentationScopeId)
        }

        override fun startQrEngagement(): Flow<ProximityQRPartialState> = flow {
            startQrEngagementCalls++
            emissions.forEach { emit(it) }
        }

        override fun toggleNfcEngagement(componentActivity: PlatformActivity, toggle: Boolean) {
            nfcToggles.add(toggle)
        }

        override fun isNfcDataRetrievalAvailable(): Boolean = nfcDataRetrievalAvailable

        override fun toggleNfcDataRetrieval(enabled: Boolean) {
            nfcDataRetrievalToggles.add(enabled)
            nfcDataRetrievalEnabled = enabled
        }

        override fun isNfcDataRetrievalEnabled(): Boolean = nfcDataRetrievalEnabled

        override fun cancelTransfer() {
            cancelTransferCalls++
        }
    }

    private val mainDispatcher = UnconfinedTestDispatcher()
    private val config = RequestUriConfig(PresentationMode.Ble(DashboardRoute))

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        startKoin { }
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
        Dispatchers.resetMain()
    }

    @Test
    fun creating_the_view_model_configures_the_interactor_and_publishes_the_scope_id() =
        runTest(mainDispatcher) {
            val interactor = FakeProximityQRInteractor()
            val viewModel = ProximityQRViewModel(interactor, config)
            advanceUntilIdle()

            assertEquals(config, interactor.configuredWith)
            assertEquals(
                config.presentationScopeId,
                viewModel.viewState.value.presentationScopeId
            )
        }

    @Test
    fun a_ready_qr_code_lands_in_the_state_and_stops_the_spinner() = runTest(mainDispatcher) {
        val viewModel = ProximityQRViewModel(
            FakeProximityQRInteractor(
                listOf(ProximityQRPartialState.QrReady(qrCode = "mdoc:engagement-payload"))
            ),
            config
        )
        advanceUntilIdle()

        val state = viewModel.viewState.value
        assertEquals("mdoc:engagement-payload", state.qrCode)
        assertFalse(state.isLoading)
        assertNull(state.error)
    }

    // Option 4 (wiki/IOS_NFC_PLAN.md §9), iOS-only: cold-tap handover completed and the wallet is
    // waiting for the reader's BLE connection. Emitted after QrReady, not instead of it, so this also
    // proves Connecting genuinely re-enables the spinner QrReady had already turned off — not just that
    // it starts out true by default.
    @Test
    fun a_connecting_notice_shows_the_spinner_again_after_the_qr_was_ready() = runTest(mainDispatcher) {
        val viewModel = ProximityQRViewModel(
            FakeProximityQRInteractor(
                listOf(
                    ProximityQRPartialState.QrReady(qrCode = "mdoc:engagement-payload"),
                    ProximityQRPartialState.Connecting,
                )
            ),
            config
        )
        advanceUntilIdle()

        val state = viewModel.viewState.value
        assertTrue(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun an_engagement_failure_becomes_a_retryable_error() = runTest(mainDispatcher) {
        val viewModel = ProximityQRViewModel(
            FakeProximityQRInteractor(
                listOf(ProximityQRPartialState.Error(error = "Bluetooth is off"))
            ),
            config
        )
        advanceUntilIdle()

        val state = viewModel.viewState.value
        assertFalse(state.isLoading)
        val error = assertNotNull(state.error)
        assertEquals(UiText.Raw("Bluetooth is off"), error.errorSubTitle)
    }

    @Test
    fun a_connected_verifier_hands_the_scope_id_to_the_request_screen() = runTest(mainDispatcher) {
        val viewModel = ProximityQRViewModel(
            FakeProximityQRInteractor(listOf(ProximityQRPartialState.Connected)),
            config
        )
        val effect = async { viewModel.effect.first() }
        advanceUntilIdle()

        assertEquals(
            Effect.Navigation.SwitchScreen(
                ProximityRequestRoute(config.presentationScopeId)
            ),
            effect.await()
        )
    }

    @Test
    fun going_back_cancels_the_transfer_and_pops() = runTest(mainDispatcher) {
        val interactor = FakeProximityQRInteractor()
        val viewModel = ProximityQRViewModel(interactor, config)
        advanceUntilIdle()

        val effect = async { viewModel.effect.first() }
        viewModel.setEvent(Event.GoBack)
        advanceUntilIdle()

        assertEquals(Effect.Navigation.Pop, effect.await())
        assertEquals(1, interactor.cancelTransferCalls)
        assertNull(viewModel.viewState.value.error)
    }

    @Test
    fun a_disconnect_backs_out_of_the_screen() = runTest(mainDispatcher) {
        val interactor = FakeProximityQRInteractor(
            listOf(ProximityQRPartialState.Disconnected)
        )
        val viewModel = ProximityQRViewModel(interactor, config)
        val effect = async { viewModel.effect.first() }
        advanceUntilIdle()

        assertEquals(Effect.Navigation.Pop, effect.await())
        assertTrue(interactor.cancelTransferCalls >= 1)
    }

    @Test
    fun re_initialising_reconfigures_the_interactor() = runTest(mainDispatcher) {
        // Event.Init still exists alongside the init block, because a retry from the error state
        // re-runs it against the same view-model.
        val interactor = FakeProximityQRInteractor()
        val viewModel = ProximityQRViewModel(interactor, config)
        advanceUntilIdle()
        interactor.setScopeId("clobbered")

        viewModel.setEvent(Event.Init)
        advanceUntilIdle()

        assertEquals(config.presentationScopeId, interactor.presentationScopeId)
    }

    @Test
    fun nfc_data_retrieval_is_offered_only_when_the_interactor_reports_it_available() =
        runTest(mainDispatcher) {
            val available = ProximityQRViewModel(
                FakeProximityQRInteractor(nfcDataRetrievalAvailable = true),
                config
            )
            val unavailable = ProximityQRViewModel(
                FakeProximityQRInteractor(nfcDataRetrievalAvailable = false),
                config
            )
            advanceUntilIdle()

            assertTrue(available.viewState.value.nfcDataRetrievalAvailable)
            assertFalse(unavailable.viewState.value.nfcDataRetrievalAvailable)
        }

    @Test
    fun toggling_nfc_data_retrieval_reaches_the_interactor_and_updates_state() =
        runTest(mainDispatcher) {
            val interactor = FakeProximityQRInteractor(nfcDataRetrievalAvailable = true)
            val viewModel = ProximityQRViewModel(interactor, config)
            advanceUntilIdle()
            assertFalse(viewModel.viewState.value.nfcDataRetrievalEnabled)

            viewModel.setEvent(Event.NfcDataRetrievalToggled(enabled = true))
            advanceUntilIdle()

            assertEquals(listOf(true), interactor.nfcDataRetrievalToggles)
            assertTrue(viewModel.viewState.value.nfcDataRetrievalEnabled)

            viewModel.setEvent(Event.NfcDataRetrievalToggled(enabled = false))
            advanceUntilIdle()

            assertEquals(listOf(true, false), interactor.nfcDataRetrievalToggles)
            assertFalse(viewModel.viewState.value.nfcDataRetrievalEnabled)
        }

    // Finding E of the current-state audit.
    @Test
    fun nfc_data_retrieval_enabled_is_read_back_from_the_interactor_on_init() =
        runTest(mainDispatcher) {
            val viewModel = ProximityQRViewModel(
                FakeProximityQRInteractor(
                    nfcDataRetrievalAvailable = true,
                    initialNfcDataRetrievalEnabled = true,
                ),
                config
            )
            advanceUntilIdle()

            assertTrue(viewModel.viewState.value.nfcDataRetrievalEnabled)
        }

    // Findings A/B/D/E of the current-state audit, part 4: a toggle mid-screen must not be silently
    // inert until the next visit.
    @Test
    fun toggling_nfc_data_retrieval_restarts_the_current_engagement() = runTest(mainDispatcher) {
        val interactor = FakeProximityQRInteractor(nfcDataRetrievalAvailable = true)
        ProximityQRViewModel(interactor, config).also { viewModel ->
            advanceUntilIdle()
            assertEquals(1, interactor.startQrEngagementCalls)

            viewModel.setEvent(Event.NfcDataRetrievalToggled(enabled = true))
            advanceUntilIdle()

            assertEquals(2, interactor.startQrEngagementCalls)
        }
    }

    // Finding A/B of the current-state audit: a degraded-NFC notice reaches the screen as a
    // snackbar, without touching `state.error` — BLE is unaffected.
    @Test
    fun an_nfc_notice_becomes_a_snackbar_effect_without_touching_error_state() =
        runTest(mainDispatcher) {
            val viewModel = ProximityQRViewModel(
                FakeProximityQRInteractor(
                    listOf(ProximityQRPartialState.NfcNotice(message = "NFC unavailable"))
                ),
                config
            )
            val effect = async { viewModel.effect.first() }
            advanceUntilIdle()

            assertEquals(Effect.ShowSnackbar(message = "NFC unavailable"), effect.await())
            assertNull(viewModel.viewState.value.error)
        }
}
