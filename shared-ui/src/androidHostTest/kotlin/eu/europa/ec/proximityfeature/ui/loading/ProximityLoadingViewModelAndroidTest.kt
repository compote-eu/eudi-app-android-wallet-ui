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

// ProximityLoadingViewModel's `doWork` — the ISO 18013-5 send path, including the device-authentication
// hand-off that was the reason the PlatformContext handle exists.
//
// Android-only NOT because the logic is Android-specific — it is ordinary common code — but because
// `Event.DoWork` carries a `PlatformContext`, an `expect class` with no common constructor that is
// deliberately uninhabited on iOS. A mock stands in for it; the view-model never calls anything on it,
// it only forwards it to the interactor, so a mock is a faithful stand-in rather than a compromise.
// The base's own behaviour is covered in commonTest by LoadingViewModelTest and runs on both targets.
package eu.europa.ec.proximityfeature.ui.loading

import android.content.Context
import eu.europa.ec.authenticationlogic.controller.authentication.DeviceAuthenticationResult
import eu.europa.ec.authenticationlogic.model.BiometricCrypto
import eu.europa.ec.commonfeature.ui.loading.Effect
import eu.europa.ec.commonfeature.ui.loading.Event
import eu.europa.ec.corelogic.model.AuthenticationData
import eu.europa.ec.proximityfeature.interactor.ProximityLoadingInteractor
import eu.europa.ec.proximityfeature.interactor.ProximityLoadingObserveResponsePartialState
import eu.europa.ec.proximityfeature.interactor.ProximityLoadingSendRequestedDocumentPartialState
import eu.europa.ec.shared.navigation.ProximityRequestRoute
import eu.europa.ec.shared.navigation.ProximitySuccessRoute
import eu.europa.ec.shared.platform.PlatformContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProximityLoadingViewModelAndroidTest {

    private class FakeProximityLoadingInteractor(
        private val responses: List<ProximityLoadingObserveResponsePartialState>,
        private val sendResult: ProximityLoadingSendRequestedDocumentPartialState =
            ProximityLoadingSendRequestedDocumentPartialState.Success,
        /** When true, the device-auth prompt reports success and the flow continues. */
        private val authSucceeds: Boolean = true,
    ) : ProximityLoadingInteractor {
        override var presentationScopeId: String = ""
            private set

        var sendCalls: Int = 0
            private set
        var authPrompts: Int = 0
            private set

        override fun setScopeId(scopeId: String) {
            presentationScopeId = scopeId
        }

        override fun observeResponse(): Flow<ProximityLoadingObserveResponsePartialState> =
            flow { responses.forEach { emit(it) } }

        override suspend fun sendRequestedDocuments():
            ProximityLoadingSendRequestedDocumentPartialState {
            sendCalls++
            return sendResult
        }

        override fun handleUserAuthentication(
            context: PlatformContext,
            crypto: BiometricCrypto,
            notifyOnAuthenticationFailure: Boolean,
            resultHandler: DeviceAuthenticationResult,
        ) {
            authPrompts++
            if (authSucceeds) {
                // The real controller invokes this from the BiometricPrompt callback. Driving it
                // directly is what lets the send-after-auth ordering be asserted.
                kotlinx.coroutines.runBlocking { resultHandler.onAuthenticationSuccess() }
            } else {
                resultHandler.onAuthenticationError()
            }
        }
    }

    private companion object {
        const val SCOPE = "ble_scope"
    }

    private val mainDispatcher = UnconfinedTestDispatcher()

    /** `PlatformContext` is `android.content.Context` here; abstract, hence the mock. */
    private val context: PlatformContext = mock<Context>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        vararg responses: ProximityLoadingObserveResponsePartialState,
        sendResult: ProximityLoadingSendRequestedDocumentPartialState =
            ProximityLoadingSendRequestedDocumentPartialState.Success,
        authSucceeds: Boolean = true,
    ) = FakeProximityLoadingInteractor(responses.toList(), sendResult, authSucceeds).let { fake ->
        fake to ProximityLoadingViewModel(fake, SCOPE)
    }

    @Test
    fun a_successful_exchange_navigates_to_the_proximity_success_screen() =
        runTest(mainDispatcher) {
            val (fake, viewModel) = viewModel(ProximityLoadingObserveResponsePartialState.Success)

            val effect = async { viewModel.effect.first() }
            viewModel.setEvent(Event.DoWork(context))
            advanceUntilIdle()

            val navigation = assertIs<Effect.Navigation.SwitchScreen>(effect.await())
            assertEquals(ProximitySuccessRoute(SCOPE), navigation.route)
            assertEquals(SCOPE, fake.presentationScopeId)
            assertNull(viewModel.viewState.value.error)
        }

    @Test
    fun a_request_ready_to_be_sent_is_sent_without_asking_for_authentication() =
        runTest(mainDispatcher) {
            val (fake, viewModel) = viewModel(
                ProximityLoadingObserveResponsePartialState.RequestReadyToBeSent
            )

            viewModel.setEvent(Event.DoWork(context))
            advanceUntilIdle()

            assertEquals(1, fake.sendCalls)
            assertEquals(0, fake.authPrompts)
            assertNull(viewModel.viewState.value.error)
        }

    @Test
    fun a_failure_from_the_response_stream_becomes_an_error() = runTest(mainDispatcher) {
        val (_, viewModel) = viewModel(
            ProximityLoadingObserveResponsePartialState.Failure(error = "transport died")
        )

        viewModel.setEvent(Event.DoWork(context))
        advanceUntilIdle()

        val error = assertNotNull(viewModel.viewState.value.error)
        // Retryable, since a proximity transport failure is often transient.
        assertNotNull(error.onRetry)
    }

    @Test
    fun a_failure_while_sending_becomes_a_retryable_error() = runTest(mainDispatcher) {
        val (fake, viewModel) = viewModel(
            ProximityLoadingObserveResponsePartialState.RequestReadyToBeSent,
            sendResult = ProximityLoadingSendRequestedDocumentPartialState.Failure("send failed"),
        )

        viewModel.setEvent(Event.DoWork(context))
        advanceUntilIdle()

        assertEquals(1, fake.sendCalls)
        assertNotNull(assertNotNull(viewModel.viewState.value.error).onRetry)
    }

    @Test
    fun authentication_is_requested_and_the_documents_are_sent_once_it_succeeds() =
        runTest(mainDispatcher) {
            val (fake, viewModel) = viewModel(
                ProximityLoadingObserveResponsePartialState.UserAuthenticationRequired(
                    authenticationData = listOf(
                        AuthenticationData(
                            crypto = BiometricCrypto(cryptoObject = null),
                            onAuthenticationSuccess = {},
                        )
                    )
                )
            )

            viewModel.setEvent(Event.DoWork(context))
            advanceUntilIdle()

            // The ordering that matters: prompt first, send only after the user authenticates.
            assertEquals(1, fake.authPrompts)
            assertEquals(1, fake.sendCalls)
            assertNull(viewModel.viewState.value.error)
        }

    @Test
    fun every_credential_is_authenticated_before_anything_is_sent() = runTest(mainDispatcher) {
        val unlocked = mutableListOf<Int>()
        val (fake, viewModel) = viewModel(
            ProximityLoadingObserveResponsePartialState.UserAuthenticationRequired(
                authenticationData = listOf(
                    AuthenticationData(BiometricCrypto(null)) { unlocked += 1 },
                    AuthenticationData(BiometricCrypto(null)) { unlocked += 2 },
                )
            )
        )

        viewModel.setEvent(Event.DoWork(context))
        advanceUntilIdle()

        // Two credentials => two prompts, each unlocked, and exactly ONE send at the end.
        assertEquals(2, fake.authPrompts)
        assertEquals(listOf(1, 2), unlocked)
        assertEquals(1, fake.sendCalls)
    }

    @Test
    fun an_empty_authentication_list_errors_instead_of_sending_unlocked() =
        runTest(mainDispatcher) {
            val (fake, viewModel) = viewModel(
                ProximityLoadingObserveResponsePartialState.UserAuthenticationRequired(
                    authenticationData = emptyList()
                )
            )

            viewModel.setEvent(Event.DoWork(context))
            advanceUntilIdle()

            // Nothing to authenticate with must NOT fall through to a send: that would disclose
            // documents whose keys were never unlocked.
            assertEquals(0, fake.sendCalls)
            val error = assertNotNull(viewModel.viewState.value.error)
            assertNull(error.onRetry)   // unrecoverable, so the card offers cancel only
        }

    @Test
    fun going_back_from_the_loading_screen_returns_to_the_request_screen() =
        runTest(mainDispatcher) {
            val (_, viewModel) = viewModel(ProximityLoadingObserveResponsePartialState.Success)

            val effect = async { viewModel.effect.first() }
            viewModel.setEvent(Event.GoBack)
            advanceUntilIdle()

            val navigation = assertIs<Effect.Navigation.PopBackStackUpTo>(effect.await())
            assertEquals(ProximityRequestRoute(SCOPE), navigation.route)
        }

    @Test
    fun the_initial_work_runs_only_once_per_instance() = runTest(mainDispatcher) {
        val (fake, viewModel) = viewModel(
            ProximityLoadingObserveResponsePartialState.RequestReadyToBeSent
        )

        // A plain LaunchedEffect(Unit) calls this again on every recomposition.
        viewModel.startInitialWork(context)
        advanceUntilIdle()
        viewModel.startInitialWork(context)
        advanceUntilIdle()

        // Sending twice would replay the whole presentation.
        assertEquals(1, fake.sendCalls)
    }

    @Test
    fun the_screen_starts_uncancellable_because_proximity_uses_a_five_second_timeout() =
        runTest(mainDispatcher) {
            val (_, viewModel) = viewModel(ProximityLoadingObserveResponsePartialState.Success)

            assertTrue(!viewModel.viewState.value.isCancellable)
        }
}
