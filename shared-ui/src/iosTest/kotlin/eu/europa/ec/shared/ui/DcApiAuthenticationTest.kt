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

// The step the document-provider extension asks for after consent. Measured 2026-10-16 on an iPhone:
// without it, a Digital Credentials request was answered with a PID after the consent tap alone. These
// cases drive the app's own BiometricViewModel with the extension's config, so they pin what decides
// whether a response leaves the wallet: only a PIN or biometrics that checked out reach Authenticated.
package eu.europa.ec.shared.ui

import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAuthenticate
import eu.europa.ec.commonfeature.interactor.QuickPinInteractorPinValidPartialState
import eu.europa.ec.commonfeature.ui.biometric.BiometricViewModel
import eu.europa.ec.commonfeature.ui.biometric.Effect
import eu.europa.ec.commonfeature.ui.biometric.Event
import eu.europa.ec.commonfeature.ui.biometric.FakeSecurePin
import eu.europa.ec.dashboardfeature.ui.settings.FakeBiometricInteractor
import eu.europa.ec.shared.navigation.DashboardRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class DcApiAuthenticationTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** Every step the view model asks the extension for while [action] runs, in order. */
    private fun stepsDuring(
        interactor: FakeBiometricInteractor,
        action: (BiometricViewModel) -> Unit,
    ): List<DcApiAuthentication.Step> {
        val steps = mutableListOf<DcApiAuthentication.Step>()
        runTest(mainDispatcher) {
            val viewModel = BiometricViewModel(interactor, DcApiAuthentication.config())
            val collector = launch {
                viewModel.effect.filterIsInstance<Effect.Navigation>().collect { effect ->
                    steps += DcApiAuthentication.stepFor(effect)
                }
            }
            advanceUntilIdle()
            action(viewModel)
            advanceUntilIdle()
            collector.cancel()
        }
        return steps
    }

    @Test
    fun a_correct_pin_authenticates() {
        val steps = stepsDuring(FakeBiometricInteractor(biometricUserSelection = false)) {
            it.setEvent(Event.OnQuickPinEntered(FakeSecurePin("123456")))
        }

        assertEquals(listOf(DcApiAuthentication.Step.Authenticated), steps)
    }

    @Test
    fun a_wrong_pin_releases_nothing() {
        val steps = stepsDuring(
            FakeBiometricInteractor(
                biometricUserSelection = false,
                pinValidResult = QuickPinInteractorPinValidPartialState.Failed("wrong PIN"),
            ),
        ) {
            it.setEvent(Event.OnQuickPinEntered(FakeSecurePin("000000")))
        }

        assertEquals(emptyList(), steps, "a wrong PIN must not reach Authenticated, or anything else")
    }

    @Test
    fun biometrics_that_check_out_authenticate() {
        val steps = stepsDuring(FakeBiometricInteractor(authResult = BiometricsAuthenticate.Success)) {
            it.setEvent(Event.OnBiometricsClicked(context = null, shouldThrowErrorIfNotAvailable = true))
        }

        assertEquals(listOf(DcApiAuthentication.Step.Authenticated), steps)
    }

    @Test
    fun a_cancelled_or_failed_prompt_releases_nothing() {
        listOf(BiometricsAuthenticate.Cancelled, BiometricsAuthenticate.Failed("no match")).forEach { result ->
            val steps = stepsDuring(FakeBiometricInteractor(authResult = result)) {
                it.setEvent(Event.OnBiometricsClicked(context = null, shouldThrowErrorIfNotAvailable = true))
            }

            assertEquals(emptyList(), steps, "$result must not reach Authenticated")
        }
    }

    @Test
    fun back_returns_to_the_consent_screen() {
        val steps = stepsDuring(FakeBiometricInteractor(biometricUserSelection = false)) {
            it.setEvent(Event.OnNavigateBack)
        }

        assertEquals(listOf(DcApiAuthentication.Step.Back), steps)
    }

    @Test
    fun only_success_and_back_mean_anything_here() {
        assertEquals(DcApiAuthentication.Step.Authenticated, DcApiAuthentication.stepFor(Effect.Navigation.Finish))
        assertEquals(DcApiAuthentication.Step.Back, DcApiAuthentication.stepFor(Effect.Navigation.Pop))
        listOf(
            Effect.Navigation.LaunchBiometricsSystemScreen,
            Effect.Navigation.SwitchScreen(route = DashboardRoute),
            Effect.Navigation.PopBackStackUpTo(route = DashboardRoute, inclusive = false),
        ).forEach { effect ->
            assertEquals(DcApiAuthentication.Step.Ignored, DcApiAuthentication.stepFor(effect), "$effect")
        }
    }
}
