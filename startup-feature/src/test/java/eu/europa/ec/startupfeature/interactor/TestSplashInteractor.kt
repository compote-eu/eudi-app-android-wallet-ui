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
import eu.europa.ec.commonfeature.config.BiometricMode
import eu.europa.ec.commonfeature.config.BiometricUiConfig
import eu.europa.ec.commonfeature.config.IssuanceFlowType
import eu.europa.ec.commonfeature.config.IssuanceUiConfig
import eu.europa.ec.commonfeature.config.OnBackNavigationConfig
import eu.europa.ec.commonfeature.interactor.QuickPinInteractor
import eu.europa.ec.commonfeature.model.PinFlow
import eu.europa.ec.shared.navigation.AddDocumentRoute
import eu.europa.ec.shared.navigation.BiometricRoute
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.navigation.QuickPinRoute
import eu.europa.ec.shared.wallet.WalletDocument
import eu.europa.ec.shared.wallet.WalletEngine
import eu.europa.ec.resourceslogic.provider.ResourceProvider
import eu.europa.ec.testfeature.util.getMockedFullDocuments
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
import org.mockito.kotlin.whenever
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.biometric_login_biometrics_enabled_subtitle
import eu.europa.ec.shared.resources.biometric_login_biometrics_not_enabled_subtitle
import eu.europa.ec.shared.resources.biometric_login_title

class TestSplashInteractor {

    @get:Rule
    val coroutineRule = CoroutineTestRule()

    @Mock
    private lateinit var quickPinInteractor: QuickPinInteractor

    @Mock
    private lateinit var resourceProvider: ResourceProvider

    @Mock
    private lateinit var walletEngine: WalletEngine

    @Mock
    private lateinit var configLogic: ConfigLogic

    private lateinit var interactor: SplashInteractor

    private lateinit var closeable: AutoCloseable

    @Before
    fun before() {
        closeable = MockitoAnnotations.openMocks(this)

        interactor = SplashInteractorImpl(
            quickPinInteractor = quickPinInteractor,
            resourceProvider = resourceProvider,
            walletEngine = walletEngine,
            configLogic = configLogic
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
            whenever(quickPinInteractor.hasPin()).thenReturn(false)
            whenever(configLogic.forcePidActivation).thenReturn(true)
            whenever(walletEngine.getAllDocuments()).thenReturn(emptyList())

            // When
            val result = interactor.getAfterSplashRoute()

            // Then
            val expectedResult = QuickPinRoute(PinFlow.CREATE_WITH_ACTIVATION)
            assertEquals(expectedResult, result)
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
            whenever(quickPinInteractor.hasPin()).thenReturn(false)
            whenever(configLogic.forcePidActivation).thenReturn(true)
            whenever(walletEngine.getAllDocuments())
                .thenReturn(listOf(WalletDocument(id = "mocked_id")))

            // When
            val result = interactor.getAfterSplashRoute()

            // Then
            val expectedResult = QuickPinRoute(PinFlow.CREATE_WITHOUT_ACTIVATION)
            assertEquals(expectedResult, result)
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
            whenever(quickPinInteractor.hasPin()).thenReturn(false)
            whenever(configLogic.forcePidActivation).thenReturn(false)
            whenever(walletEngine.getAllDocuments()).thenReturn(emptyList())

            // When
            val result = interactor.getAfterSplashRoute()

            // Then
            val expectedResult = QuickPinRoute(PinFlow.CREATE_WITHOUT_ACTIVATION)
            assertEquals(expectedResult, result)
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
            whenever(quickPinInteractor.hasPin()).thenReturn(true)
            whenever(configLogic.forcePidActivation).thenReturn(false)
            whenever(walletEngine.getAllDocuments()).thenReturn(emptyList())
            mockBiometricLoginStrings()

            // When
            val result = interactor.getAfterSplashRoute()

            // Then
            val expectedResult = BiometricRoute(
                buildBiometricUiConfig(shouldActivateWithPid = false)
            )
            assertEquals(expectedResult, result)
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
            whenever(quickPinInteractor.hasPin()).thenReturn(true)
            whenever(configLogic.forcePidActivation).thenReturn(true)
            whenever(walletEngine.getAllDocuments())
                .thenReturn(listOf(WalletDocument(id = "mocked_id")))
            mockBiometricLoginStrings()

            // When
            val result = interactor.getAfterSplashRoute()

            // Then
            val expectedResult = BiometricRoute(
                buildBiometricUiConfig(shouldActivateWithPid = false)
            )
            assertEquals(expectedResult, result)
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
            whenever(quickPinInteractor.hasPin()).thenReturn(true)
            whenever(configLogic.forcePidActivation).thenReturn(true)
            whenever(walletEngine.getAllDocuments()).thenReturn(emptyList())
            mockBiometricLoginStrings()

            // When
            val result = interactor.getAfterSplashRoute()

            // Then
            val expectedResult = BiometricRoute(
                buildBiometricUiConfig(shouldActivateWithPid = true)
            )
            assertEquals(expectedResult, result)
        }
    }

    //endregion

    //region helper functions
    private fun mockBiometricLoginStrings() {
        whenever(resourceProvider.getString(Res.string.biometric_login_title))
            .thenReturn(mockedBiometricLoginTitle)
        whenever(resourceProvider.getString(Res.string.biometric_login_biometrics_enabled_subtitle))
            .thenReturn(mockedBiometricLoginSubtitleEnabled)
        whenever(resourceProvider.getString(Res.string.biometric_login_biometrics_not_enabled_subtitle))
            .thenReturn(mockedBiometricLoginSubtitleNotEnabled)
    }

    private fun buildBiometricUiConfig(shouldActivateWithPid: Boolean): BiometricUiConfig {
        return BiometricUiConfig(
            mode = BiometricMode.Login(
                title = mockedBiometricLoginTitle,
                subTitleWhenBiometricsEnabled = mockedBiometricLoginSubtitleEnabled,
                subTitleWhenBiometricsNotEnabled = mockedBiometricLoginSubtitleNotEnabled
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
    //endregion

    //region mocked objects
    private val mockedBiometricLoginTitle = "Biometric login title"
    private val mockedBiometricLoginSubtitleEnabled = "Biometric subtitle when biometrics enabled"
    private val mockedBiometricLoginSubtitleNotEnabled =
        "Biometric subtitle when biometrics not enabled"
    //endregion
}
