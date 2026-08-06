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

// PresentationLoadingViewModel's `doWork` — the OpenID4VP / DC-API send path.
//
// Android-only for the same two reasons as its proximity twin, doubled: `Event.DoWork` carries a
// `PlatformContext`, and this interactor's `IntentToSend` state carries a `PlatformIntent`. Both are
// `expect class` with no common constructor and uninhabited on iOS.
//
// The interesting difference from proximity is that this stream has FIVE terminal-ish states rather
// than three, and three of them (`Success`, `Redirect`, `IntentToSend`) all funnel into the same
// `onSuccess()`. Their payloads are deliberately unused here — the redirect URI is read later off
// `PresentationSuccessInteractor` — so these tests pin that convergence explicitly, since a reader
// could otherwise mistake the ignored payloads for a bug.
package eu.europa.ec.presentationfeature.ui.loading

import android.content.Context
import android.content.Intent
import eu.europa.ec.authenticationlogic.controller.authentication.DeviceAuthenticationResult
import eu.europa.ec.authenticationlogic.model.BiometricCrypto
import eu.europa.ec.commonfeature.ui.loading.Effect
import eu.europa.ec.commonfeature.ui.loading.Event
import eu.europa.ec.corelogic.model.AuthenticationData
import eu.europa.ec.presentationfeature.interactor.PresentationLoadingInteractor
import eu.europa.ec.presentationfeature.interactor.PresentationLoadingObserveResponsePartialState
import eu.europa.ec.presentationfeature.interactor.PresentationLoadingSendRequestedDocumentPartialState
import eu.europa.ec.shared.navigation.PresentationRequestRoute
import eu.europa.ec.shared.navigation.PresentationSuccessRoute
import eu.europa.ec.shared.platform.PlatformContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
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
class PresentationLoadingViewModelAndroidTest {

    private class FakePresentationLoadingInteractor(
        private val responses: List<PresentationLoadingObserveResponsePartialState>,
        private val sendResult: PresentationLoadingSendRequestedDocumentPartialState =
            PresentationLoadingSendRequestedDocumentPartialState.Success,
    ) : PresentationLoadingInteractor {
        override var presentationScopeId: String = ""
            private set

        var sendCalls: Int = 0
            private set
        var authPrompts: Int = 0
            private set

        override fun setScopeId(scopeId: String) {
            presentationScopeId = scopeId
        }

        override fun observeResponse(): Flow<PresentationLoadingObserveResponsePartialState> =
            flow { responses.forEach { emit(it) } }

        override suspend fun sendRequestedDocuments():
            PresentationLoadingSendRequestedDocumentPartialState {
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
            runBlocking { resultHandler.onAuthenticationSuccess() }
        }
    }

    private companion object {
        const val SCOPE = "vp_presentation_scope_id"
    }

    private val mainDispatcher = UnconfinedTestDispatcher()
    private val context: PlatformContext = mock<Context>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        vararg responses: PresentationLoadingObserveResponsePartialState,
        sendResult: PresentationLoadingSendRequestedDocumentPartialState =
            PresentationLoadingSendRequestedDocumentPartialState.Success,
    ) = FakePresentationLoadingInteractor(responses.toList(), sendResult).let { fake ->
        fake to PresentationLoadingViewModel(fake, SCOPE)
    }

    private suspend fun assertNavigatesToSuccess(
        response: PresentationLoadingObserveResponsePartialState,
        scope: kotlinx.coroutines.CoroutineScope,
    ) {
        val (_, viewModel) = viewModel(response)
        val effect = scope.async { viewModel.effect.first() }
        viewModel.setEvent(Event.DoWork(context))

        val navigation = assertIs<Effect.Navigation.SwitchScreen>(effect.await())
        assertEquals(PresentationSuccessRoute(SCOPE), navigation.route)
        assertNull(viewModel.viewState.value.error)
    }

    @Test
    fun a_plain_success_navigates_to_the_presentation_success_screen() = runTest(mainDispatcher) {
        assertNavigatesToSuccess(PresentationLoadingObserveResponsePartialState.Success, this)
    }

    @Test
    fun a_redirect_navigates_to_success_and_ignores_the_uri_here() = runTest(mainDispatcher) {
        // The URI is NOT consumed on this screen; PresentationSuccessInteractor reads it later.
        assertNavigatesToSuccess(
            PresentationLoadingObserveResponsePartialState.Redirect(uri = "https://rp.test/done"),
            this,
        )
    }

    @Test
    fun an_intent_to_send_navigates_to_success_and_ignores_the_intent_here() =
        runTest(mainDispatcher) {
            assertNavigatesToSuccess(
                PresentationLoadingObserveResponsePartialState.IntentToSend(intent = Intent()),
                this,
            )
        }

    @Test
    fun a_request_ready_to_be_sent_is_sent_without_asking_for_authentication() =
        runTest(mainDispatcher) {
            val (fake, viewModel) = viewModel(
                PresentationLoadingObserveResponsePartialState.RequestReadyToBeSent
            )

            viewModel.setEvent(Event.DoWork(context))
            advanceUntilIdle()

            assertEquals(1, fake.sendCalls)
            assertEquals(0, fake.authPrompts)
            assertNull(viewModel.viewState.value.error)
        }

    @Test
    fun a_failure_from_the_response_stream_becomes_a_retryable_error() = runTest(mainDispatcher) {
        val (_, viewModel) = viewModel(
            PresentationLoadingObserveResponsePartialState.Failure(error = "verifier rejected")
        )

        viewModel.setEvent(Event.DoWork(context))
        advanceUntilIdle()

        assertNotNull(assertNotNull(viewModel.viewState.value.error).onRetry)
    }

    @Test
    fun a_failure_while_sending_becomes_a_retryable_error() = runTest(mainDispatcher) {
        val (fake, viewModel) = viewModel(
            PresentationLoadingObserveResponsePartialState.RequestReadyToBeSent,
            sendResult = PresentationLoadingSendRequestedDocumentPartialState.Failure("no route"),
        )

        viewModel.setEvent(Event.DoWork(context))
        advanceUntilIdle()

        assertEquals(1, fake.sendCalls)
        assertNotNull(assertNotNull(viewModel.viewState.value.error).onRetry)
    }

    @Test
    fun authentication_is_requested_before_the_documents_are_sent() = runTest(mainDispatcher) {
        val unlocked = mutableListOf<Int>()
        val (fake, viewModel) = viewModel(
            PresentationLoadingObserveResponsePartialState.UserAuthenticationRequired(
                authenticationData = listOf(
                    AuthenticationData(BiometricCrypto(null)) { unlocked += 1 },
                    AuthenticationData(BiometricCrypto(null)) { unlocked += 2 },
                )
            )
        )

        viewModel.setEvent(Event.DoWork(context))
        advanceUntilIdle()

        assertEquals(2, fake.authPrompts)
        assertEquals(listOf(1, 2), unlocked)
        assertEquals(1, fake.sendCalls)
    }

    @Test
    fun an_empty_authentication_list_errors_instead_of_sending_unlocked() =
        runTest(mainDispatcher) {
            val (fake, viewModel) = viewModel(
                PresentationLoadingObserveResponsePartialState.UserAuthenticationRequired(
                    authenticationData = emptyList()
                )
            )

            viewModel.setEvent(Event.DoWork(context))
            advanceUntilIdle()

            assertEquals(0, fake.sendCalls)
            assertNull(assertNotNull(viewModel.viewState.value.error).onRetry)
        }

    @Test
    fun going_back_returns_to_the_presentation_request_screen() = runTest(mainDispatcher) {
        val (_, viewModel) = viewModel(PresentationLoadingObserveResponsePartialState.Success)

        val effect = async { viewModel.effect.first() }
        viewModel.setEvent(Event.GoBack)
        advanceUntilIdle()

        val navigation = assertIs<Effect.Navigation.PopBackStackUpTo>(effect.await())
        assertIs<PresentationRequestRoute>(navigation.route)
    }

    @Test
    fun the_initial_work_runs_only_once_per_instance() = runTest(mainDispatcher) {
        val (fake, viewModel) = viewModel(
            PresentationLoadingObserveResponsePartialState.RequestReadyToBeSent
        )

        viewModel.startInitialWork(context)
        advanceUntilIdle()
        viewModel.startInitialWork(context)
        advanceUntilIdle()

        assertEquals(1, fake.sendCalls)
    }
}
