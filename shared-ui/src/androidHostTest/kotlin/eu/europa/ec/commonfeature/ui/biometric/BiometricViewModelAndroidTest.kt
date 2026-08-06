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

// The biometric-prompt half of BiometricViewModel — Android-only because `Event.OnBiometricsClicked`
// carries a `PlatformContext`. The PIN-fallback half, which is the security-critical part, is in
// commonTest and runs on both targets.
//
// `shouldThrowErrorIfNotAvailable` is the flag worth pinning here. The same event is sent from two
// places with opposite expectations: the automatic on-create prompt passes false and must fail
// silently, while an explicit tap on the fingerprint button passes true and must tell the user what
// went wrong. Collapsing the two would either nag on every launch or swallow a real error.
package eu.europa.ec.commonfeature.ui.biometric

import android.content.Context
import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAuthenticate
import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAvailability
import eu.europa.ec.commonfeature.ui.biometric.BiometricViewModelTest.Companion.config
import eu.europa.ec.dashboardfeature.ui.settings.FakeBiometricInteractor
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.platform.PlatformContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.mockito.kotlin.mock
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class BiometricViewModelAndroidTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private val context: PlatformContext = mock<Context>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun a_successful_prompt_resets_the_throttle_and_navigates_on() = runTest(mainDispatcher) {
        val fake = FakeBiometricInteractor(authResult = BiometricsAuthenticate.Success)
        val viewModel = BiometricViewModel(fake, config())
        advanceUntilIdle()

        val effect = async { viewModel.effect.first() }
        viewModel.setEvent(
            Event.OnBiometricsClicked(context, shouldThrowErrorIfNotAvailable = true)
        )
        advanceUntilIdle()

        val navigation = assertIs<Effect.Navigation.SwitchScreen>(effect.await())
        assertEquals(DashboardRoute, navigation.route)
        assertEquals(1, fake.authPrompts)
        // A successful login clears any accumulated PIN throttle.
        assertEquals(1, fake.throttleResets)
    }

    @Test
    fun a_failed_prompt_leaves_the_user_on_the_login_screen() = runTest(mainDispatcher) {
        val fake = FakeBiometricInteractor(authResult = BiometricsAuthenticate.Failed("no match"))
        val viewModel = BiometricViewModel(fake, config())
        advanceUntilIdle()

        viewModel.setEvent(
            Event.OnBiometricsClicked(context, shouldThrowErrorIfNotAvailable = true)
        )
        advanceUntilIdle()

        assertEquals(1, fake.authPrompts)
        // No navigation, and crucially no throttle reset — a failed prompt must not launder away
        // earlier PIN failures.
        assertEquals(0, fake.throttleResets)
    }

    @Test
    fun a_cancelled_prompt_is_inert() = runTest(mainDispatcher) {
        val fake = FakeBiometricInteractor(authResult = BiometricsAuthenticate.Cancelled)
        val viewModel = BiometricViewModel(fake, config())
        advanceUntilIdle()

        viewModel.setEvent(
            Event.OnBiometricsClicked(context, shouldThrowErrorIfNotAvailable = true)
        )
        advanceUntilIdle()

        assertEquals(0, fake.throttleResets)
        assertNull(viewModel.viewState.value.error)
    }

    @Test
    fun tapping_the_button_with_no_enrolment_offers_the_system_screen() = runTest(mainDispatcher) {
        val fake = FakeBiometricInteractor(availability = BiometricsAvailability.NonEnrolled)
        val viewModel = BiometricViewModel(fake, config())
        advanceUntilIdle()

        val effect = async { viewModel.effect.first() }
        viewModel.setEvent(
            Event.OnBiometricsClicked(context, shouldThrowErrorIfNotAvailable = true)
        )
        advanceUntilIdle()

        assertIs<Effect.Navigation.LaunchBiometricsSystemScreen>(effect.await())
        assertEquals(0, fake.authPrompts)
    }

    @Test
    fun the_automatic_prompt_stays_silent_when_there_is_no_enrolment() = runTest(mainDispatcher) {
        val fake = FakeBiometricInteractor(availability = BiometricsAvailability.NonEnrolled)
        val viewModel = BiometricViewModel(fake, config())
        advanceUntilIdle()

        val effect = async { viewModel.effect.first() }
        // shouldThrowErrorIfNotAvailable = false: this is the on-create attempt.
        viewModel.setEvent(
            Event.OnBiometricsClicked(context, shouldThrowErrorIfNotAvailable = false)
        )
        advanceUntilIdle()

        // Nothing emitted; the user just gets the PIN field. Proven by the NEXT event arriving.
        viewModel.setEvent(Event.OnNavigateBack)
        advanceUntilIdle()
        assertIs<Effect.Navigation.PopBackStackUpTo>(effect.await())
    }

    @Test
    fun tapping_the_button_surfaces_a_hardware_failure() = runTest(mainDispatcher) {
        val fake = FakeBiometricInteractor(
            availability = BiometricsAvailability.Failure("sensor unavailable")
        )
        val viewModel = BiometricViewModel(fake, config())
        advanceUntilIdle()

        viewModel.setEvent(
            Event.OnBiometricsClicked(context, shouldThrowErrorIfNotAvailable = true)
        )
        advanceUntilIdle()

        assertNotNull(viewModel.viewState.value.error)
    }

    @Test
    fun the_automatic_prompt_stays_silent_on_a_hardware_failure() = runTest(mainDispatcher) {
        val fake = FakeBiometricInteractor(
            availability = BiometricsAvailability.Failure("sensor unavailable")
        )
        val viewModel = BiometricViewModel(fake, config())
        advanceUntilIdle()

        viewModel.setEvent(
            Event.OnBiometricsClicked(context, shouldThrowErrorIfNotAvailable = false)
        )
        advanceUntilIdle()

        // An error card on every cold start would be intolerable on a device with no sensor.
        assertNull(viewModel.viewState.value.error)
    }
}
