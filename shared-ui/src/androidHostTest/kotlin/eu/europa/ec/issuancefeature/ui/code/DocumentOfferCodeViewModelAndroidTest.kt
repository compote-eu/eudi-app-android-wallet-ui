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

// The issuance outcomes of DocumentOfferCodeViewModel. Android-only because every one of them is
// reached through `Event.OnPinEntered`, which carries a `PlatformContext`: submitting a transaction
// code can lead to a device-authentication prompt.
//
// This is the pre-authorized-code branch of issuance, so the outcome set is the interesting part —
// six of them, each with a different consequence for where the user ends up.
package eu.europa.ec.issuancefeature.ui.code

import android.content.Context
import eu.europa.ec.corelogic.model.UntrustedIssuerReasonDomain
import eu.europa.ec.authenticationlogic.controller.authentication.DeviceAuthenticationResult
import eu.europa.ec.authenticationlogic.model.BiometricCrypto
import eu.europa.ec.authenticationlogic.secure.SecurePin
import eu.europa.ec.authenticationlogic.secure.SecurePinData
import eu.europa.ec.commonfeature.config.OfferCodeUiConfig
import eu.europa.ec.issuancefeature.interactor.IssueDocumentsInteractorPartialState
import eu.europa.ec.issuancefeature.ui.offer.FakeDocumentOfferInteractor
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.navigation.DocumentIssuanceSuccessRoute
import eu.europa.ec.shared.platform.PlatformContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentOfferCodeViewModelAndroidTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    // A mocked Context is faithful here: the view-model only forwards it to the interactor.
    private val context: PlatformContext = mock<Context>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        interactor: FakeDocumentOfferInteractor,
        config: OfferCodeUiConfig = offerCodeConfig(),
    ) = DocumentOfferCodeViewModel(config, interactor)

    private fun interactorWith(
        vararg results: IssueDocumentsInteractorPartialState,
    ) = FakeDocumentOfferInteractor(issueResults = results.toList())

    private fun CoroutineScope.collectEffects(
        viewModel: DocumentOfferCodeViewModel,
    ): Pair<List<Effect>, Job> {
        val effects = mutableListOf<Effect>()
        val job = launch { viewModel.effect.collect { effects.add(it) } }
        return effects to job
    }

    /**
     * The view-model forwards the code to the interactor as an opaque single-use handle and never reads
     * it, so a fake is enough — the real `SecurePinImpl` is Android-only anyway.
     */
    private class FakeSecurePin(private val value: String) : SecurePin {
        override val length: Int = value.length
        override val isCleared: Boolean = false
        override fun getAndClear(): SecurePinData =
            throw UnsupportedOperationException("not needed by the view-model")

        override fun getAndClearAsString(): String = value
        override fun contentEquals(other: SecurePin): Boolean =
            other is FakeSecurePin && other.value == value

        override fun close() = Unit
    }

    private fun pin(): SecurePin = FakeSecurePin("12345")

    @Test
    fun `submitting the code issues the documents from the configured offer`() =
        runTest(mainDispatcher) {
            val interactor = interactorWith(
                IssueDocumentsInteractorPartialState.Success(documentIds = listOf("doc-1", "doc-2"))
            )
            val config = offerCodeConfig()
            val viewModel = viewModel(interactor, config)

            val (effects, job) = collectEffects(viewModel)
            viewModel.setEvent(Event.OnPinEntered(code = pin(), context = context))
            advanceUntilIdle()
            job.cancel()

            // The uri comes from the config, not from anything the code screen collects.
            assertEquals(listOf(config.offerUri), interactor.issuedWith)
            val switch = effects.filterIsInstance<Effect.Navigation.SwitchScreen>().single()
            val route = assertIs<DocumentIssuanceSuccessRoute>(switch.route)
            assertEquals(listOf("doc-1", "doc-2"), route.config.documentIds)
            // The caller's onSuccess navigation is carried through to the success screen, so the flow
            // returns wherever the offer was started from.
            assertEquals(config.onSuccessNavigation, route.config.onSuccessNavigation)
            assertFalse(viewModel.viewState.value.isLoading)
        }

    @Test
    fun `a deferred issuance goes to the route the interactor built`() = runTest(mainDispatcher) {
        // Deferred issuance has nothing to show yet, so the interactor decides the destination rather
        // than the view-model assembling a document-success screen with no documents.
        val deferredRoute: AppRoute = DashboardRoute
        val interactor = interactorWith(
            IssueDocumentsInteractorPartialState.DeferredSuccess(successRoute = deferredRoute)
        )
        val viewModel = viewModel(interactor)

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.OnPinEntered(code = pin(), context = context))
        advanceUntilIdle()
        job.cancel()

        val switch = effects.filterIsInstance<Effect.Navigation.SwitchScreen>().single()
        assertEquals(deferredRoute, switch.route)
        assertFalse(viewModel.viewState.value.isLoading)
    }

    @Test
    fun `a wrong code surfaces an error that keeps the user on the screen`() =
        runTest(mainDispatcher) {
            val interactor = interactorWith(
                IssueDocumentsInteractorPartialState.Failure(errorMessage = "invalid tx code")
            )
            val viewModel = viewModel(interactor)

            val (effects, job) = collectEffects(viewModel)
            viewModel.setEvent(Event.OnPinEntered(code = pin(), context = context))
            advanceUntilIdle()

            val error = assertNotNull(viewModel.viewState.value.error)
            assertFalse(viewModel.viewState.value.isLoading)
            // No retry: re-submitting the same wrong code is pointless. Cancel dismisses so the user can
            // type a different one — it must NOT navigate away, or a typo would end the whole flow.
            assertNull(error.onRetry)
            error.onCancel()
            advanceUntilIdle()
            job.cancel()

            assertNull(viewModel.viewState.value.error)
            assertTrue(effects.none { it is Effect.Navigation })
        }

    @Test
    fun `an untrusted issuer opens its sheet rather than an error`() = runTest(mainDispatcher) {
        val interactor = interactorWith(IssueDocumentsInteractorPartialState.IssuerNotTrusted(reason = UntrustedIssuerReasonDomain.ACCESS_CERTIFICATE))
        val viewModel = viewModel(interactor)

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.OnPinEntered(code = pin(), context = context))
        advanceUntilIdle()
        job.cancel()

        assertTrue(effects.any { it is Effect.ShowBottomSheet })
        assertIs<DocumentOfferCodeBottomSheetContent.IssuerNotTrusted>(
            viewModel.viewState.value.sheetContent
        )
        assertNull(viewModel.viewState.value.error)
        assertFalse(viewModel.viewState.value.isLoading)
    }

    @Test
    fun `a partial success keeps the issued ids so the sheet can go on to them`() =
        runTest(mainDispatcher) {
            // Some documents WERE issued despite the issuer not being fully trusted. Dropping them here
            // would lose credentials the wallet already holds.
            val interactor = interactorWith(
                IssueDocumentsInteractorPartialState.PartialSuccessWithUntrustedIssuer(
                    issuedDocumentIds = listOf("doc-1"),
                )
            )
            val config = offerCodeConfig()
            val viewModel = viewModel(interactor, config)

            val (effects, job) = collectEffects(viewModel)
            viewModel.setEvent(Event.OnPinEntered(code = pin(), context = context))
            advanceUntilIdle()

            assertTrue(effects.any { it is Effect.ShowBottomSheet })
            val content = assertIs<DocumentOfferCodeBottomSheetContent.PartialSuccessWithUntrustedIssuer>(
                viewModel.viewState.value.sheetContent
            )
            assertEquals(listOf("doc-1"), content.issuedDocumentIds)

            // Dismissing that sheet continues to the success screen with exactly those documents.
            viewModel.setEvent(Event.BottomSheet.FinishedClosing)
            advanceUntilIdle()
            job.cancel()

            val switch = effects.filterIsInstance<Effect.Navigation.SwitchScreen>().single()
            val route = assertIs<DocumentIssuanceSuccessRoute>(switch.route)
            assertEquals(listOf("doc-1"), route.config.documentIds)
            assertEquals(config.onSuccessNavigation, route.config.onSuccessNavigation)
        }

    @Test
    fun `issuance needing device auth raises the prompt through the platform handle`() =
        runTest(mainDispatcher) {
            val handler = DeviceAuthenticationResult()
            val interactor = interactorWith(
                IssueDocumentsInteractorPartialState.UserAuthRequired(
                    crypto = BiometricCrypto(cryptoObject = null),
                    resultHandler = handler,
                )
            )
            val viewModel = viewModel(interactor)

            viewModel.setEvent(Event.OnPinEntered(code = pin(), context = context))
            advanceUntilIdle()

            // The interactor's own handler is passed straight back, so issuance resumes after the
            // prompt instead of being restarted.
            assertEquals(1, interactor.userAuthCalls)
        }
}
