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

// Phase 3b: the Android implementation of the (now KMP) `SplashInteractor` interface, which moved to
// :shared-ui/commonMain with `SplashViewModel`. This side stays Android-only: it resolves strings
// through ResourceProvider and reads ConfigLogic/QuickPinInteractor.
package eu.europa.ec.startupfeature.interactor

import eu.europa.ec.commonfeature.config.BiometricMode
import eu.europa.ec.commonfeature.config.BiometricUiConfig
import eu.europa.ec.commonfeature.config.IssuanceFlowType
import eu.europa.ec.commonfeature.config.IssuanceUiConfig
import eu.europa.ec.commonfeature.config.OnBackNavigationConfig
import eu.europa.ec.commonfeature.interactor.QuickPinInteractor
import eu.europa.ec.commonfeature.model.PinFlow
import eu.europa.ec.shared.navigation.AddDocumentRoute
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.navigation.BiometricRoute
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.navigation.QuickPinRoute
import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.shared.wallet.WalletEngine
import eu.europa.ec.uilogic.config.ConfigNavigation
import eu.europa.ec.uilogic.config.NavigationType
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.biometric_login_biometrics_enabled_subtitle
import eu.europa.ec.shared.resources.biometric_login_biometrics_not_enabled_subtitle
import eu.europa.ec.shared.resources.biometric_login_title
import eu.europa.ec.shared.wallet.config.SharedAppConfig

/**
 * Where the app goes after the splash: to create a PIN, or to unlock with the one it has.
 *
 * Shared because the decision is the wallet's, not a platform's — it reads whether a PIN exists and
 * whether the wallet holds documents, both of which each platform answers through its own storage.
 */
class SplashInteractorImpl(
    private val quickPinInteractor: QuickPinInteractor,
    private val walletEngine: WalletEngine,
    /**
     * The app's configuration, for [SharedAppConfig.forcePidActivation] — whether this build refuses to
     * go anywhere before a PID is issued.
     *
     * Was a `() -> Boolean` until 2026-08-28, on the reasoning that "Android reads it from `ConfigLogic`,
     * which is Android-only; iOS has no configuration layer yet". Both halves stopped being true: the flag
     * moved onto [SharedAppConfig] in commonMain, and iOS has `IosWalletConfig`. A lambda here meant every
     * host had to be handed this one flag, which is how the same setting came to be declared through four
     * different seams.
     */
    private val appConfig: SharedAppConfig,
) : SplashInteractor {

    private suspend fun hasDocuments(): Boolean =
        walletEngine.getAllDocuments().isNotEmpty()

    private suspend fun shouldActivateWithPid(): Boolean =
        appConfig.forcePidActivation && !hasDocuments()

    override suspend fun getAfterSplashRoute(): AppRoute = when (quickPinInteractor.hasPin()) {
        true -> {
            getBiometricsConfig()
        }

        false -> {
            getQuickPinConfig()
        }
    }

    private suspend fun getQuickPinConfig(): AppRoute {
        return QuickPinRoute(
            if (shouldActivateWithPid()) {
                PinFlow.CREATE_WITH_ACTIVATION
            } else {
                PinFlow.CREATE_WITHOUT_ACTIVATION
            }
        )
    }

    private suspend fun getBiometricsConfig(): AppRoute {

        val shouldActivateWithPid = shouldActivateWithPid()

        return BiometricRoute(
            BiometricUiConfig(
                mode = BiometricMode.Login(
                    title = UiText.Resource(Res.string.biometric_login_title),
                    subTitleWhenBiometricsEnabled = UiText.Resource(Res.string.biometric_login_biometrics_enabled_subtitle),
                    subTitleWhenBiometricsNotEnabled = UiText.Resource(Res.string.biometric_login_biometrics_not_enabled_subtitle),
                ),
                isPreAuthorization = true,
                shouldInitializeBiometricAuthOnCreate = true,
                onSuccessNavigation = ConfigNavigation(
                    navigationType = NavigationType.PushRoute(
                        route = if (shouldActivateWithPid) {
                            AddDocumentRoute(
                                IssuanceUiConfig(
                                    flowType = IssuanceFlowType.NoDocument
                                )
                            )
                        } else {
                            DashboardRoute
                        }
                    )
                ),
                onBackNavigationConfig = OnBackNavigationConfig(
                    onBackNavigation = ConfigNavigation(
                        navigationType = NavigationType.Finish
                    ),
                    hasToolbarBackIcon = false
                )
            )
        )
    }
}