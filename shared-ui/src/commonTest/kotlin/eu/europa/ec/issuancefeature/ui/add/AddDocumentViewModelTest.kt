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

// AddDocumentViewModel. Everything except `Event.IssueDocument` is here; that one carries a
// `PlatformContext` because issuance may raise a device-auth prompt, so it lives in
// AddDocumentViewModelAndroidTest.
package eu.europa.ec.issuancefeature.ui.add

import eu.europa.ec.authenticationlogic.controller.authentication.DeviceAuthenticationResult
import eu.europa.ec.authenticationlogic.model.BiometricCrypto
import eu.europa.ec.commonfeature.config.IssuanceFlowType
import eu.europa.ec.commonfeature.config.IssuanceUiConfig
import eu.europa.ec.commonfeature.config.PresentationMode
import eu.europa.ec.commonfeature.config.QrScanFlow
import eu.europa.ec.corelogic.controller.IssuanceMethod
import eu.europa.ec.issuancefeature.interactor.AddDocumentInteractor
import eu.europa.ec.issuancefeature.interactor.AddDocumentInteractorIssueDocumentsPartialState
import eu.europa.ec.issuancefeature.interactor.AddDocumentInteractorScopedPartialState
import eu.europa.ec.issuancefeature.ui.add.model.AddDocumentUi
import eu.europa.ec.shared.navigation.AddDocumentRoute
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.navigation.DocumentOfferRoute
import eu.europa.ec.shared.navigation.PresentationRequestRoute
import eu.europa.ec.shared.navigation.QrScanRoute
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.uilogic.component.ListItemDataUi
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
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

internal val testIssuanceConfig = IssuanceUiConfig(
    flowType = IssuanceFlowType.ExtraDocument(formatType = null),
)

internal fun addDocumentOptions(vararg ids: String) = listOf(
    "Issuer" to ids.map { id ->
        AddDocumentUi(
            credentialIssuerId = "issuer-1",
            configurationIds = listOf(id),
            itemData = ListItemDataUi(
                itemId = id,
                mainContentData = ListItemMainContentDataUi.Text("Document $id"),
            ),
        )
    }
)

internal class FakeAddDocumentInteractor(
    private val optionResults: List<AddDocumentInteractorScopedPartialState> = listOf(
        AddDocumentInteractorScopedPartialState.Success(options = addDocumentOptions("pid"))
    ),
    private val issueResults: List<AddDocumentInteractorIssueDocumentsPartialState> = emptyList(),
    private val deferredSuccessRoute: AppRoute = DashboardRoute,
) : AddDocumentInteractor {

    var optionCalls: Int = 0
        private set
    var issuedWith: MutableList<Triple<IssuanceMethod, List<String>, String>> = mutableListOf()
        private set
    var resumedUris: MutableList<String> = mutableListOf()
        private set
    var userAuthCalls: Int = 0
        private set

    override fun getAddDocumentOption(
        flowType: IssuanceFlowType,
    ): Flow<AddDocumentInteractorScopedPartialState> = flow {
        val index = optionCalls.coerceAtMost(optionResults.lastIndex)
        optionCalls++
        optionResults.getOrNull(index)?.let { emit(it) }
    }

    override fun issueDocuments(
        issuanceMethod: IssuanceMethod,
        configIds: List<String>,
        issuerId: String,
    ): Flow<AddDocumentInteractorIssueDocumentsPartialState> = flow {
        issuedWith.add(Triple(issuanceMethod, configIds, issuerId))
        issueResults.forEach { emit(it) }
    }

    override fun handleUserAuth(
        context: PlatformContext,
        crypto: BiometricCrypto,
        notifyOnAuthenticationFailure: Boolean,
        resultHandler: DeviceAuthenticationResult,
    ) {
        userAuthCalls++
    }

    override fun buildGenericSuccessRouteForDeferred(flowType: IssuanceFlowType): AppRoute =
        deferredSuccessRoute

    override fun resumeOpenId4VciWithAuthorization(uri: String) {
        resumedUris.add(uri)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AddDocumentViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        interactor: AddDocumentInteractor = FakeAddDocumentInteractor(),
        classifier: DeepLinkClassifier = FakeDeepLinkClassifier(),
        config: IssuanceUiConfig = testIssuanceConfig,
    ) = AddDocumentViewModel(interactor, classifier, config)

    private fun CoroutineScope.collectEffects(
        viewModel: AddDocumentViewModel,
    ): Pair<List<Effect>, Job> {
        val effects = mutableListOf<Effect>()
        val job = launch { viewModel.effect.collect { effects.add(it) } }
        return effects to job
    }

    //region loading the options

    @Test
    fun init_loads_the_add_document_options() = runTest(mainDispatcher) {
        val interactor = FakeAddDocumentInteractor()
        val viewModel = viewModel(interactor)

        viewModel.setEvent(Event.Init(deepLink = null))
        advanceUntilIdle()

        val state = viewModel.viewState.value
        assertEquals(1, interactor.optionCalls)
        assertFalse(state.isLoading)
        assertTrue(state.isInitialised)
        assertFalse(state.noOptions)
        assertEquals(1, state.options.size)
        assertNull(state.error)
    }

    @Test
    fun a_second_init_does_not_reload_the_options() = runTest(mainDispatcher) {
        val interactor = FakeAddDocumentInteractor()
        val viewModel = viewModel(interactor)

        viewModel.setEvent(Event.Init(deepLink = null))
        advanceUntilIdle()
        viewModel.setEvent(Event.Init(deepLink = null))
        advanceUntilIdle()

        // Once the list is there, Init is only a deep-link delivery point.
        assertEquals(1, interactor.optionCalls)
    }

    @Test
    fun no_options_is_not_an_error() = runTest(mainDispatcher) {
        val interactor = FakeAddDocumentInteractor(
            optionResults = listOf(
                AddDocumentInteractorScopedPartialState.NoOptions(errorMsg = "nothing to add")
            )
        )
        val viewModel = viewModel(interactor)

        viewModel.setEvent(Event.Init(deepLink = null))
        advanceUntilIdle()

        // An issuer with nothing on offer is a normal outcome with its own empty state, not a failure.
        val state = viewModel.viewState.value
        assertTrue(state.noOptions)
        assertFalse(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun a_load_failure_with_no_deep_link_shows_a_retryable_error() = runTest(mainDispatcher) {
        val interactor = FakeAddDocumentInteractor(
            optionResults = listOf(
                AddDocumentInteractorScopedPartialState.Failure(error = "boom"),
                AddDocumentInteractorScopedPartialState.Success(options = addDocumentOptions("pid")),
            )
        )
        val viewModel = viewModel(interactor)

        viewModel.setEvent(Event.Init(deepLink = null))
        advanceUntilIdle()

        val error = assertNotNull(viewModel.viewState.value.error)
        assertNotNull(error.onRetry).invoke()
        advanceUntilIdle()

        assertNull(viewModel.viewState.value.error)
        assertEquals(2, interactor.optionCalls)
    }

    @Test
    fun a_load_failure_with_a_deep_link_shows_no_error_and_follows_the_link() =
        runTest(mainDispatcher) {
            // The options are what failed, but the link still has somewhere to go — showing an error
            // card here would bury the destination the user actually asked for.
            val interactor = FakeAddDocumentInteractor(
                optionResults = listOf(
                    AddDocumentInteractorScopedPartialState.Failure(error = "boom")
                )
            )
            val classifier = FakeDeepLinkClassifier(kind = DeepLinkKind.CREDENTIAL_OFFER)
            val viewModel = viewModel(interactor, classifier)

            val (effects, job) = collectEffects(viewModel)
            viewModel.setEvent(Event.Init(deepLink = "openid-credential-offer://x"))
            advanceUntilIdle()
            job.cancel()

            assertNull(viewModel.viewState.value.error)
            val open = effects.filterIsInstance<Effect.Navigation.OpenDeepLinkAction>().single()
            assertIs<DocumentOfferRoute>(open.route)
        }

    //endregion

    //region deep links

    @Test
    fun a_credential_offer_link_opens_the_offer_screen_and_returns_to_the_dashboard() =
        runTest(mainDispatcher) {
            val classifier = FakeDeepLinkClassifier(kind = DeepLinkKind.CREDENTIAL_OFFER)
            val viewModel = viewModel(classifier = classifier)

            val (effects, job) = collectEffects(viewModel)
            viewModel.setEvent(Event.Init(deepLink = "openid-credential-offer://x"))
            advanceUntilIdle()
            job.cancel()

            val open = effects.filterIsInstance<Effect.Navigation.OpenDeepLinkAction>().single()
            assertEquals("openid-credential-offer://x", open.deepLinkUri)
            val route = assertIs<DocumentOfferRoute>(open.route)
            assertEquals("openid-credential-offer://x", route.config.offerUri)
            // On success go to the dashboard, popping this screen so Back cannot return to it.
            val push = assertIs<NavigationType.PushRoute>(route.config.onSuccessNavigation.navigationType)
            assertEquals(DashboardRoute, push.route)
            assertEquals(AddDocumentRoute(testIssuanceConfig), push.popUpTo)
            assertIs<NavigationType.Pop>(route.config.onCancelNavigation.navigationType)
        }

    @Test
    fun an_external_link_is_handed_to_the_host_with_no_destination() = runTest(mainDispatcher) {
        val classifier = FakeDeepLinkClassifier(kind = DeepLinkKind.EXTERNAL)
        val viewModel = viewModel(classifier = classifier)

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.Init(deepLink = "https://issuer.test/info"))
        advanceUntilIdle()
        job.cancel()

        val open = effects.filterIsInstance<Effect.Navigation.OpenDeepLinkAction>().single()
        assertEquals("https://issuer.test/info", open.deepLinkUri)
        assertNull(open.route)
    }

    @Test
    fun a_link_of_any_other_kind_is_ignored() = runTest(mainDispatcher) {
        val classifier = FakeDeepLinkClassifier(kind = DeepLinkKind.OPENID4VP)
        val viewModel = viewModel(classifier = classifier)

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.Init(deepLink = "eudi-openid4vp://request"))
        advanceUntilIdle()
        job.cancel()

        assertTrue(effects.none { it is Effect.Navigation.OpenDeepLinkAction })
    }

    //endregion

    //region navigation and lifecycle

    @Test
    fun scanning_a_qr_opens_the_scanner_in_the_same_issuance_flow() = runTest(mainDispatcher) {
        val viewModel = viewModel()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.GoToQrScan)
        advanceUntilIdle()
        job.cancel()

        val switch = effects.filterIsInstance<Effect.Navigation.SwitchScreen>().single()
        val route = assertIs<QrScanRoute>(switch.route)
        val flow = assertIs<QrScanFlow.Issuance>(route.config.qrScanFlow)
        // The scanner continues *this* flow rather than starting a fresh one.
        assertEquals(testIssuanceConfig.flowType, flow.issuanceFlowType)
    }

    @Test
    fun a_dynamic_presentation_returns_to_this_screen_afterwards() = runTest(mainDispatcher) {
        val viewModel = viewModel()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.OnDynamicPresentation(uri = "openid4vp://request"))
        advanceUntilIdle()
        job.cancel()

        val switch = effects.filterIsInstance<Effect.Navigation.SwitchScreen>().single()
        val route = assertIs<PresentationRequestRoute>(switch.route)
        val mode = assertIs<PresentationMode.OpenId4Vp>(route.config.mode)
        assertEquals("openid4vp://request", mode.uri)
        assertEquals(AddDocumentRoute(testIssuanceConfig), mode.initiatorRoute)
    }

    @Test
    fun resuming_issuance_shows_progress_and_forwards_the_uri() = runTest(mainDispatcher) {
        val interactor = FakeAddDocumentInteractor()
        val viewModel = viewModel(interactor)

        viewModel.setEvent(Event.OnResumeIssuance(uri = "eudi-openid4ci://authorize?code=abc"))
        advanceUntilIdle()

        assertTrue(viewModel.viewState.value.isLoading)
        assertEquals(listOf("eudi-openid4ci://authorize?code=abc"), interactor.resumedUris)
    }

    @Test
    fun pausing_after_the_options_arrived_stops_the_spinner() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        viewModel.setEvent(Event.Init(deepLink = null))
        advanceUntilIdle()

        viewModel.setEvent(Event.OnPause)
        advanceUntilIdle()

        assertFalse(viewModel.viewState.value.isLoading)
    }

    @Test
    fun pausing_before_initialisation_leaves_the_spinner_alone() = runTest(mainDispatcher) {
        // Nothing has loaded, so clearing the loading flag would render an empty screen instead.
        val viewModel = viewModel()
        viewModel.setEvent(Event.OnResumeIssuance(uri = "x"))
        advanceUntilIdle()
        assertTrue(viewModel.viewState.value.isLoading)

        viewModel.setEvent(Event.OnPause)
        advanceUntilIdle()

        assertTrue(viewModel.viewState.value.isLoading)
    }

    @Test
    fun back_pops_from_an_extra_document_flow_and_finishes_from_the_first_one() =
        runTest(mainDispatcher) {
            // The destination used to be a lambda held in State; it is decided by the view-model now,
            // from the flow type, so both cases are worth pinning.
            val extra = viewModel(
                config = IssuanceUiConfig(
                    flowType = IssuanceFlowType.ExtraDocument(formatType = null)
                )
            )
            val (extraEffects, extraJob) = collectEffects(extra)
            extra.setEvent(Event.OnBack)
            advanceUntilIdle()
            extraJob.cancel()
            assertTrue(extraEffects.any { it is Effect.Navigation.Pop })

            val first = viewModel(
                config = IssuanceUiConfig(flowType = IssuanceFlowType.NoDocument)
            )
            val (firstEffects, firstJob) = collectEffects(first)
            first.setEvent(Event.OnBack)
            advanceUntilIdle()
            firstJob.cancel()
            // Nothing to pop back to: this screen is the app's entry point in that flow.
            assertTrue(firstEffects.any { it is Effect.Navigation.Finish })
        }

    @Test
    fun popping_and_finishing_are_distinct() = runTest(mainDispatcher) {
        val viewModel = viewModel()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.Pop)
        advanceUntilIdle()
        viewModel.setEvent(Event.Finish)
        advanceUntilIdle()
        job.cancel()

        // Pop returns within the app; Finish leaves it — this screen can be the app's entry point.
        assertTrue(effects.any { it is Effect.Navigation.Pop })
        assertTrue(effects.any { it is Effect.Navigation.Finish })
    }

    @Test
    fun dismissing_an_error_clears_it() = runTest(mainDispatcher) {
        val interactor = FakeAddDocumentInteractor(
            optionResults = listOf(AddDocumentInteractorScopedPartialState.Failure(error = "boom"))
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
    fun the_bottom_sheet_open_flag_follows_the_host() = runTest(mainDispatcher) {
        val viewModel = viewModel()

        viewModel.setEvent(Event.BottomSheet.UpdateBottomSheetState(isOpen = true))
        advanceUntilIdle()
        assertTrue(viewModel.viewState.value.isBottomSheetOpen)

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.BottomSheet.Close)
        advanceUntilIdle()
        job.cancel()

        assertIs<Effect.CloseBottomSheet>(effects.single())
    }

    //endregion
}
