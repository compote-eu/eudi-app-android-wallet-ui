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

// The pending-intent branch of `DocumentSuccessViewModel.handleStickyButtonPressed`, split out
// because it both takes and emits a `PlatformIntent`. What is asserted is identity — the same handle
// must come back out — so a token from `testPlatformIntent()` is a faithful stand-in.
//
// This is the DC-API return path — the wallet was invoked by the browser, so "done" means finishing the
// activity with a result rather than navigating anywhere in-app. It takes precedence over the
// configured navigation, which is the branch worth pinning: getting it backwards would leave the
// browser waiting forever while the wallet cheerfully returned to its dashboard.
package eu.europa.ec.presentationfeature.ui.success

import eu.europa.ec.shared.platform.testPlatformIntent
import eu.europa.ec.commonfeature.ui.document_success.DocumentSuccessViewModelTest.Companion.SCOPE
import eu.europa.ec.commonfeature.ui.document_success.DocumentSuccessViewModelTest.Companion.documentRow
import eu.europa.ec.commonfeature.ui.document_success.DocumentSuccessViewModelTest.Companion.presentationSuccess
import eu.europa.ec.commonfeature.ui.document_success.DocumentSuccessViewModelTest.FakePresentationSuccessInteractor
import eu.europa.ec.commonfeature.ui.document_success.Effect
import eu.europa.ec.commonfeature.ui.document_success.Event
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
class PresentationSuccessViewModelPlatformHandleTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun a_pending_intent_finishes_with_a_result_instead_of_navigating() = runTest(mainDispatcher) {
        val pending = testPlatformIntent()
        val fake = FakePresentationSuccessInteractor(
            listOf(presentationSuccess(documentRow("d1"))),
            pendingIntent = pending,
        )
        val viewModel = PresentationSuccessViewModel(fake, SCOPE)
        advanceUntilIdle()

        val effect = async { viewModel.effect.first() }
        viewModel.setEvent(Event.StickyButtonPressed)
        advanceUntilIdle()

        val navigation = assertIs<Effect.Navigation.FinishWithResult>(effect.await())
        // The very intent the interactor supplied, handed straight back to the caller.
        assertEquals(pending, navigation.intent)
    }

    @Test
    fun a_pending_intent_wins_over_a_configured_redirect() = runTest(mainDispatcher) {
        val pending = testPlatformIntent()
        val fake = FakePresentationSuccessInteractor(
            listOf(presentationSuccess(documentRow("d1"))),
            // Both are present; the intent must take precedence.
            redirectUri = "https://rp.test/callback",
            pendingIntent = pending,
        )
        val viewModel = PresentationSuccessViewModel(fake, SCOPE)
        advanceUntilIdle()

        val effect = async { viewModel.effect.first() }
        viewModel.setEvent(Event.StickyButtonPressed)
        advanceUntilIdle()

        // Deep-linking here would strand the browser that invoked us.
        assertIs<Effect.Navigation.FinishWithResult>(effect.await())
    }
}
