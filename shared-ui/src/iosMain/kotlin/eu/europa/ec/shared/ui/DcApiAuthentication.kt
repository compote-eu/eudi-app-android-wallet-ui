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

package eu.europa.ec.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import eu.europa.ec.commonfeature.config.BiometricMode
import eu.europa.ec.commonfeature.config.BiometricUiConfig
import eu.europa.ec.commonfeature.config.OnBackNavigationConfig
import eu.europa.ec.commonfeature.ui.biometric.BiometricScreen
import eu.europa.ec.commonfeature.ui.biometric.BiometricViewModel
import eu.europa.ec.commonfeature.ui.biometric.Effect
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.shared.resources.biometric_default_mode_text_above_pin_field
import eu.europa.ec.shared.resources.loading_biometry_biometrics_enabled_description
import eu.europa.ec.shared.resources.loading_biometry_biometrics_not_enabled_description
import eu.europa.ec.uilogic.config.ConfigNavigation
import eu.europa.ec.uilogic.config.NavigationType
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The PIN or biometric step the document-provider extension asks for after consent, before a response
 * leaves the wallet.
 *
 * ## Why this exists
 *
 * Measured 2026-10-16 on an iPhone: a Digital Credentials request from Safari was answered with a PID after
 * the consent tap alone — no Face ID, no passcode, no wallet PIN. Nothing else stands in for it: the
 * documents' Keychain items and the device keys need no user presence, so the extension read and signed
 * without the user proving anything. The app asks for its PIN or biometrics after consent on both platforms
 * (`PresentationRequestViewModel` → `BiometricRoute`), Android's Digital Credentials path goes through that
 * same flow, and the official iOS wallet's extension asks for biometry with a quick-PIN fallback after
 * consent. This step makes the extension do the same.
 *
 * ## What is shared
 *
 * The screen, the view model and everything behind them are the app's: [BiometricScreen] over
 * [BiometricViewModel] over the iOS `BiometricInteractor`, so the PIN, its lockout and the Face ID item
 * are the same ones the app unlocks with. The extension is entitled to the app's Keychain group, and the
 * lockout is kept in the app group (`IosPinThrottle.shared`), so a wrong PIN here counts against the same
 * attempts as one in the app.
 */
internal object DcApiAuthentication {

    /** What one of the view model's navigations means here. */
    enum class Step {
        /** The PIN or biometrics checked out: the response may be released. */
        Authenticated,

        /** Back to the consent screen, where the request can still be shared or declined. */
        Back,

        /** A navigation [config] never names, and so nothing for this host to do. */
        Ignored,
    }

    /**
     * The app's presentation config (`PresentationRequestViewModel.getNextRoute`), with the two
     * navigations re-aimed: there is no next screen to push, so success is [NavigationType.Finish], and
     * back is [NavigationType.Pop], to the consent screen underneath.
     *
     * Finish and Pop are the only navigations [BiometricViewModel] takes from its config — success and
     * back respectively — so each maps to exactly one outcome in [stepFor].
     */
    fun config(): BiometricUiConfig = BiometricUiConfig(
        mode = BiometricMode.Default(
            descriptionWhenBiometricsEnabled =
                UiText.Resource(Res.string.loading_biometry_biometrics_enabled_description),
            descriptionWhenBiometricsNotEnabled =
                UiText.Resource(Res.string.loading_biometry_biometrics_not_enabled_description),
            textAbovePin = UiText.Resource(Res.string.biometric_default_mode_text_above_pin_field),
        ),
        isPreAuthorization = false,
        shouldInitializeBiometricAuthOnCreate = true,
        onSuccessNavigation = ConfigNavigation(navigationType = NavigationType.Finish),
        onBackNavigationConfig = OnBackNavigationConfig(
            onBackNavigation = ConfigNavigation(navigationType = NavigationType.Pop),
            hasToolbarBackIcon = true,
        ),
    )

    /**
     * [effect] read against [config]. Everything else is [Step.Ignored], including
     * `LaunchBiometricsSystemScreen`: the app answers it by opening Settings, which an extension cannot
     * open, and the PIN field stays on the screen either way.
     */
    fun stepFor(effect: Effect.Navigation): Step = when (effect) {
        is Effect.Navigation.Finish -> Step.Authenticated
        is Effect.Navigation.Pop -> Step.Back
        else -> Step.Ignored
    }
}

/**
 * The step itself. [onAuthenticated] is called once the PIN or biometrics checked out, [onBack] when the user
 * goes back to the consent screen.
 *
 * The view model gets a store of its own, cleared when the step leaves the composition — the extension's
 * Compose host is not a navigation entry, so nothing else would end it (and its lockout tick).
 */
@Composable
internal fun DcApiAuthenticationStep(
    onAuthenticated: () -> Unit,
    onBack: () -> Unit,
) {
    val owner = remember {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear() }
    }

    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        BiometricScreen(
            viewModel = koinViewModel { parametersOf(DcApiAuthentication.config()) },
            onNavigationRequested = { effect ->
                when (DcApiAuthentication.stepFor(effect)) {
                    DcApiAuthentication.Step.Authenticated -> onAuthenticated()
                    DcApiAuthentication.Step.Back -> onBack()
                    DcApiAuthentication.Step.Ignored -> Unit
                }
            },
        )
    }
}
