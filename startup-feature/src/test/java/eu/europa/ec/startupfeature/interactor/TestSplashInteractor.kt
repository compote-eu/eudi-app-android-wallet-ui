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

package eu.europa.ec.startupfeature.interactor

import eu.europa.ec.businesslogic.config.ConfigLogic
import eu.europa.ec.businesslogic.controller.storage.TrustMarkIntroductionStore
import eu.europa.ec.commonfeature.config.BiometricMode
import eu.europa.ec.commonfeature.config.BiometricUiConfig
import eu.europa.ec.commonfeature.config.IssuanceFlowType
import eu.europa.ec.commonfeature.config.IssuanceUiConfig
import eu.europa.ec.commonfeature.config.OnBackNavigationConfig
import eu.europa.ec.commonfeature.config.TrustMarkMode
import eu.europa.ec.commonfeature.config.TrustMarkUiConfig
import eu.europa.ec.commonfeature.interactor.QuickPinInteractor
import eu.europa.ec.commonfeature.model.PinFlow
import eu.europa.ec.shared.navigation.AddDocumentRoute
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.navigation.BiometricRoute
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.navigation.QuickPinRoute
import eu.europa.ec.shared.navigation.TrustMarkRoute
import eu.europa.ec.shared.resources.StringCatalog
import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.shared.wallet.WalletDocument
import eu.europa.ec.shared.wallet.WalletEngine
import eu.europa.ec.testlogic.extension.runTest
import eu.europa.ec.testlogic.rule.CoroutineTestRule
import eu.europa.ec.uilogic.config.ConfigNavigation
import eu.europa.ec.uilogic.config.NavigationType
import junit.framework.TestCase.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.biometric_login_biometrics_enabled_subtitle
import eu.europa.ec.shared.resources.biometric_login_biometrics_not_enabled_subtitle
import eu.europa.ec.shared.resources.biometric_login_title
import eu.europa.ec.shared.resources.generic_error_message

class TestSplashInteractor {

    @get:Rule
    val coroutineRule = CoroutineTestRule()

    @Mock
    private lateinit var quickPinInteractor: QuickPinInteractor


    @Mock
    private lateinit var walletEngine: WalletEngine

    @Mock
    private lateinit var configLogic: ConfigLogic

    @Mock
    private lateinit var introductionStore: TrustMarkIntroductionStore

    @Mock
    private lateinit var strings: StringCatalog

    private lateinit var interactor: SplashInteractor

    private lateinit var closeable: AutoCloseable

    @Before
    fun before() {
        closeable = MockitoAnnotations.openMocks(this)
        whenever(strings.get(Res.string.generic_error_message)).thenReturn(mockedGenericErrorMessage)

        // The interactor is shared, and so is the configuration it reads: `forcePidActivation` is a
        // member of `SharedAppConfig`, which `ConfigLogic` extends. These cases still drive it through
        // the same mock they always did — it is now passed as the config rather than unwrapped into a
        // lambda by the host.
        interactor = SplashInteractorImpl(
            quickPinInteractor = quickPinInteractor,
            walletEngine = walletEngine,
            appConfig = configLogic,
            introductionStore = introductionStore,
            strings = strings,
        )
    }

    @After
    fun after() {
        closeable.close()
    }

    //region getAfterSplashRoute

    // Case 1:
    // 1. quickPinInteractor.hasPin() returns false (no PIN set yet).
    // 2. configLogic.forcePidActivation is true.
    // 3. walletCoreDocumentsController.getAllDocuments() returns an empty list,
    //    so shouldActivateWithPid evaluates to true.

    // Case 1 Expected Result:
    // The QUICK_PIN route, with pinFlow = CREATE_WITH_ACTIVATION.
    @Test
    fun `Given Case 1, When getAfterSplashRoute is called, Then Case 1 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            mockIntroductionCompletedCall(completed = mockedIntroductionCompleted)
            whenever(quickPinInteractor.hasPin()).thenReturn(false)
            whenever(configLogic.forcePidActivation).thenReturn(true)
            whenever(walletEngine.getAllDocuments()).thenReturn(emptyList())

            // When
            val result = interactor.getAfterSplashRoute()

            // Then
            val expectedResult = QuickPinRoute(PinFlow.CREATE_WITH_ACTIVATION)
            assertEquals(SplashRoutePartialState.Success(expectedResult), result)
        }
    }

    // Case 2:
    // 1. quickPinInteractor.hasPin() returns false (no PIN set yet).
    // 2. configLogic.forcePidActivation is true.
    // 3. walletCoreDocumentsController.getAllDocuments() returns a non-empty list,
    //    so shouldActivateWithPid evaluates to false.

    // Case 2 Expected Result:
    // The QUICK_PIN route, with pinFlow = CREATE_WITHOUT_ACTIVATION.
    @Test
    fun `Given Case 2, When getAfterSplashRoute is called, Then Case 2 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            mockIntroductionCompletedCall(completed = mockedIntroductionCompleted)
            whenever(quickPinInteractor.hasPin()).thenReturn(false)
            whenever(configLogic.forcePidActivation).thenReturn(true)
            whenever(walletEngine.getAllDocuments())
                .thenReturn(listOf(WalletDocument(id = "mocked_id")))

            // When
            val result = interactor.getAfterSplashRoute()

            // Then
            val expectedResult = QuickPinRoute(PinFlow.CREATE_WITHOUT_ACTIVATION)
            assertEquals(SplashRoutePartialState.Success(expectedResult), result)
        }
    }

    // Case 3:
    // 1. quickPinInteractor.hasPin() returns false (no PIN set yet).
    // 2. configLogic.forcePidActivation is false,
    //    so shouldActivateWithPid evaluates to false regardless of stored documents.

    // Case 3 Expected Result:
    // The QUICK_PIN route, with pinFlow = CREATE_WITHOUT_ACTIVATION.
    @Test
    fun `Given Case 3, When getAfterSplashRoute is called, Then Case 3 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            mockIntroductionCompletedCall(completed = mockedIntroductionCompleted)
            whenever(quickPinInteractor.hasPin()).thenReturn(false)
            whenever(configLogic.forcePidActivation).thenReturn(false)
            whenever(walletEngine.getAllDocuments()).thenReturn(emptyList())

            // When
            val result = interactor.getAfterSplashRoute()

            // Then
            val expectedResult = QuickPinRoute(PinFlow.CREATE_WITHOUT_ACTIVATION)
            assertEquals(SplashRoutePartialState.Success(expectedResult), result)
        }
    }

    // Case 4:
    // 1. quickPinInteractor.hasPin() returns true (PIN already set, biometric login flow).
    // 2. configLogic.forcePidActivation is false,
    //    so shouldActivateWithPid evaluates to false and onSuccessNavigation pushes DashboardRoute.

    // Case 4 Expected Result:
    // BiometricRoute, carrying the BiometricUiConfig.
    @Test
    fun `Given Case 4, When getAfterSplashRoute is called, Then Case 4 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            mockIntroductionCompletedCall(completed = mockedIntroductionCompleted)
            whenever(quickPinInteractor.hasPin()).thenReturn(true)
            whenever(configLogic.forcePidActivation).thenReturn(false)
            whenever(walletEngine.getAllDocuments()).thenReturn(emptyList())
            // When
            val result = interactor.getAfterSplashRoute()

            // Then
            val expectedResult = BiometricRoute(
                buildBiometricUiConfig(shouldActivateWithPid = false)
            )
            assertEquals(SplashRoutePartialState.Success(expectedResult), result)
        }
    }

    // Case 5:
    // 1. quickPinInteractor.hasPin() returns true (PIN already set, biometric login flow).
    // 2. configLogic.forcePidActivation is true.
    // 3. walletCoreDocumentsController.getAllDocuments() returns a non-empty list,
    //    so shouldActivateWithPid evaluates to false and onSuccessNavigation pushes DashboardRoute.

    // Case 5 Expected Result:
    // BiometricRoute, carrying the BiometricUiConfig.
    @Test
    fun `Given Case 5, When getAfterSplashRoute is called, Then Case 5 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            mockIntroductionCompletedCall(completed = mockedIntroductionCompleted)
            whenever(quickPinInteractor.hasPin()).thenReturn(true)
            whenever(configLogic.forcePidActivation).thenReturn(true)
            whenever(walletEngine.getAllDocuments())
                .thenReturn(listOf(WalletDocument(id = "mocked_id")))
            // When
            val result = interactor.getAfterSplashRoute()

            // Then
            val expectedResult = BiometricRoute(
                buildBiometricUiConfig(shouldActivateWithPid = false)
            )
            assertEquals(SplashRoutePartialState.Success(expectedResult), result)
        }
    }

    // Case 6:
    // 1. quickPinInteractor.hasPin() returns true (PIN already set, biometric login flow).
    // 2. configLogic.forcePidActivation is true.
    // 3. walletCoreDocumentsController.getAllDocuments() returns an empty list,
    //    so shouldActivateWithPid evaluates to true and onSuccessNavigation pushes
    //    AddDocumentRoute carrying IssuanceUiConfig(NoDocument).

    // Case 6 Expected Result:
    // BiometricRoute, carrying the BiometricUiConfig
    // (whose nested onSuccessNavigation carries the AddDocumentRoute config).
    @Test
    fun `Given Case 6, When getAfterSplashRoute is called, Then Case 6 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            mockIntroductionCompletedCall(completed = mockedIntroductionCompleted)
            whenever(quickPinInteractor.hasPin()).thenReturn(true)
            whenever(configLogic.forcePidActivation).thenReturn(true)
            whenever(walletEngine.getAllDocuments()).thenReturn(emptyList())
            // When
            val result = interactor.getAfterSplashRoute()

            // Then
            val expectedResult = BiometricRoute(
                buildBiometricUiConfig(shouldActivateWithPid = true)
            )
            assertEquals(SplashRoutePartialState.Success(expectedResult), result)
        }
    }

    // Case 7 of upstream (a biometric configuration that does not serialize) has no counterpart: the
    // route is typed, not a base64 argument. Its number is kept free so 8–10 stay aligned with upstream.

    // Case 8:
    // 1. The introduction is incomplete and no PIN exists.
    // 2. PID activation is tested both enabled and disabled.
    // Case 8 Expected Result:
    // Welcome carries the existing PIN route without recording completion.
    @Test
    fun `Given Case 8, When getAfterSplashRoute is called, Then Case 8 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            mockIntroductionCompletedCall(false)
            whenever(quickPinInteractor.hasPin()).thenReturn(false)
            whenever(walletEngine.getAllDocuments()).thenReturn(emptyList())
            listOf(true, false).forEach { forcePidActivation ->
                whenever(configLogic.forcePidActivation).thenReturn(forcePidActivation)
                val pinFlow = if (forcePidActivation) {
                    PinFlow.CREATE_WITH_ACTIVATION
                } else PinFlow.CREATE_WITHOUT_ACTIVATION

                // When
                val result = interactor.getAfterSplashRoute()

                // Then
                assertEquals(
                    SplashRoutePartialState.Success(welcomeRoute(continuationRoute = QuickPinRoute(pinFlow))),
                    result,
                )
            }
            verify(introductionStore, never()).setTrustMarkIntroductionCompleted(any())
        }
    }

    // Case 9:
    // 1. A PIN exists but the introduction remains incomplete.
    // Case 9 Expected Result:
    // Welcome preserves the biometric continuation, without inferring completion from the PIN.
    @Test
    fun `Given Case 9, When getAfterSplashRoute is called, Then Case 9 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            mockIntroductionCompletedCall(false)
            whenever(quickPinInteractor.hasPin()).thenReturn(true)
            whenever(configLogic.forcePidActivation).thenReturn(false)

            // When
            val result = interactor.getAfterSplashRoute()

            // Then
            assertEquals(
                SplashRoutePartialState.Success(
                    welcomeRoute(
                        continuationRoute = BiometricRoute(buildBiometricUiConfig(shouldActivateWithPid = false))
                    )
                ),
                result,
            )
        }
    }

    // Case 10:
    // 1. Reading introduction completion fails.
    // Case 10 Expected Result:
    // A recoverable failure is returned instead of bypassing Welcome.
    @Test
    fun `Given Case 10, When getAfterSplashRoute is called, Then Case 10 Expected Result is returned`() {
        coroutineRule.runTest {
            // Given
            whenever(introductionStore.getTrustMarkIntroductionCompleted()).thenThrow(mockedExceptionWithMessage)
            whenever(quickPinInteractor.hasPin()).thenReturn(false)
            whenever(configLogic.forcePidActivation).thenReturn(false)

            // When
            val result = interactor.getAfterSplashRoute()

            // Then
            assertEquals(SplashRoutePartialState.Failure(mockedGenericErrorMessage), result)
        }
    }

    //endregion

    //region helper functions
    private suspend fun mockIntroductionCompletedCall(completed: Boolean) {
        whenever(introductionStore.getTrustMarkIntroductionCompleted()).thenReturn(completed)
    }

    private fun welcomeRoute(continuationRoute: AppRoute) = TrustMarkRoute(
        TrustMarkUiConfig(mode = TrustMarkMode.Welcome(continuationRoute = continuationRoute))
    )

    private fun buildBiometricUiConfig(shouldActivateWithPid: Boolean): BiometricUiConfig {
        return BiometricUiConfig(
            mode = BiometricMode.Login(
                title = UiText.Resource(Res.string.biometric_login_title),
                subTitleWhenBiometricsEnabled = UiText.Resource(Res.string.biometric_login_biometrics_enabled_subtitle),
                subTitleWhenBiometricsNotEnabled = UiText.Resource(Res.string.biometric_login_biometrics_not_enabled_subtitle)
            ),
            isPreAuthorization = true,
            shouldInitializeBiometricAuthOnCreate = true,
            onSuccessNavigation = ConfigNavigation(
                navigationType = NavigationType.PushRoute(
                    route = if (shouldActivateWithPid) {
                        AddDocumentRoute(IssuanceUiConfig(flowType = IssuanceFlowType.NoDocument))
                    } else {
                        DashboardRoute
                    }
                )
            ),
            onBackNavigationConfig = OnBackNavigationConfig(
                onBackNavigation = ConfigNavigation(navigationType = NavigationType.Finish),
                hasToolbarBackIcon = false
            )
        )
    }

    private val mockedIntroductionCompleted = true
    private val mockedGenericErrorMessage = "Something went wrong"
    private val mockedExceptionWithMessage = RuntimeException("Exception to test interactor.")
    //endregion

}
