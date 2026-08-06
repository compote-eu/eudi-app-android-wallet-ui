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

// BiometricViewModel — the wallet's login gate, and the last shared view-model to get coverage.
//
// The PIN-fallback half is all here: it needs no platform handle, and it is the security-critical
// half. Two properties matter most and are asserted directly:
//
//   * the `SecurePin` is CLOSED on every path that does not consume it (locked out, wrong length),
//     because it is a single-use handle over secret material;
//   * a lockout genuinely blocks entry rather than merely displaying a message.
//
// The biometric-prompt half needs a `PlatformContext` and lives in BiometricViewModelAndroidTest.
package eu.europa.ec.commonfeature.ui.biometric

import eu.europa.ec.authenticationlogic.provider.PinLockoutState
import eu.europa.ec.authenticationlogic.secure.SecurePin
import eu.europa.ec.authenticationlogic.secure.SecurePinData
import eu.europa.ec.commonfeature.config.BiometricMode
import eu.europa.ec.commonfeature.config.BiometricUiConfig
import eu.europa.ec.commonfeature.config.OnBackNavigationConfig
import eu.europa.ec.commonfeature.interactor.QuickPinInteractorPinValidPartialState
import eu.europa.ec.dashboardfeature.ui.settings.FakeBiometricInteractor
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.uilogic.config.ConfigNavigation
import eu.europa.ec.uilogic.config.NavigationType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Single-use handle over the PIN; the tests assert it is closed rather than leaked. */
internal class FakeSecurePin(private val value: String) : SecurePin {
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

@OptIn(ExperimentalCoroutinesApi::class)
class BiometricViewModelTest {

    internal companion object {
        fun config(
            biometricsEnabledOnCreate: Boolean = false,
            backNavigation: ConfigNavigation? = ConfigNavigation(
                navigationType = NavigationType.PopTo(DashboardRoute)
            ),
        ) = BiometricUiConfig(
            mode = BiometricMode.Default(
                descriptionWhenBiometricsEnabled = UiText.Raw("use biometrics"),
                descriptionWhenBiometricsNotEnabled = UiText.Raw("enter your PIN"),
                textAbovePin = UiText.Raw("PIN"),
            ),
            isPreAuthorization = false,
            shouldInitializeBiometricAuthOnCreate = biometricsEnabledOnCreate,
            onSuccessNavigation = ConfigNavigation(
                navigationType = NavigationType.PushRoute(DashboardRoute)
            ),
            onBackNavigationConfig = OnBackNavigationConfig(
                onBackNavigation = backNavigation,
                hasToolbarBackIcon = true,
            ),
        )
    }

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun the_users_biometric_preference_is_read_on_construction() = runTest(mainDispatcher) {
        val viewModel = BiometricViewModel(
            FakeBiometricInteractor(biometricUserSelection = true),
            config(),
        )
        advanceUntilIdle()

        // From `init`, so it survives process death — see HomeViewModel's note.
        assertTrue(viewModel.viewState.value.userBiometricsAreEnabled)
        assertFalse(viewModel.viewState.value.isLockedOut)
    }

    @Test
    fun the_prompt_is_requested_on_create_only_when_configured_and_enabled() =
        runTest(mainDispatcher) {
            val viewModel = BiometricViewModel(
                FakeBiometricInteractor(biometricUserSelection = true),
                config(biometricsEnabledOnCreate = true),
            )
            val effect = async { viewModel.effect.first() }
            advanceUntilIdle()

            assertIs<Effect.InitializeBiometricAuthOnCreate>(effect.await())
        }

    @Test
    fun no_prompt_on_create_when_the_user_has_biometrics_off() = runTest(mainDispatcher) {
        val viewModel = BiometricViewModel(
            FakeBiometricInteractor(biometricUserSelection = false),
            config(biometricsEnabledOnCreate = true),
        )
        advanceUntilIdle()

        assertFalse(viewModel.viewState.value.userBiometricsAreEnabled)
        // Nothing was emitted, so the next event is what a collector sees.
        val effect = async { viewModel.effect.first() }
        viewModel.setEvent(Event.OnNavigateBack)
        advanceUntilIdle()
        assertIs<Effect.Navigation.PopBackStackUpTo>(effect.await())
    }

    @Test
    fun entering_the_wrong_pin_shows_the_error_and_keeps_the_screen_usable() =
        runTest(mainDispatcher) {
            val viewModel = BiometricViewModel(
                FakeBiometricInteractor(
                    pinValidResult = QuickPinInteractorPinValidPartialState.Failed("wrong PIN"),
                ),
                config(),
            )
            advanceUntilIdle()

            viewModel.setEvent(Event.OnQuickPinEntered(FakeSecurePin("123456")))
            advanceUntilIdle()

            val state = viewModel.viewState.value
            assertEquals("wrong PIN", state.quickPinError)
            assertFalse(state.isLoading)
            assertFalse(state.isLockedOut)
        }

    @Test
    fun a_correct_pin_resets_the_throttle_and_navigates_on() = runTest(mainDispatcher) {
        val fake = FakeBiometricInteractor(
            pinValidResult = QuickPinInteractorPinValidPartialState.Success,
        )
        val viewModel = BiometricViewModel(fake, config())
        advanceUntilIdle()

        val effect = async { viewModel.effect.first() }
        viewModel.setEvent(Event.OnQuickPinEntered(FakeSecurePin("123456")))
        advanceUntilIdle()

        val navigation = assertIs<Effect.Navigation.SwitchScreen>(effect.await())
        assertEquals(DashboardRoute, navigation.route)
        assertEquals(1, fake.throttleResets)
    }

    @Test
    fun a_pin_of_the_wrong_length_is_closed_and_never_submitted() = runTest(mainDispatcher) {
        val fake = FakeBiometricInteractor()
        val viewModel = BiometricViewModel(fake, config())
        advanceUntilIdle()

        val pin = FakeSecurePin("12")   // quickPinSize is 6
        viewModel.setEvent(Event.OnQuickPinEntered(pin))
        advanceUntilIdle()

        // Closed rather than leaked, and no validation attempt was made.
        assertTrue(pin.closed)
        assertFalse(viewModel.viewState.value.isLoading)
        assertEquals(0, fake.throttleResets)
    }

    @Test
    fun too_many_failures_lock_the_screen_out_with_a_countdown() = runTest(mainDispatcher) {
        val viewModel = BiometricViewModel(
            FakeBiometricInteractor(
                pinValidResult = QuickPinInteractorPinValidPartialState.Failed("wrong"),
                lockoutAfterFailure = PinLockoutState.Active(remaining = 30.seconds, total = 30.seconds),
            ),
            config(),
        )
        advanceUntilIdle()

        viewModel.setEvent(Event.OnQuickPinEntered(FakeSecurePin("123456")))
        // NOT advanceUntilIdle(): that would run the whole 30s countdown to completion and
        // stopLockoutTick() would clear the very state under test.
        advanceTimeBy(100)

        val state = viewModel.viewState.value
        assertTrue(state.isLockedOut)
        assertFalse(state.isLoading)
        assertNotNull(state.lockoutMessage)
        // The inline "wrong PIN" text is replaced by the lockout message, not shown alongside it.
        assertNull(state.quickPinError)
    }

    @Test
    fun a_lockout_found_on_entry_starts_the_countdown_immediately() = runTest(mainDispatcher) {
        val viewModel = BiometricViewModel(
            FakeBiometricInteractor(lockoutOnEntry = PinLockoutState.Active(remaining = 30.seconds, total = 30.seconds)),
            config(),
        )
        advanceTimeBy(100)   // see the note above: do not let the countdown finish

        assertTrue(viewModel.viewState.value.isLockedOut)
        assertNotNull(viewModel.viewState.value.lockoutMessage)
    }

    @Test
    fun the_countdown_message_reformats_every_second_and_clears_when_it_expires() =
        runTest(mainDispatcher) {
            val viewModel = BiometricViewModel(
                FakeBiometricInteractor(
                    lockoutOnEntry = PinLockoutState.Active(remaining = 3.seconds, total = 3.seconds),
                ),
                config(),
            )
            advanceTimeBy(100)
            val first = assertNotNull(viewModel.viewState.value.lockoutMessage)

            advanceTimeBy(1_100)
            val second = assertNotNull(viewModel.viewState.value.lockoutMessage)
            assertTrue(first != second, "the countdown must re-render each second")

            // Once it runs out the screen must unlock itself, with no user action.
            advanceTimeBy(3_000)
            advanceUntilIdle()
            assertFalse(viewModel.viewState.value.isLockedOut)
            assertNull(viewModel.viewState.value.lockoutMessage)
        }

    @Test
    fun a_pin_entered_while_locked_out_is_closed_and_ignored() = runTest(mainDispatcher) {
        val fake = FakeBiometricInteractor(
            lockoutOnEntry = PinLockoutState.Active(remaining = 30.seconds, total = 30.seconds),
        )
        val viewModel = BiometricViewModel(fake, config())
        advanceTimeBy(100)

        val pin = FakeSecurePin("123456")
        viewModel.setEvent(Event.OnQuickPinEntered(pin))
        advanceTimeBy(100)

        // A lockout must actually BLOCK entry, not merely display a message.
        assertTrue(pin.closed)
        assertEquals(0, fake.throttleResets)
        assertTrue(viewModel.viewState.value.isLockedOut)
    }

    @Test
    fun typing_while_locked_out_does_not_clear_the_lockout() = runTest(mainDispatcher) {
        val viewModel = BiometricViewModel(
            FakeBiometricInteractor(lockoutOnEntry = PinLockoutState.Active(remaining = 30.seconds, total = 30.seconds)),
            config(),
        )
        advanceTimeBy(100)

        viewModel.setEvent(Event.OnQuickPinLengthChanged(length = 3))
        advanceTimeBy(100)

        assertTrue(viewModel.viewState.value.isLockedOut)
        assertNotNull(viewModel.viewState.value.lockoutMessage)
    }

    @Test
    fun typing_clears_a_previous_pin_error() = runTest(mainDispatcher) {
        val viewModel = BiometricViewModel(
            FakeBiometricInteractor(
                pinValidResult = QuickPinInteractorPinValidPartialState.Failed("wrong"),
            ),
            config(),
        )
        advanceUntilIdle()
        viewModel.setEvent(Event.OnQuickPinEntered(FakeSecurePin("123456")))
        advanceUntilIdle()
        assertNotNull(viewModel.viewState.value.quickPinError)

        viewModel.setEvent(Event.OnQuickPinLengthChanged(length = 1))
        advanceUntilIdle()

        assertNull(viewModel.viewState.value.quickPinError)
    }

    @Test
    fun launching_the_system_screen_delegates_and_clears_any_error() = runTest(mainDispatcher) {
        val fake = FakeBiometricInteractor()
        val viewModel = BiometricViewModel(fake, config())
        advanceUntilIdle()

        viewModel.setEvent(Event.LaunchBiometricSystemScreen)
        advanceUntilIdle()

        assertEquals(1, fake.systemScreenLaunches)
        assertNull(viewModel.viewState.value.error)
    }

    @Test
    fun back_follows_the_configured_navigation() = runTest(mainDispatcher) {
        val viewModel = BiometricViewModel(FakeBiometricInteractor(), config())
        advanceUntilIdle()

        val effect = async { viewModel.effect.first() }
        viewModel.setEvent(Event.OnNavigateBack)
        advanceUntilIdle()

        val navigation = assertIs<Effect.Navigation.PopBackStackUpTo>(effect.await())
        assertEquals(DashboardRoute, navigation.route)
    }

    @Test
    fun back_does_nothing_when_the_config_forbids_it() = runTest(mainDispatcher) {
        val viewModel = BiometricViewModel(
            FakeBiometricInteractor(),
            config(backNavigation = null),
        )
        advanceUntilIdle()
        assertFalse(viewModel.viewState.value.isBackable)

        val effect = async { viewModel.effect.first() }
        viewModel.setEvent(Event.OnNavigateBack)
        advanceUntilIdle()

        // The login gate can be non-dismissible; nothing must be emitted.
        viewModel.setEvent(Event.LaunchBiometricSystemScreen)
        viewModel.setEvent(Event.OnQuickPinEntered(FakeSecurePin("123456")))
        advanceUntilIdle()
        assertIs<Effect.Navigation.SwitchScreen>(effect.await())
    }

    @Test
    fun dismissing_an_error_clears_it() = runTest(mainDispatcher) {
        val viewModel = BiometricViewModel(FakeBiometricInteractor(), config())
        advanceUntilIdle()

        viewModel.setEvent(Event.OnErrorDismiss)
        advanceUntilIdle()

        assertNull(viewModel.viewState.value.error)
    }
}
