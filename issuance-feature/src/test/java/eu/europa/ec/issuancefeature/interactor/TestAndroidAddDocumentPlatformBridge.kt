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

// Android's half of add-document issuance. These cases came from `TestAddDocumentInteractor` and moved
// with their subject: the interactor's own `handleUserAuth` and `resumeOpenId4VciWithAuthorization` are
// now shared code that delegates, and the behaviour they used to assert — which biometric branch runs,
// and that the redirect reaches wallet-core — belongs to this bridge.
package eu.europa.ec.issuancefeature.interactor

import android.content.Context
import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAvailability
import eu.europa.ec.authenticationlogic.controller.authentication.DeviceAuthenticationResult
import eu.europa.ec.authenticationlogic.model.BiometricCrypto
import eu.europa.ec.commonfeature.interactor.DeviceAuthenticationInteractor
import eu.europa.ec.corelogic.controller.FetchScopedDocumentsPartialState
import eu.europa.ec.corelogic.controller.IssuanceMethod
import eu.europa.ec.corelogic.controller.IssueDocumentsPartialState
import eu.europa.ec.corelogic.controller.WalletCoreDocumentsController
import eu.europa.ec.resourceslogic.provider.ResourceProvider
import eu.europa.ec.testfeature.util.mockedGenericErrorMessage
import eu.europa.ec.testfeature.util.mockedNotifyOnAuthenticationFailure
import eu.europa.ec.testfeature.util.mockedPidId
import eu.europa.ec.testfeature.util.mockedPlainFailureMessage
import eu.europa.ec.testfeature.util.mockedUriPath1
import eu.europa.ec.testlogic.extension.runFlowTest
import eu.europa.ec.testlogic.extension.runTest
import eu.europa.ec.testlogic.extension.toFlow
import eu.europa.ec.testlogic.rule.CoroutineTestRule
import junit.framework.TestCase.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.eq
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Locale

class TestAndroidAddDocumentPlatformBridge {

    @get:Rule
    val coroutineRule = CoroutineTestRule()

    @Mock
    private lateinit var walletCoreDocumentsController: WalletCoreDocumentsController

    @Mock
    private lateinit var deviceAuthenticationInteractor: DeviceAuthenticationInteractor

    @Mock
    private lateinit var resourceProvider: ResourceProvider

    @Mock
    private lateinit var context: Context

    @Mock
    private lateinit var resultHandler: DeviceAuthenticationResult

    private lateinit var bridge: AddDocumentPlatformBridge

    private lateinit var closeable: AutoCloseable

    private lateinit var crypto: BiometricCrypto

    @Before
    fun before() {
        closeable = MockitoAnnotations.openMocks(this)

        bridge = AndroidAddDocumentPlatformBridge(
            walletCoreDocumentsController = walletCoreDocumentsController,
            deviceAuthenticationInteractor = deviceAuthenticationInteractor,
            resourceProvider = resourceProvider,
        )

        crypto = BiometricCrypto(cryptoObject = null)
    }

    @After
    fun after() {
        closeable.close()
    }

    //region handleUserAuth

    // Case 1:
    // 1. deviceAuthenticationInteractor.getBiometricsAvailability returns:
    // BiometricsAvailability.CanAuthenticate

    // Case 1 Expected Result:
    // deviceAuthenticationInteractor.authenticateWithBiometrics called once.
    @Test
    fun `Given Case 1, When handleUserAuth is called, Then Case 1 expected result is returned`() {
        // Given
        whenever(deviceAuthenticationInteractor.getBiometricsAvailability())
            .thenReturn(BiometricsAvailability.CanAuthenticate)

        // When
        bridge.handleUserAuth(
            context = context,
            crypto = crypto,
            notifyOnAuthenticationFailure = mockedNotifyOnAuthenticationFailure,
            resultHandler = resultHandler
        )

        // Then
        verify(deviceAuthenticationInteractor, times(1))
            .authenticateWithBiometrics(
                context,
                crypto,
                mockedNotifyOnAuthenticationFailure,
                resultHandler
            )
    }

    // Case 2:
    // 1. deviceAuthenticationInteractor.getBiometricsAvailability returns:
    // BiometricsAvailability.NonEnrolled

    // Case 2 Expected Result:
    // deviceAuthenticationInteractor.launchBiometricSystemScreen called once.
    @Test
    fun `Given Case 2, When handleUserAuth is called, Then Case 2 expected result is returned`() {
        // Given
        whenever(deviceAuthenticationInteractor.getBiometricsAvailability())
            .thenReturn(BiometricsAvailability.NonEnrolled)

        // When
        bridge.handleUserAuth(
            context = context,
            crypto = crypto,
            notifyOnAuthenticationFailure = mockedNotifyOnAuthenticationFailure,
            resultHandler = resultHandler
        )

        // Then
        verify(deviceAuthenticationInteractor, times(1))
            .launchBiometricSystemScreen()
    }

    // Case 3:
    // 1. deviceAuthenticationInteractor.getBiometricsAvailability returns:
    // BiometricsAvailability.Failure

    // Case 3 Expected Result:
    // resultHandler.onAuthenticationFailure called once.
    @Test
    fun `Given Case 3, When handleUserAuth is called, Then Case 3 expected result is returned`() {
        // Given
        val onFailure = mock<() -> Unit>()
        val resultHandler = DeviceAuthenticationResult(
            onAuthenticationFailure = onFailure
        )

        whenever(deviceAuthenticationInteractor.getBiometricsAvailability())
            .thenReturn(BiometricsAvailability.Failure(errorMessage = mockedPlainFailureMessage))

        // When
        bridge.handleUserAuth(
            context = context,
            crypto = crypto,
            notifyOnAuthenticationFailure = mockedNotifyOnAuthenticationFailure,
            resultHandler = resultHandler
        )

        // Then
        verify(onFailure).invoke()
    }
    //endregion

    //region resumeOpenId4VciWithAuthorization

    @Test
    fun `When resumeOpenId4VciWithAuthorization is called, Then it is invoked on the controller`() {
        // When
        bridge.resumeOpenId4VciWithAuthorization(mockedUriPath1)

        // Then
        verify(walletCoreDocumentsController, times(1))
            .resumeOpenId4VciWithAuthorization(mockedUriPath1)
    }
    //endregion

    //region getScopedDocuments

    // The shared interactor speaks BCP-47 tags, wallet-core wants a java.util.Locale, and this is the
    // only place that conversion happens.
    @Test
    fun `Given a language tag, When getScopedDocuments is called, Then the controller is asked with that Locale`() {
        coroutineRule.runTest {
            // Given
            val expected = FetchScopedDocumentsPartialState.Failure(mockedGenericErrorMessage)
            whenever(walletCoreDocumentsController.getScopedDocuments(eq(Locale.forLanguageTag("de-DE"))))
                .thenReturn(expected)

            // When
            val result = bridge.getScopedDocuments(locale = "de-DE")

            // Then
            assertEquals(expected, result)
        }
    }

    @Test
    fun `When localeTag is called, Then it is the resource provider's locale as a language tag`() {
        // Given
        whenever(resourceProvider.getLocale()).thenReturn(Locale.forLanguageTag("fr-FR"))

        // Then
        assertEquals("fr-FR", bridge.localeTag())
    }
    //endregion

    //region issueDocuments

    @Test
    fun `Given issuance parameters, When issueDocuments is called, Then the controller is asked for them`() {
        coroutineRule.runTest {
            // Given
            val configIds = listOf("id")
            whenever(
                walletCoreDocumentsController.issueDocuments(
                    issuanceMethod = IssuanceMethod.OPENID4VCI,
                    configIds = configIds,
                    issuerId = "issuerId",
                )
            ).thenReturn(IssueDocumentsPartialState.Success(listOf(mockedPidId)).toFlow())

            // When
            bridge.issueDocuments(
                issuanceMethod = IssuanceMethod.OPENID4VCI,
                configIds = configIds,
                issuerId = "issuerId",
            ).runFlowTest {
                // Then
                assertEquals(IssueDocumentsPartialState.Success(listOf(mockedPidId)), awaitItem())
            }
        }
    }
    //endregion
}
