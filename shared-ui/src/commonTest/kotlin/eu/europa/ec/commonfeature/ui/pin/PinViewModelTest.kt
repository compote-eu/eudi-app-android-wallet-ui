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

package eu.europa.ec.commonfeature.ui.pin

import eu.europa.ec.authenticationlogic.provider.PinLockoutState
import eu.europa.ec.authenticationlogic.secure.SecurePin
import eu.europa.ec.authenticationlogic.secure.SecurePinData
import eu.europa.ec.commonfeature.config.SuccessUIConfig
import eu.europa.ec.commonfeature.interactor.QuickPinInteractor
import eu.europa.ec.commonfeature.interactor.QuickPinInteractorPinValidPartialState
import eu.europa.ec.commonfeature.interactor.QuickPinInteractorSetPinPartialState
import eu.europa.ec.commonfeature.model.PinFlow
import eu.europa.ec.shared.navigation.AddDocumentRoute
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.navigation.SuccessRoute
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.shared.resources.quick_pin_locked_out
import eu.europa.ec.uilogic.component.content.ScreenNavigateAction
import eu.europa.ec.uilogic.config.NavigationType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 3b: the PIN rules — enter/re-enter/validate, the mismatch retry and the lockout countdown — are
 * now shared, so this is where they are pinned for both platforms.
 *
 * The fake [SecurePin] below is the point of the contract split made to move this view-model: the real
 * `SecurePinImpl` is Android-only (its mutual exclusion is `@Synchronized`), and nothing here needs it,
 * because the view-model only ever forwards the PIN as an opaque single-use handle. The tests assert it
 * is *closed* on the paths that must not leak it.
 *
 * `Dispatchers.setMain` is shared with `runTest` so the view-model's `artificialDelay` and the
 * one-second lockout tick run on the same virtual clock. Effects are collected before the triggering
 * event because `MviViewModel._effect` is a RENDEZVOUS `Channel`.
 */
@OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher, setMain, advanceTimeBy/UntilIdle
class PinViewModelTest {

    private class FakeSecurePin(private val value: String) : SecurePin {
        var closed: Boolean = false
            private set

        override val length: Int = value.length
        override val isCleared: Boolean get() = closed

        override fun getAndClear(): SecurePinData =
            throw UnsupportedOperationException("not needed by the view-model")

        override fun getAndClearAsString(): String {
            closed = true
            return value
        }

        override fun contentEquals(other: SecurePin): Boolean =
            other is FakeSecurePin && other.value == value

        override fun close() {
            closed = true
        }
    }

    private class FakeQuickPinInteractor(
        override val maxFailedPinAttempts: Int = 3,
        private val setPinResult: QuickPinInteractorSetPinPartialState =
            QuickPinInteractorSetPinPartialState.Success,
        private val pinValidResult: QuickPinInteractorPinValidPartialState =
            QuickPinInteractorPinValidPartialState.Success,
        private val lockoutAfterFailure: PinLockoutState = PinLockoutState.Idle,
        private val lockoutOnEntry: PinLockoutState = PinLockoutState.Idle,
    ) : QuickPinInteractor {
        var throttleResets: Int = 0
            private set

        override fun setPin(
            newPin: SecurePin,
            initialPin: SecurePin
        ): Flow<QuickPinInteractorSetPinPartialState> = flow { emit(setPinResult) }

        override fun changePin(
            newPin: SecurePin
        ): Flow<QuickPinInteractorSetPinPartialState> = flow { emit(setPinResult) }

        override fun isCurrentPinValid(
            pin: SecurePin
        ): Flow<QuickPinInteractorPinValidPartialState> = flow { emit(pinValidResult) }

        override suspend fun hasPin(): Boolean = true

        override suspend fun getPinLockoutState(): PinLockoutState = lockoutOnEntry

        override suspend fun recordPinFailure(): PinLockoutState = lockoutAfterFailure

        override suspend fun resetPinThrottle() {
            throttleResets++
        }
    }

    private companion object {
        /**
         * Just past the view-model's `artificialDelay(500)` on the wrong-PIN path, so the lockout has
         * been established but its one-second tick has not run yet.
         */
        const val WRONG_PIN_SETTLE_MS = 501L
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
    fun creating_a_pin_starts_in_the_enter_phase_and_cannot_be_navigated_back_out_of() =
        runTest(mainDispatcher) {
            val viewModel =
                PinViewModel(FakeQuickPinInteractor(), PinFlow.CREATE_WITH_ACTIVATION)
            advanceUntilIdle()

            val state = viewModel.viewState.value
            assertEquals(PinValidationState.ENTER, state.pinState)
            assertEquals(ScreenNavigateAction.NONE, state.action)
            assertEquals(Event.Finish, state.onBackEvent)
            assertFalse(state.isLockedOut)
        }

    @Test
    fun cancelling_pops_only_once_the_sheet_has_finished_closing() = runTest(mainDispatcher) {
        val viewModel = PinViewModel(FakeQuickPinInteractor(), PinFlow.UPDATE)
        advanceUntilIdle()

        val effects = mutableListOf<Effect>()
        val job = launch { viewModel.effect.collect { effects.add(it) } }

        viewModel.setEvent(Event.BottomSheet.Cancel.SecondaryButtonPressed)
        advanceUntilIdle()

        // The pop used to fire after a fixed artificialDelay(200), which raced the hide animation.
        assertTrue(viewModel.viewState.value.bottomSheetClosingInProgress)
        assertTrue(effects.none { it is Effect.Navigation })
        assertTrue(effects.any { it is Effect.CloseBottomSheet })

        viewModel.setEvent(Event.BottomSheet.FinishedClosing)
        advanceUntilIdle()
        job.cancel()

        assertFalse(viewModel.viewState.value.bottomSheetClosingInProgress)
        assertIs<Effect.Navigation.Pop>(effects.last { it is Effect.Navigation })
    }

    @Test
    fun cancel_is_ignored_while_the_sheet_is_closing() = runTest(mainDispatcher) {
        val viewModel = PinViewModel(FakeQuickPinInteractor(), PinFlow.UPDATE)
        advanceUntilIdle()

        viewModel.setEvent(Event.BottomSheet.Cancel.SecondaryButtonPressed)
        advanceUntilIdle()

        val effects = mutableListOf<Effect>()
        val job = launch { viewModel.effect.collect { effects.add(it) } }
        // Would otherwise reopen the confirmation sheet and abandon the pending exit.
        viewModel.setEvent(Event.CancelPressed)
        advanceUntilIdle()
        job.cancel()

        assertTrue(effects.none { it is Effect.ShowBottomSheet })
    }

    @Test
    fun reopening_the_sheet_clears_the_closing_guard() = runTest(mainDispatcher) {
        val viewModel = PinViewModel(FakeQuickPinInteractor(), PinFlow.UPDATE)
        advanceUntilIdle()

        viewModel.setEvent(Event.BottomSheet.Cancel.SecondaryButtonPressed)
        advanceUntilIdle()
        assertTrue(viewModel.viewState.value.bottomSheetClosingInProgress)

        viewModel.setEvent(Event.BottomSheet.UpdateBottomSheetState(isOpen = true))
        advanceUntilIdle()

        assertFalse(viewModel.viewState.value.bottomSheetClosingInProgress)
    }

    @Test
    fun changing_a_pin_starts_by_validating_the_current_one_and_is_cancelable() =
        runTest(mainDispatcher) {
            val viewModel = PinViewModel(FakeQuickPinInteractor(), PinFlow.UPDATE)
            advanceUntilIdle()

            val state = viewModel.viewState.value
            assertEquals(PinValidationState.VALIDATE, state.pinState)
            assertEquals(ScreenNavigateAction.CANCELABLE, state.action)
            assertEquals(Event.CancelPressed, state.onBackEvent)
        }

    @Test
    fun the_first_entry_of_a_new_pin_moves_on_to_the_reenter_phase() = runTest(mainDispatcher) {
        val viewModel = PinViewModel(FakeQuickPinInteractor(), PinFlow.CREATE_WITH_ACTIVATION)

        viewModel.setEvent(Event.PinEntered(FakeSecurePin("123456")))
        advanceUntilIdle()

        val state = viewModel.viewState.value
        assertEquals(PinValidationState.REENTER, state.pinState)
        assertTrue(state.resetPin)
        assertFalse(state.isLoading)
        assertNull(state.quickPinError)
    }

    @Test
    fun a_matching_reentry_stores_the_pin_and_navigates_to_the_success_screen() =
        runTest(mainDispatcher) {
            val viewModel =
                PinViewModel(FakeQuickPinInteractor(), PinFlow.CREATE_WITH_ACTIVATION)

            viewModel.setEvent(Event.PinEntered(FakeSecurePin("123456")))
            advanceUntilIdle()

            val effect = async { viewModel.effect.first() }
            viewModel.setEvent(Event.PinEntered(FakeSecurePin("123456")))
            advanceUntilIdle()

            val switch = assertIs<Effect.Navigation.SwitchScreen>(effect.await())
            val route = assertIs<SuccessRoute>(switch.route)
            // The activation flow's success button has to lead into document issuance.
            val navigation = route.config.buttonConfig.single().navigation.navigationType
            val push = assertIs<NavigationType.PushRoute>(navigation)
            assertIs<AddDocumentRoute>(push.route)
        }

    @Test
    fun a_mismatching_reentry_surfaces_the_interactors_error_and_stays_put() =
        runTest(mainDispatcher) {
            val interactor = FakeQuickPinInteractor(
                setPinResult = QuickPinInteractorSetPinPartialState.Failed("PINs do not match")
            )
            val viewModel = PinViewModel(interactor, PinFlow.CREATE_WITH_ACTIVATION)

            viewModel.setEvent(Event.PinEntered(FakeSecurePin("123456")))
            advanceUntilIdle()
            viewModel.setEvent(Event.PinEntered(FakeSecurePin("654321")))
            advanceUntilIdle()

            val state = viewModel.viewState.value
            assertEquals("PINs do not match", state.quickPinError)
            assertEquals(PinValidationState.REENTER, state.pinState)
            assertFalse(state.isLoading)
        }

    @Test
    fun a_valid_current_pin_clears_the_throttle_and_opens_the_enter_phase() =
        runTest(mainDispatcher) {
            val interactor = FakeQuickPinInteractor(
                pinValidResult = QuickPinInteractorPinValidPartialState.Success
            )
            val viewModel = PinViewModel(interactor, PinFlow.UPDATE)

            viewModel.setEvent(Event.PinEntered(FakeSecurePin("111111")))
            advanceUntilIdle()

            val state = viewModel.viewState.value
            assertEquals(PinValidationState.ENTER, state.pinState)
            assertTrue(state.resetPin)
            assertEquals(1, interactor.throttleResets)
        }

    @Test
    fun a_wrong_current_pin_shows_the_error_while_attempts_remain() = runTest(mainDispatcher) {
        val interactor = FakeQuickPinInteractor(
            pinValidResult = QuickPinInteractorPinValidPartialState.Failed("Wrong PIN"),
            lockoutAfterFailure = PinLockoutState.Idle
        )
        val viewModel = PinViewModel(interactor, PinFlow.UPDATE)

        viewModel.setEvent(Event.PinEntered(FakeSecurePin("000000")))
        advanceUntilIdle()

        val state = viewModel.viewState.value
        assertEquals("Wrong PIN", state.quickPinError)
        assertFalse(state.isLockedOut)
        assertEquals(PinValidationState.VALIDATE, state.pinState)
    }

    @Test
    fun exhausting_the_attempts_locks_the_screen_and_counts_down_in_mm_ss() =
        runTest(mainDispatcher) {
            val interactor = FakeQuickPinInteractor(
                maxFailedPinAttempts = 3,
                pinValidResult = QuickPinInteractorPinValidPartialState.Failed("Wrong PIN"),
                lockoutAfterFailure = PinLockoutState.Active(
                    remaining = 65.seconds,
                    total = 90.seconds
                )
            )
            val viewModel = PinViewModel(interactor, PinFlow.UPDATE)

            viewModel.setEvent(Event.PinEntered(FakeSecurePin("000000")))
            // Deliberately NOT advanceUntilIdle: that would run the whole countdown on the virtual
            // clock and the tick would clear the lockout again. Just past `artificialDelay(500)`.
            advanceTimeBy(WRONG_PIN_SETTLE_MS)

            // Two-digit minutes and seconds, formatted without `String.format`, which is JVM-only.
            assertTrue(viewModel.viewState.value.isLockedOut)
            assertEquals(
                UiText.Resource(Res.string.quick_pin_locked_out, 3, "01:05"),
                viewModel.viewState.value.lockoutMessage
            )
            assertNull(viewModel.viewState.value.quickPinError)

            advanceTimeBy(1_001L)
            assertEquals(
                UiText.Resource(Res.string.quick_pin_locked_out, 3, "01:04"),
                viewModel.viewState.value.lockoutMessage
            )
        }

    @Test
    fun the_lockout_clears_itself_when_the_countdown_runs_out() = runTest(mainDispatcher) {
        val interactor = FakeQuickPinInteractor(
            pinValidResult = QuickPinInteractorPinValidPartialState.Failed("Wrong PIN"),
            lockoutAfterFailure = PinLockoutState.Active(
                remaining = 2.seconds,
                total = 30.seconds
            )
        )
        val viewModel = PinViewModel(interactor, PinFlow.UPDATE)

        viewModel.setEvent(Event.PinEntered(FakeSecurePin("000000")))
        advanceTimeBy(WRONG_PIN_SETTLE_MS)
        assertTrue(viewModel.viewState.value.isLockedOut)

        // Now let the two-second countdown run out.
        advanceUntilIdle()

        assertFalse(viewModel.viewState.value.isLockedOut)
        assertNull(viewModel.viewState.value.lockoutMessage)
    }

    @Test
    fun a_pin_entered_while_locked_out_is_ignored_and_closed() = runTest(mainDispatcher) {
        val interactor = FakeQuickPinInteractor(
            pinValidResult = QuickPinInteractorPinValidPartialState.Failed("Wrong PIN"),
            lockoutAfterFailure = PinLockoutState.Active(
                remaining = 30.seconds,
                total = 30.seconds
            )
        )
        val viewModel = PinViewModel(interactor, PinFlow.UPDATE)
        viewModel.setEvent(Event.PinEntered(FakeSecurePin("000000")))
        advanceTimeBy(WRONG_PIN_SETTLE_MS)
        assertTrue(viewModel.viewState.value.isLockedOut)

        val ignored = FakeSecurePin("111111")
        viewModel.setEvent(Event.PinEntered(ignored))
        advanceTimeBy(1L)

        assertTrue(ignored.closed, "a PIN rejected by the lockout must still be cleared")
        assertEquals(PinValidationState.VALIDATE, viewModel.viewState.value.pinState)
    }

    @Test
    fun an_active_lockout_is_restored_when_the_view_model_is_recreated() =
        runTest(mainDispatcher) {
            // The `init` path: after process death the screen must come back still locked, since the
            // event that used to drive this came from a `rememberSaveable`-guarded launched effect.
            val interactor = FakeQuickPinInteractor(
                maxFailedPinAttempts = 3,
                lockoutOnEntry = PinLockoutState.Active(
                    remaining = 14.seconds,
                    total = 30.seconds
                )
            )
            val viewModel = PinViewModel(interactor, PinFlow.UPDATE)
            advanceTimeBy(1L)

            assertTrue(viewModel.viewState.value.isLockedOut)
            assertEquals(
                UiText.Resource(Res.string.quick_pin_locked_out, 3, "00:14"),
                viewModel.viewState.value.lockoutMessage
            )
        }

    @Test
    fun a_restored_lockout_is_not_applied_to_the_create_flow() = runTest(mainDispatcher) {
        // Only the VALIDATE phase can be locked out; creating a PIN has nothing to throttle.
        val interactor = FakeQuickPinInteractor(
            lockoutOnEntry = PinLockoutState.Active(remaining = 30.seconds, total = 30.seconds)
        )
        val viewModel = PinViewModel(interactor, PinFlow.CREATE_WITH_ACTIVATION)
        advanceUntilIdle()

        assertFalse(viewModel.viewState.value.isLockedOut)
    }

    @Test
    fun cancelling_the_change_flow_clears_the_pending_pin_and_pops() = runTest(mainDispatcher) {
        val viewModel = PinViewModel(FakeQuickPinInteractor(), PinFlow.UPDATE)
        val pending = FakeSecurePin("123456")

        // Reach the re-enter phase so there is a pending PIN held by the view-model.
        viewModel.setEvent(Event.PinEntered(FakeSecurePin("111111")))
        advanceUntilIdle()
        viewModel.setEvent(Event.PinEntered(pending))
        advanceUntilIdle()

        val effect = async { viewModel.effect.first() }
        viewModel.setEvent(Event.BottomSheet.Cancel.SecondaryButtonPressed)
        advanceUntilIdle()

        assertEquals(Effect.CloseBottomSheet, effect.await())
        assertTrue(pending.closed, "the pending PIN must be cleared when the flow is abandoned")
    }

    @Test
    fun finishing_clears_the_pending_pin_and_leaves_the_flow() = runTest(mainDispatcher) {
        val viewModel = PinViewModel(FakeQuickPinInteractor(), PinFlow.CREATE_WITH_ACTIVATION)
        val pending = FakeSecurePin("123456")
        viewModel.setEvent(Event.PinEntered(pending))
        advanceUntilIdle()

        val effect = async { viewModel.effect.first() }
        viewModel.setEvent(Event.Finish)
        advanceUntilIdle()

        assertEquals(Effect.Navigation.Finish, effect.await())
        assertTrue(pending.closed)
    }

    @Test
    fun changing_the_pin_length_clears_a_previous_error() = runTest(mainDispatcher) {
        val interactor = FakeQuickPinInteractor(
            pinValidResult = QuickPinInteractorPinValidPartialState.Failed("Wrong PIN")
        )
        val viewModel = PinViewModel(interactor, PinFlow.UPDATE)
        viewModel.setEvent(Event.PinEntered(FakeSecurePin("000000")))
        advanceUntilIdle()
        assertEquals("Wrong PIN", viewModel.viewState.value.quickPinError)

        viewModel.setEvent(Event.OnQuickPinLengthChanged)
        advanceUntilIdle()

        assertNull(viewModel.viewState.value.quickPinError)
        assertFalse(viewModel.viewState.value.resetPin)
    }

    @Test
    fun the_change_flow_lands_back_on_the_dashboard_after_success() = runTest(mainDispatcher) {
        val viewModel = PinViewModel(FakeQuickPinInteractor(), PinFlow.UPDATE)

        viewModel.setEvent(Event.PinEntered(FakeSecurePin("111111")))
        advanceUntilIdle()
        viewModel.setEvent(Event.PinEntered(FakeSecurePin("222222")))
        advanceUntilIdle()

        val effect = async { viewModel.effect.first() }
        viewModel.setEvent(Event.PinEntered(FakeSecurePin("222222")))
        advanceUntilIdle()

        val switch = assertIs<Effect.Navigation.SwitchScreen>(effect.await())
        val route = assertIs<SuccessRoute>(switch.route)
        val navigation = route.config.onBackScreenToNavigate.navigationType
        assertEquals(NavigationType.PopTo(DashboardRoute), navigation)
        assertIs<SuccessUIConfig.ImageConfig>(route.config.imageConfig)
    }
}
