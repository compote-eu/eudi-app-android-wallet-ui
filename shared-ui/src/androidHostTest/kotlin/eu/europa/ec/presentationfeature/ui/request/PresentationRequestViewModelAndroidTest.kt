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

// The one piece of request-screen behaviour that CANNOT be tested from common code.
//
// `RequestViewModel.handleOnBack()` finishes the activity instead of popping when the request arrived
// through the Digital Credentials API, and it decides that from `State.intentAction?.type`. Building an
// `IntentAction` needs a `PlatformIntent`, which is an `expect class` with no common constructor — so
// this assertion belongs in the Android host-test source set, where the actual type is a real
// `android.content.Intent`.
//
// That split is the point rather than an inconvenience: it keeps the platform-shaped input at the
// platform edge, and everything else about these view-models stays in commonTest, running on both
// targets. This is the first test in shared-ui's androidHostTest source set.
package eu.europa.ec.presentationfeature.ui.request

import android.content.Intent
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
class PresentationRequestViewModelAndroidTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun dcApiAction() = IntentAction(
        // Never inspected by the view-model — it only reads `type` — so a bare Intent is enough, and
        // needs no Android framework beyond the class itself.
        intent = Intent(),
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
        }
}
