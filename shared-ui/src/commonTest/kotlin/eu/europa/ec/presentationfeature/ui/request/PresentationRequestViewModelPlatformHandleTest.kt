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

// The one piece of request-screen behaviour that needs a `PlatformIntent`.
//
// `RequestViewModel.handleOnBack()` finishes the activity instead of popping when the request arrived
// through the Digital Credentials API, and it decides that from `State.intentAction?.type`. Building an
// `IntentAction` needs a `PlatformIntent`, an `expect class` with no common constructor — supplied here
// by `testPlatformIntent()`: a real `android.content.Intent` on Android, a token on iOS.
//
// A token suffices because `IntentAction` carries `type` alongside the intent rather than deriving it
// from one, so nothing here reads the intent — it is only passed through.
package eu.europa.ec.presentationfeature.ui.request

import eu.europa.ec.shared.platform.testPlatformIntent
import eu.europa.ec.commonfeature.ui.request.Effect
import eu.europa.ec.commonfeature.ui.request.Event
import eu.europa.ec.presentationfeature.ui.request.PresentationRequestViewModelTest.Companion.OPENID_CONFIG
import eu.europa.ec.presentationfeature.ui.request.PresentationRequestViewModelTest.Companion.document
import eu.europa.ec.presentationfeature.ui.request.PresentationRequestViewModelTest.Companion.success
import eu.europa.ec.presentationfeature.ui.request.PresentationRequestViewModelTest.FakePresentationRequestInteractor
import eu.europa.ec.uilogic.navigation.helper.IntentAction
import eu.europa.ec.uilogic.navigation.helper.IntentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class PresentationRequestViewModelPlatformHandleTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun dcApiAction() = IntentAction(
        // Never inspected by the view-model — it only reads `type` — so a bare Intent is enough, and
        // needs no Android framework beyond the class itself.
        intent = testPlatformIntent(),
        type = IntentType.DC_API,
    )

    @Test
    fun back_finishes_the_activity_when_the_request_came_through_the_dc_api() =
        runTest(mainDispatcher) {
            val fake = FakePresentationRequestInteractor(
                listOf(success(document("d1", "c1", checked = true)))
            )
            val viewModel = PresentationRequestViewModel(fake, OPENID_CONFIG)

            val action = dcApiAction()
            viewModel.setEvent(Event.Init(intentAction = action))
            advanceUntilIdle()

            // The action must reach both the interactor (which builds the wallet-core request from the
            // real Intent) and the state (which decides back behaviour).
            assertEquals(action, fake.configuredIntentAction)
            assertEquals(IntentType.DC_API, viewModel.viewState.value.intentAction?.type)

            val effect = async { viewModel.effect.first() }
            viewModel.setEvent(Event.OnBack)
            advanceUntilIdle()

            // Finish, NOT Pop: there is no in-app back stack to return to when the browser invoked us.
            assertIs<Effect.Navigation.Finish>(effect.await())

            // And nothing is sent to a verifier: a DC API request is answered through the calling app,
            // so there is no OpenID4VP session for an `access_denied` to belong to.
            assertEquals(0, fake.rejectCount)
        }

    @Test
    fun back_tells_the_verifier_the_user_declined_on_an_ordinary_remote_request() =
        runTest(mainDispatcher) {
            val fake = FakePresentationRequestInteractor(
                listOf(success(document("d1", "c1", checked = true)))
            )
            val viewModel = PresentationRequestViewModel(fake, OPENID_CONFIG)

            viewModel.setEvent(Event.Init(intentAction = null))
            advanceUntilIdle()

            val effect = async { viewModel.effect.first() }
            viewModel.setEvent(Event.OnBack)
            advanceUntilIdle()

            assertIs<Effect.Navigation.Pop>(effect.await())
            // The point of the change: the verifier is waiting on an HTTP request and is owed an
            // answer. Measured against the dev verifier on 2026-09-16 — before this, cancelling left
            // its event log stopped at "Request object retrieved" indefinitely.
            assertEquals(1, fake.rejectCount)
        }

    @Test
    fun a_successful_share_never_sends_a_rejection() =
        runTest(mainDispatcher) {
            val fake = FakePresentationRequestInteractor(
                listOf(success(document("d1", "c1", checked = true)))
            )
            val viewModel = PresentationRequestViewModel(fake, OPENID_CONFIG)

            viewModel.setEvent(Event.Init(intentAction = null))
            advanceUntilIdle()

            // Share, not back — the one path that must never apologise to the verifier.
            viewModel.setEvent(Event.StickyButtonPressed)
            advanceUntilIdle()

            assertEquals(0, fake.rejectCount)
            // 🪤 `cleanUp()` is deliberately NOT exercised here: it closes a Koin scope and a unit test
            // has no Koin. It cannot reject anyway — it calls `stopPresentation()`, a different method —
            // and the whole share-then-exit path was watched end to end against the dev verifier on
            // 2026-09-16, which posted a vp_token and no `access_denied`.
        }
}
