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

package eu.europa.ec.dashboardfeature.ui.home

import eu.europa.ec.commonfeature.config.PresentationMode
import eu.europa.ec.commonfeature.config.QrScanFlow
import eu.europa.ec.commonfeature.config.QrScanUiConfig
import eu.europa.ec.commonfeature.config.RequestUriConfig
import eu.europa.ec.dashboardfeature.interactor.HomeInteractor
import eu.europa.ec.dashboardfeature.interactor.HomeInteractorGetUserNameViaMainPidDocumentPartialState
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.navigation.DocumentSignRoute
import eu.europa.ec.shared.navigation.ProximityQrRoute
import eu.europa.ec.shared.navigation.QrScanRoute
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.shared.resources.home_screen_welcome
import eu.europa.ec.shared.resources.home_screen_welcome_user_message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 3b: Home is the first shared view-model whose whole contract is *routing*, so these tests are
 * the ones that stop a route from being cross-wired on either platform — each assertion names the
 * typed [eu.europa.ec.shared.navigation.AppRoute] and the exact config payload it must carry.
 *
 * The welcome-message cases also guard the `init` block: it exists precisely because the `Event.Init`
 * that used to drive it came from an `OneTimeLaunchedEffect` whose "already ran" flag is
 * `rememberSaveable`, so after process death the event was never re-sent and Home came back reading a
 * bare "Welcome". Nothing about that regression is visible in a screenshot of a healthy launch, so it
 * is pinned here instead.
 *
 * `Dispatchers.setMain` is required because `viewModelScope` dispatches on Main, and the dispatcher is
 * shared with `runTest` so both run on one virtual clock. Effects are collected *before* the event is
 * sent because `MviViewModel._effect` is a RENDEZVOUS `Channel` — a send with no waiting receiver
 * parks forever. Where an event emits two effects, the ordered list is asserted: `setEffect` launches
 * on the unconfined dispatcher, so the sends complete in call order.
 */
@OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher, setMain, advanceUntilIdle
class HomeViewModelTest {

    private class FakeHomeInteractor(
        private val userName: HomeInteractorGetUserNameViaMainPidDocumentPartialState =
            HomeInteractorGetUserNameViaMainPidDocumentPartialState.Success(userFirstName = ""),
        private val bleAvailable: Boolean = true,
        private val bleCentralClientMode: Boolean = false,
    ) : HomeInteractor {
        override fun isBleAvailable(): Boolean = bleAvailable

        override fun isBleCentralClientModeEnabled(): Boolean = bleCentralClientMode

        override fun getUserNameViaMainPidDocument():
                Flow<HomeInteractorGetUserNameViaMainPidDocumentPartialState> = flow {
            emit(userName)
        }
    }

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun the_welcome_message_greets_the_name_on_the_main_pid() = runTest(mainDispatcher) {
        val viewModel = HomeViewModel(
            FakeHomeInteractor(
                userName = HomeInteractorGetUserNameViaMainPidDocumentPartialState.Success(
                    userFirstName = "Alex"
                )
            )
        )
        advanceUntilIdle()

        assertEquals(
            UiText.Resource(Res.string.home_screen_welcome_user_message, "Alex"),
            viewModel.viewState.value.welcomeUserMessage
        )
        assertFalse(viewModel.viewState.value.isLoading)
    }

    @Test
    fun a_pid_without_a_first_name_falls_back_to_the_plain_welcome() = runTest(mainDispatcher) {
        val viewModel = HomeViewModel(
            FakeHomeInteractor(
                userName = HomeInteractorGetUserNameViaMainPidDocumentPartialState.Success(
                    userFirstName = "   "
                )
            )
        )
        advanceUntilIdle()

        assertEquals(
            UiText.Resource(Res.string.home_screen_welcome),
            viewModel.viewState.value.welcomeUserMessage
        )
    }

    @Test
    fun a_failed_lookup_stops_loading_and_leaves_the_plain_welcome() = runTest(mainDispatcher) {
        val viewModel = HomeViewModel(
            FakeHomeInteractor(
                userName = HomeInteractorGetUserNameViaMainPidDocumentPartialState.Failure(
                    error = "no wallet"
                )
            )
        )
        advanceUntilIdle()

        assertEquals(
            UiText.Resource(Res.string.home_screen_welcome),
            viewModel.viewState.value.welcomeUserMessage
        )
        assertFalse(viewModel.viewState.value.isLoading)
    }

    @Test
    fun the_initial_state_takes_ble_central_client_mode_from_the_interactor() =
        runTest(mainDispatcher) {
            val viewModel = HomeViewModel(FakeHomeInteractor(bleCentralClientMode = true))
            advanceUntilIdle()

            assertTrue(viewModel.viewState.value.isBleCentralClientModeEnabled)
        }

    @Test
    fun authenticating_online_closes_the_sheet_and_opens_the_presentation_scanner() =
        runTest(mainDispatcher) {
            val viewModel = HomeViewModel(FakeHomeInteractor())
            val effects = async { viewModel.effect.take(2).toList() }

            viewModel.setEvent(Event.BottomSheet.Authenticate.OpenAuthenticateOnLine)
            advanceUntilIdle()

            assertEquals(
                listOf(
                    Effect.CloseBottomSheet(hasNextBottomSheet = false),
                    Effect.Navigation.SwitchScreen(
                        route = QrScanRoute(QrScanUiConfig(qrScanFlow = QrScanFlow.Presentation))
                    ),
                ),
                effects.await()
            )
        }

    @Test
    fun scanning_to_sign_opens_the_scanner_in_the_signature_flow() = runTest(mainDispatcher) {
        val viewModel = HomeViewModel(FakeHomeInteractor())
        val effects = async { viewModel.effect.take(2).toList() }

        viewModel.setEvent(Event.BottomSheet.SignDocument.OpenScanQR)
        advanceUntilIdle()

        assertEquals(
            listOf(
                Effect.CloseBottomSheet(hasNextBottomSheet = false),
                Effect.Navigation.SwitchScreen(
                    route = QrScanRoute(QrScanUiConfig(qrScanFlow = QrScanFlow.Signature))
                ),
            ),
            effects.await()
        )
    }

    @Test
    fun signing_from_the_device_goes_straight_to_document_sign() = runTest(mainDispatcher) {
        val viewModel = HomeViewModel(FakeHomeInteractor())
        val effects = async { viewModel.effect.take(2).toList() }

        viewModel.setEvent(Event.BottomSheet.SignDocument.OpenFromDevice)
        advanceUntilIdle()

        assertEquals(
            listOf(
                Effect.CloseBottomSheet(hasNextBottomSheet = false),
                Effect.Navigation.SwitchScreen(route = DocumentSignRoute),
            ),
            effects.await()
        )
    }

    @Test
    fun starting_the_proximity_flow_asks_for_a_ble_presentation_that_returns_to_the_dashboard() =
        runTest(mainDispatcher) {
            val viewModel = HomeViewModel(FakeHomeInteractor())
            val effects = async { viewModel.effect.take(2).toList() }

            viewModel.setEvent(Event.StartProximityFlow)
            advanceUntilIdle()

            assertEquals(
                listOf(
                    Effect.CloseBottomSheet(hasNextBottomSheet = false),
                    Effect.Navigation.SwitchScreen(
                        route = ProximityQrRoute(
                            RequestUriConfig(PresentationMode.Ble(DashboardRoute))
                        )
                    ),
                ),
                effects.await()
            )
            assertEquals(BleAvailability.AVAILABLE, viewModel.viewState.value.bleAvailability)
        }

    @Test
    fun authenticating_in_person_with_bluetooth_on_moves_on_to_asking_for_permissions() =
        runTest(mainDispatcher) {
            val viewModel = HomeViewModel(FakeHomeInteractor(bleAvailable = true))

            viewModel.setEvent(Event.BottomSheet.Authenticate.OpenAuthenticateInPerson)
            advanceUntilIdle()

            assertEquals(BleAvailability.NO_PERMISSION, viewModel.viewState.value.bleAvailability)
        }

    @Test
    fun authenticating_in_person_with_bluetooth_off_offers_to_turn_it_on() =
        runTest(mainDispatcher) {
            val viewModel = HomeViewModel(FakeHomeInteractor(bleAvailable = false))
            val effects = async { viewModel.effect.take(2).toList() }

            viewModel.setEvent(Event.BottomSheet.Authenticate.OpenAuthenticateInPerson)
            advanceUntilIdle()

            assertEquals(
                listOf(
                    Effect.CloseBottomSheet(hasNextBottomSheet = true),
                    Effect.ShowBottomSheet,
                ),
                effects.await()
            )
            assertEquals(BleAvailability.DISABLED, viewModel.viewState.value.bleAvailability)
            assertEquals(
                HomeScreenBottomSheetContent.Bluetooth(BleAvailability.DISABLED),
                viewModel.viewState.value.sheetContent
            )
        }

    @Test
    fun a_missing_ble_permission_sends_the_user_to_the_app_settings() = runTest(mainDispatcher) {
        val viewModel = HomeViewModel(FakeHomeInteractor())
        val effects = async { viewModel.effect.take(2).toList() }

        viewModel.setEvent(
            Event.BottomSheet.Bluetooth.PrimaryButtonPressed(BleAvailability.NO_PERMISSION)
        )
        advanceUntilIdle()

        assertEquals(
            listOf(
                Effect.CloseBottomSheet(hasNextBottomSheet = false),
                Effect.Navigation.OnAppSettings,
            ),
            effects.await()
        )
    }

    @Test
    fun bluetooth_switched_off_sends_the_user_to_the_system_settings() = runTest(mainDispatcher) {
        val viewModel = HomeViewModel(FakeHomeInteractor())
        val effects = async { viewModel.effect.take(2).toList() }

        viewModel.setEvent(
            Event.BottomSheet.Bluetooth.PrimaryButtonPressed(BleAvailability.DISABLED)
        )
        advanceUntilIdle()

        assertEquals(
            listOf(
                Effect.CloseBottomSheet(hasNextBottomSheet = false),
                Effect.Navigation.OnSystemSettings,
            ),
            effects.await()
        )
    }
}
