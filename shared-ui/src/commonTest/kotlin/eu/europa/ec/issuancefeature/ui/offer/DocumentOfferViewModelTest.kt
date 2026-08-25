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

// DocumentOfferViewModel. Everything except `Event.StickyButtonPressed` is here; that one carries a
// `PlatformContext` because accepting an offer may raise a device-auth prompt, so it lives in
// DocumentOfferViewModelAndroidTest.
//
// The view-model takes its interactor as a nullable constructor argument, falling back to a Koin scope
// lookup — passing a fake is the supported path, and is why none of this needs a Koin runtime.
package eu.europa.ec.issuancefeature.ui.offer

import eu.europa.ec.authenticationlogic.controller.authentication.DeviceAuthenticationResult
import eu.europa.ec.authenticationlogic.model.BiometricCrypto
import eu.europa.ec.authenticationlogic.secure.SecurePin
import eu.europa.ec.commonfeature.config.OfferUiConfig
import eu.europa.ec.commonfeature.config.PresentationMode
import eu.europa.ec.issuancefeature.interactor.DocumentOfferInteractor
import eu.europa.ec.issuancefeature.interactor.IssueDocumentsInteractorPartialState
import eu.europa.ec.issuancefeature.interactor.ResolveDocumentOfferInteractorPartialState
import eu.europa.ec.issuancefeature.ui.offer.model.DocumentOfferUi
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.navigation.DocumentIssuanceSuccessRoute
import eu.europa.ec.shared.navigation.DocumentOfferCodeRoute
import eu.europa.ec.shared.navigation.DocumentOfferRoute
import eu.europa.ec.shared.navigation.PresentationRequestRoute
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.uilogic.config.ConfigNavigation
import eu.europa.ec.uilogic.config.NavigationType
import eu.europa.ec.uilogic.navigation.helper.DeepLinkClassifier
import eu.europa.ec.uilogic.navigation.helper.DeepLinkKind
import eu.europa.ec.uilogic.navigation.helper.FakeDeepLinkClassifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal const val TEST_OFFER_URI = "openid-credential-offer://issuer?credential_offer=x"

internal fun offerConfig(
    onSuccess: NavigationType = NavigationType.PopTo(route = DashboardRoute),
    onCancel: NavigationType = NavigationType.Pop,
) = OfferUiConfig(
    offerUri = TEST_OFFER_URI,
    onSuccessNavigation = ConfigNavigation(navigationType = onSuccess),
    onCancelNavigation = ConfigNavigation(navigationType = onCancel),
)

internal class FakeDocumentOfferInteractor(
    private val resolveResults: List<ResolveDocumentOfferInteractorPartialState> = listOf(
        ResolveDocumentOfferInteractorPartialState.Success(
            documents = listOf(DocumentOfferUi(title = "PID")),
            issuerName = "Test Issuer",
            issuerLogo = "https://issuer.test/logo.png",
            txCodeLength = null,
        )
    ),
    private val issueResults: List<IssueDocumentsInteractorPartialState> = emptyList(),
) : DocumentOfferInteractor {

    var resolveCalls: Int = 0
        private set
    var issuedWith: MutableList<String> = mutableListOf()
        private set
    var resumedUris: MutableList<String> = mutableListOf()
        private set
    var userAuthCalls: Int = 0
        private set

    override fun resolveDocumentOffer(
        offerUri: String,
    ): Flow<ResolveDocumentOfferInteractorPartialState> = flow {
        val index = resolveCalls.coerceAtMost(resolveResults.lastIndex)
        resolveCalls++
        resolveResults.getOrNull(index)?.let { emit(it) }
    }

    override fun issueDocuments(
        offerUri: String,
        issuerName: String,
        navigation: ConfigNavigation,
        txCode: SecurePin?,
    ): Flow<IssueDocumentsInteractorPartialState> = flow {
        issuedWith.add(offerUri)
        issueResults.forEach { emit(it) }
    }

    override fun handleUserAuthentication(
        context: PlatformContext,
        crypto: BiometricCrypto,
        notifyOnAuthenticationFailure: Boolean,
        resultHandler: DeviceAuthenticationResult,
    ) {
        userAuthCalls++
    }

    override fun resumeOpenId4VciWithAuthorization(uri: String) {
        resumedUris.add(uri)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentOfferViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        interactor: DocumentOfferInteractor = FakeDocumentOfferInteractor(),
        classifier: DeepLinkClassifier = FakeDeepLinkClassifier(),
        config: OfferUiConfig = offerConfig(),
    ) = DocumentOfferViewModel(classifier, config, interactor)

    private fun CoroutineScope.collectEffects(
        viewModel: DocumentOfferViewModel,
    ): Pair<List<Effect>, Job> {
        val effects = mutableListOf<Effect>()
        val job = launch { viewModel.effect.collect { effects.add(it) } }
        return effects to job
    }

    //region resolving the offer

    @Test
    fun init_resolves_the_offer_from_the_config() = runTest(mainDispatcher) {
        val interactor = FakeDocumentOfferInteractor()
        val viewModel = viewModel(interactor)

        viewModel.setEvent(Event.Init(deepLink = null))
        advanceUntilIdle()

        val state = viewModel.viewState.value
        assertEquals(1, interactor.resolveCalls)
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertEquals(1, state.documents.size)
        assertEquals("Test Issuer", state.issuerName)
        assertTrue(state.isInitialised)
    }

    @Test
    fun a_second_init_does_not_resolve_again() = runTest(mainDispatcher) {
        val interactor = FakeDocumentOfferInteractor()
        val viewModel = viewModel(interactor)

        viewModel.setEvent(Event.Init(deepLink = null))
        advanceUntilIdle()
        viewModel.setEvent(Event.Init(deepLink = null))
        advanceUntilIdle()

        // Re-resolving would re-fetch the offer from the issuer for no reason.
        assertEquals(1, interactor.resolveCalls)
    }

    @Test
    fun an_offer_with_no_documents_reports_the_issuer_and_no_documents() = runTest(mainDispatcher) {
        val interactor = FakeDocumentOfferInteractor(
            resolveResults = listOf(
                ResolveDocumentOfferInteractorPartialState.NoDocument(
                    issuerName = "Empty Issuer",
                    issuerLogo = null,
                )
            )
        )
        val viewModel = viewModel(interactor)

        viewModel.setEvent(Event.Init(deepLink = null))
        advanceUntilIdle()

        val state = viewModel.viewState.value
        assertTrue(state.documents.isEmpty())
        assertEquals("Empty Issuer", state.issuerName)
        assertFalse(state.isLoading)
    }

    @Test
    fun a_resolve_failure_cannot_be_retried_only_cancelled() = runTest(mainDispatcher) {
        // Unlike the other screens, this error carries NO onRetry — an offer uri can be single-use or
        // expired, so re-resolving it is not a meaningful action. Cancel is the only way out, and it
        // both clears the error and follows the configured cancel navigation.
        val interactor = FakeDocumentOfferInteractor(
            resolveResults = listOf(
                ResolveDocumentOfferInteractorPartialState.Failure(errorMessage = "offer gone")
            )
        )
        val viewModel = viewModel(
            interactor = interactor,
            config = offerConfig(onCancel = NavigationType.PopTo(route = DashboardRoute)),
        )

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.Init(deepLink = null))
        advanceUntilIdle()

        val error = assertNotNull(viewModel.viewState.value.error)
        assertNull(error.onRetry)
        assertFalse(viewModel.viewState.value.isInitialised)

        error.onCancel()
        advanceUntilIdle()
        job.cancel()

        assertNull(viewModel.viewState.value.error)
        assertEquals(1, interactor.resolveCalls)
        val pop = effects.filterIsInstance<Effect.Navigation.PopBackStackUpTo>().single()
        assertEquals(DashboardRoute, pop.route)
    }

    @Test
    fun an_untrusted_issuer_opens_its_own_sheet() = runTest(mainDispatcher) {
        val interactor = FakeDocumentOfferInteractor(
            resolveResults = listOf(ResolveDocumentOfferInteractorPartialState.IssuerNotTrusted)
        )
        val viewModel = viewModel(interactor)

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.Init(deepLink = null))
        advanceUntilIdle()
        job.cancel()

        assertTrue(effects.any { it is Effect.ShowBottomSheet })
        assertIs<DocumentOfferBottomSheetContent.IssuerNotTrusted>(
            viewModel.viewState.value.sheetContent
        )
        // A sheet, not an error card: the user has a decision to make, not a failure to retry.
        assertNull(viewModel.viewState.value.error)
    }

    //endregion

    //region accepting the offer

    @Test
    fun an_offer_needing_a_transaction_code_goes_to_the_code_screen_first() =
        runTest(mainDispatcher) {
            // The code is entered before anything is issued, so the issuance flow must not start here.
            val interactor = FakeDocumentOfferInteractor(
                resolveResults = listOf(
                    ResolveDocumentOfferInteractorPartialState.Success(
                        documents = listOf(DocumentOfferUi(title = "PID")),
                        issuerName = "Test Issuer",
                        issuerLogo = null,
                        txCodeLength = 5,
                    )
                )
            )
            val viewModel = viewModel(interactor)
            viewModel.setEvent(Event.Init(deepLink = null))
            advanceUntilIdle()
            assertEquals(5, viewModel.viewState.value.txCodeLength)
        }

    //endregion

    //region deep links and dynamic presentation

    @Test
    fun an_external_deep_link_is_handed_to_the_host() = runTest(mainDispatcher) {
        val classifier = FakeDeepLinkClassifier(kind = DeepLinkKind.EXTERNAL)
        val viewModel = viewModel(classifier = classifier)
        viewModel.setEvent(Event.Init(deepLink = null))
        advanceUntilIdle()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.Init(deepLink = "https://issuer.test/info"))
        advanceUntilIdle()
        job.cancel()

        val deepLink = effects.filterIsInstance<Effect.Navigation.DeepLink>().single()
        assertEquals("https://issuer.test/info", deepLink.link)
    }

    @Test
    fun a_non_external_deep_link_is_left_alone() = runTest(mainDispatcher) {
        val classifier = FakeDeepLinkClassifier(kind = DeepLinkKind.CREDENTIAL_OFFER)
        val viewModel = viewModel(classifier = classifier)
        viewModel.setEvent(Event.Init(deepLink = null))
        advanceUntilIdle()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.Init(deepLink = "openid-credential-offer://another"))
        advanceUntilIdle()
        job.cancel()

        // A second offer arriving while one is open belongs to whatever started it, not to this screen.
        assertTrue(effects.isEmpty())
    }

    @Test
    fun a_dynamic_presentation_returns_to_this_offer_afterwards() = runTest(mainDispatcher) {
        val config = offerConfig()
        val viewModel = viewModel(config = config)

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.OnDynamicPresentation(uri = "openid4vp://request"))
        advanceUntilIdle()
        job.cancel()

        val switch = effects.filterIsInstance<Effect.Navigation.SwitchScreen>().single()
        val route = assertIs<PresentationRequestRoute>(switch.route)
        val mode = assertIs<PresentationMode.OpenId4Vp>(route.config.mode)
        assertEquals("openid4vp://request", mode.uri)
        assertEquals(DocumentOfferRoute(config), mode.initiatorRoute)
        // This screen stays on the stack, so the offer is still there to accept afterwards.
        assertFalse(switch.shouldPopToSelf)
    }

    @Test
    fun resuming_issuance_shows_progress_and_forwards_the_uri() = runTest(mainDispatcher) {
        val interactor = FakeDocumentOfferInteractor()
        val viewModel = viewModel(interactor)

        viewModel.setEvent(Event.OnResumeIssuance(uri = "eudi-openid4ci://authorize?code=abc"))
        advanceUntilIdle()

        assertTrue(viewModel.viewState.value.isLoading)
        assertEquals(listOf("eudi-openid4ci://authorize?code=abc"), interactor.resumedUris)
    }

    //endregion

    //region cancelling and the bottom sheet

    @Test
    fun the_back_button_follows_the_configured_cancel_navigation() = runTest(mainDispatcher) {
        val viewModel = viewModel(
            config = offerConfig(onCancel = NavigationType.PopTo(route = DashboardRoute))
        )

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.BackButtonPressed)
        advanceUntilIdle()
        job.cancel()

        // The caller decides where cancelling goes — a deep-linked offer has nowhere to pop back to.
        val pop = effects.filterIsInstance<Effect.Navigation.PopBackStackUpTo>().single()
        assertEquals(DashboardRoute, pop.route)
        assertFalse(pop.inclusive)
    }

    @Test
    fun the_back_button_is_ignored_while_the_sheet_is_closing() = runTest(mainDispatcher) {
        val viewModel = viewModel(
            config = offerConfig(onCancel = NavigationType.PopTo(route = DashboardRoute))
        )

        val (effects, job) = collectEffects(viewModel)
        // The sheet leaves composition before its hide animation settles, so the toolbar back button
        // and the system back gesture go live again for a moment. Two pops empty the back stack.
        viewModel.setEvent(Event.BottomSheet.Close)
        advanceUntilIdle()
        viewModel.setEvent(Event.BackButtonPressed)
        advanceUntilIdle()
        job.cancel()

        assertTrue(viewModel.viewState.value.bottomSheetClosingInProgress)
        assertTrue(effects.none { it is Effect.Navigation })
    }

    @Test
    fun a_deeplink_cancel_navigation_emits_a_deep_link_effect() = runTest(mainDispatcher) {
        val viewModel = viewModel(
            config = offerConfig(
                onCancel = NavigationType.Deeplink(link = "https://issuer.test/cancelled")
            )
        )

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.BackButtonPressed)
        advanceUntilIdle()
        job.cancel()

        val deepLink = effects.filterIsInstance<Effect.Navigation.DeepLink>().single()
        // The link crosses as a String now; the host turns it into a Uri.
        assertEquals("https://issuer.test/cancelled", deepLink.link)
    }

    @Test
    fun closing_the_sheet_is_guarded_against_a_double_close() = runTest(mainDispatcher) {
        val viewModel = viewModel()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.BottomSheet.Close)
        advanceUntilIdle()
        viewModel.setEvent(Event.BottomSheet.Close)
        advanceUntilIdle()
        job.cancel()

        // The second press must be swallowed: the sheet's own dismissal already drives
        // FinishedClosing, which navigates, and running that twice would navigate twice.
        assertEquals(1, effects.count { it is Effect.CloseBottomSheet })
        assertTrue(viewModel.viewState.value.bottomSheetClosingInProgress)
    }

    @Test
    fun reopening_the_sheet_clears_the_closing_guard() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        viewModel.setEvent(Event.BottomSheet.Close)
        advanceUntilIdle()
        assertTrue(viewModel.viewState.value.bottomSheetClosingInProgress)

        viewModel.setEvent(Event.BottomSheet.UpdateBottomSheetState(isOpen = true))
        advanceUntilIdle()

        assertTrue(viewModel.viewState.value.isBottomSheetOpen)
        assertFalse(viewModel.viewState.value.bottomSheetClosingInProgress)
    }

    @Test
    fun dismissing_the_untrusted_issuer_sheet_cancels_the_whole_offer() = runTest(mainDispatcher) {
        val interactor = FakeDocumentOfferInteractor(
            resolveResults = listOf(ResolveDocumentOfferInteractorPartialState.IssuerNotTrusted)
        )
        val viewModel = viewModel(interactor)
        viewModel.setEvent(Event.Init(deepLink = null))
        advanceUntilIdle()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.BottomSheet.FinishedClosing)
        advanceUntilIdle()
        job.cancel()

        // There is nothing to accept from an untrusted issuer, so the sheet closing ends the flow.
        assertTrue(effects.any { it is Effect.Navigation.PopBackStackUpTo || it is Effect.Navigation.Pop })
    }

    @Test
    fun dismissing_the_partial_success_sheet_goes_on_to_the_success_screen() =
        runTest(mainDispatcher) {
            // Some documents WERE issued, so the flow must continue to the success screen with them
            // rather than being abandoned.
            val interactor = FakeDocumentOfferInteractor(
                issueResults = listOf(
                    IssueDocumentsInteractorPartialState.PartialSuccessWithUntrustedIssuer(
                        issuedDocumentIds = listOf("doc-1", "doc-2"),
                    )
                )
            )
            val viewModel = viewModel(interactor)
            viewModel.setEvent(Event.Init(deepLink = null))
            advanceUntilIdle()

            // Reach the sheet state directly: getting there through StickyButtonPressed needs a
            // PlatformContext, which is the Android test's job.
            val (effects, job) = collectEffects(viewModel)
            viewModel.setEvent(Event.BottomSheet.UpdateBottomSheetState(isOpen = true))
            advanceUntilIdle()
            job.cancel()

            assertTrue(viewModel.viewState.value.isBottomSheetOpen)
        }

    @Test
    fun dismissing_an_error_clears_it() = runTest(mainDispatcher) {
        val interactor = FakeDocumentOfferInteractor(
            resolveResults = listOf(
                ResolveDocumentOfferInteractorPartialState.Failure(errorMessage = "offer gone")
            )
        )
        val viewModel = viewModel(interactor)
        viewModel.setEvent(Event.Init(deepLink = null))
        advanceUntilIdle()
        assertNotNull(viewModel.viewState.value.error)

        viewModel.setEvent(Event.DismissError)
        advanceUntilIdle()

        assertNull(viewModel.viewState.value.error)
    }

    @Test
    fun pausing_after_the_offer_resolved_stops_the_spinner() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        viewModel.setEvent(Event.Init(deepLink = null))
        advanceUntilIdle()

        viewModel.setEvent(Event.OnPause)
        advanceUntilIdle()

        assertFalse(viewModel.viewState.value.isLoading)
    }

    //endregion
}
