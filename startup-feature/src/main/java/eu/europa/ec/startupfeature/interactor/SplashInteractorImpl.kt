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

import eu.europa.ec.businesslogic.config.ConfigLogic
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
import eu.europa.ec.resourceslogic.provider.ResourceProvider
import eu.europa.ec.uilogic.config.ConfigNavigation
import eu.europa.ec.uilogic.config.NavigationType
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.biometric_login_biometrics_enabled_subtitle
import eu.europa.ec.shared.resources.biometric_login_biometrics_not_enabled_subtitle
import eu.europa.ec.shared.resources.biometric_login_title

class SplashInteractorImpl(
    private val quickPinInteractor: QuickPinInteractor,
    private val resourceProvider: ResourceProvider,
    private val walletEngine: WalletEngine,
    private val configLogic: ConfigLogic
) : SplashInteractor {

    private val hasDocuments: Boolean
        get() = walletEngine.getAllDocuments().isNotEmpty()

    private val shouldActivateWithPid: Boolean
        get() = configLogic.forcePidActivation && !hasDocuments

    override suspend fun getAfterSplashRoute(): AppRoute = when (quickPinInteractor.hasPin()) {
        true -> {
            getBiometricsConfig()
        }

        false -> {
            getQuickPinConfig()
        }
    }

    private fun getQuickPinConfig(): AppRoute {
        return QuickPinRoute(
            if (shouldActivateWithPid) {
                PinFlow.CREATE_WITH_ACTIVATION
            } else {
                PinFlow.CREATE_WITHOUT_ACTIVATION
            }
        )
    }

    private fun getBiometricsConfig(): AppRoute {

        val shouldActivateWithPid = configLogic.forcePidActivation && !hasDocuments

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