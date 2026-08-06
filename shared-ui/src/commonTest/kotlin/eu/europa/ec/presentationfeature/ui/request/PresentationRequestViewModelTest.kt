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

// PresentationRequestViewModel — the OpenID4VP / DC-API twin of ProximityRequestViewModel.
//
// The shared `RequestViewModel` behaviour (claim selection, combinations, bottom sheets, the
// once-per-instance Init guard) is covered thoroughly in ProximityRequestViewModelTest and is not
// repeated here. This file covers what is DIFFERENT about the presentation side:
//
//  * `init()` pushes the config into the interactor and lands the scope id + intent action in state,
//    which is what `getNextRoute()` and `cleanUp()` then read;
//  * the loading route it hands to the biometric screen is built from that state.
//
// The DC-API back-navigation branch (`Finish` instead of `Pop`) needs a real `IntentAction`, which
// carries a `PlatformIntent` and therefore cannot be constructed from common code at all — it lives in
// PresentationRequestViewModelAndroidTest, next to the platform type it needs.
package eu.europa.ec.presentationfeature.ui.request

import eu.europa.ec.commonfeature.config.PresentationMode
import eu.europa.ec.commonfeature.config.RequestUriConfig
import eu.europa.ec.commonfeature.ui.request.Effect
import eu.europa.ec.commonfeature.ui.request.Event
import eu.europa.ec.commonfeature.ui.request.RequestBottomSheetContent
import eu.europa.ec.commonfeature.ui.request.model.DocumentFormatDomain
import eu.europa.ec.commonfeature.ui.request.model.DocumentPayloadDomain
import eu.europa.ec.commonfeature.ui.request.model.RequestCombinationUi
import eu.europa.ec.commonfeature.ui.request.model.RequestDataUi
import eu.europa.ec.commonfeature.ui.request.model.RequestDocumentItemUi
import eu.europa.ec.corelogic.model.ClaimDomain
import eu.europa.ec.corelogic.model.ClaimPathDomain
import eu.europa.ec.corelogic.model.ClaimType
import eu.europa.ec.presentationfeature.interactor.PresentationRequestInteractor
import eu.europa.ec.presentationfeature.interactor.PresentationRequestInteractorPartialState
import eu.europa.ec.shared.navigation.BiometricRoute
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.ListItemDataUi
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import eu.europa.ec.uilogic.component.ListItemTrailingContentDataUi
import eu.europa.ec.uilogic.component.wrap.CheckboxDataUi
import eu.europa.ec.uilogic.component.wrap.ExpandableListItemUi
import eu.europa.ec.uilogic.navigation.helper.IntentAction
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
class PresentationRequestViewModelTest {

    internal class FakePresentationRequestInteractor(
        private val states: List<PresentationRequestInteractorPartialState>,
    ) : PresentationRequestInteractor {
        override var presentationScopeId: String = ""
            private set

        var configuredWith: RequestUriConfig? = null
            private set
        var configuredIntentAction: IntentAction? = null
            private set
        var stopCount: Int = 0
            private set
        val disclosed: MutableList<RequestCombinationUi?> = mutableListOf()
        var requestCalls: Int = 0
            private set

        override fun setScopeId(scopeId: String) {
            presentationScopeId = scopeId
        }

        override fun setConfig(config: RequestUriConfig, intentAction: IntentAction?) {
            configuredWith = config
            configuredIntentAction = intentAction
            setScopeId(config.presentationScopeId)
        }

        override fun getRequestDocuments(): Flow<PresentationRequestInteractorPartialState> = flow {
            requestCalls++
            states.forEach { emit(it) }
        }

        override fun stopPresentation() {
            stopCount++
        }

        override fun updateRequestedDocuments(selectedCombination: RequestCombinationUi?) {
            disclosed += selectedCombination
        }
    }

    internal companion object {
        val OPENID_CONFIG = RequestUriConfig(
            mode = PresentationMode.OpenId4Vp(
                uri = "openid4vp://request",
                initiatorRoute = DashboardRoute,
            )
        )

        fun document(itemId: String, claimId: String, checked: Boolean) = RequestDocumentItemUi(
            domainPayload = DocumentPayloadDomain(
                docName = "doc-$itemId",
                docId = "id-$itemId",
                docFormatDomain = DocumentFormatDomain.SdJwtVc,
                docClaimsDomain = listOf(
                    ClaimDomain.Primitive(
                        key = claimId,
                        displayTitle = claimId,
                        path = ClaimPathDomain.ofPlainKeys(listOf(claimId), ClaimType.SdJwtVc),
                        value = "v",
                        isRequired = false,
                    )
                ),
            ),
            headerUi = ExpandableListItemUi.NestedListItem(
                header = ListItemDataUi(
                    itemId = itemId,
                    mainContentData = ListItemMainContentDataUi.Text("doc-$itemId"),
                    trailingContentData = ListItemTrailingContentDataUi.Icon(
                        iconData = AppIcons.KeyboardArrowDown,
                    ),
                ),
                nestedItems = listOf(
                    ExpandableListItemUi.SingleListItem(
                        header = ListItemDataUi(
                            itemId = claimId,
                            mainContentData = ListItemMainContentDataUi.Text("claim"),
                            trailingContentData = ListItemTrailingContentDataUi.Checkbox(
                                checkboxData = CheckboxDataUi(isChecked = checked),
                            ),
                        ),
                    ),
                ),
                isExpanded = false,
            ),
        )

        fun success(vararg documents: RequestDocumentItemUi) =
            PresentationRequestInteractorPartialState.Success(
                verifierName = "Acme",
                verifierIsTrusted = true,
                combinationsUi = listOf(
                    RequestCombinationUi(documents = documents.toList(), matches = emptyList())
                ),
                claimsAreSelectable = true,
            )
    }

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(vararg states: PresentationRequestInteractorPartialState) =
        FakePresentationRequestInteractor(states.toList()).let { fake ->
            fake to PresentationRequestViewModel(fake, OPENID_CONFIG)
        }

    @Test
    fun init_pushes_the_config_to_the_interactor_and_records_the_scope() = runTest(mainDispatcher) {
        val (fake, viewModel) = viewModel(success(document("d1", "c1", checked = true)))

        viewModel.setEvent(Event.Init(intentAction = null))
        advanceUntilIdle()

        assertEquals(OPENID_CONFIG, fake.configuredWith)
        assertNull(fake.configuredIntentAction)
        // The scope id must land in STATE here, because getNextRoute()/cleanUp() read it from there
        // rather than from the injected config.
        assertEquals(OPENID_CONFIG.presentationScopeId, viewModel.viewState.value.presentationScopeId)
        assertEquals(OPENID_CONFIG.presentationScopeId, fake.presentationScopeId)
    }

    @Test
    fun a_resolved_request_renders_and_discloses_its_documents() = runTest(mainDispatcher) {
        val (fake, viewModel) = viewModel(success(document("d1", "c1", checked = true)))

        viewModel.setEvent(Event.Init(intentAction = null))
        advanceUntilIdle()

        val state = viewModel.viewState.value
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertIs<RequestDataUi.Single>(state.requestDataUi)
        assertTrue(state.allowShare)
        assertTrue(state.headerConfig.relyingPartyData!!.isVerified)
        assertNotNull(fake.disclosed.lastOrNull())
    }

    @Test
    fun init_runs_the_work_only_once_per_instance() = runTest(mainDispatcher) {
        val (fake, viewModel) = viewModel(success(document("d1", "c1", checked = true)))

        viewModel.setEvent(Event.Init(intentAction = null))
        advanceUntilIdle()
        viewModel.setEvent(Event.Init(intentAction = null))
        advanceUntilIdle()

        assertEquals(1, fake.requestCalls)
    }

    @Test
    fun the_sticky_button_routes_through_device_auth_to_the_presentation_loading_screen() =
        runTest(mainDispatcher) {
            val (_, viewModel) = viewModel(success(document("d1", "c1", checked = true)))
            viewModel.setEvent(Event.Init(intentAction = null))
            advanceUntilIdle()

            val effect = async { viewModel.effect.first() }
            viewModel.setEvent(Event.StickyButtonPressed)
            advanceUntilIdle()

            val navigation = assertIs<Effect.Navigation.SwitchScreen>(effect.await())
            assertIs<BiometricRoute>(navigation.route)
        }

    @Test
    fun a_failure_becomes_a_dismissible_error() = runTest(mainDispatcher) {
        val (_, viewModel) = viewModel(
            PresentationRequestInteractorPartialState.Failure(error = "boom")
        )

        viewModel.setEvent(Event.Init(intentAction = null))
        advanceUntilIdle()
        assertNotNull(viewModel.viewState.value.error)

        viewModel.setEvent(Event.DismissError)
        advanceUntilIdle()
        assertNull(viewModel.viewState.value.error)
    }

    @Test
    fun no_disclosable_data_leaves_share_disabled() = runTest(mainDispatcher) {
        val (_, viewModel) = viewModel(
            PresentationRequestInteractorPartialState.NoData(
                verifierName = "Acme",
                verifierIsTrusted = true,
            )
        )

        viewModel.setEvent(Event.Init(intentAction = null))
        advanceUntilIdle()

        val state = viewModel.viewState.value
        assertIs<RequestDataUi.NoData>(state.requestDataUi)
        assertFalse(state.allowShare)
    }

    @Test
    fun an_untrusted_verifier_stops_the_presentation_and_opens_its_sheet() =
        runTest(mainDispatcher) {
            val (fake, viewModel) = viewModel(
                PresentationRequestInteractorPartialState.VerifierNotTrusted
            )

            val effect = async { viewModel.effect.first() }
            viewModel.setEvent(Event.Init(intentAction = null))
            advanceUntilIdle()

            assertIs<Effect.ShowBottomSheet>(effect.await())
            assertEquals(1, fake.stopCount)
            assertEquals(
                RequestBottomSheetContent.VERIFIER_NOT_TRUSTED,
                viewModel.viewState.value.sheetContent,
            )
        }

    @Test
    fun a_disconnect_navigates_back() = runTest(mainDispatcher) {
        val (_, viewModel) = viewModel(PresentationRequestInteractorPartialState.Disconnect)

        val effect = async { viewModel.effect.first() }
        viewModel.setEvent(Event.Init(intentAction = null))
        advanceUntilIdle()

        assertIs<Effect.Navigation.Pop>(effect.await())
    }

    @Test
    fun back_pops_when_the_request_did_not_arrive_through_the_dc_api() = runTest(mainDispatcher) {
        val (_, viewModel) = viewModel(success(document("d1", "c1", checked = true)))
        viewModel.setEvent(Event.Init(intentAction = null))
        advanceUntilIdle()

        val effect = async { viewModel.effect.first() }
        viewModel.setEvent(Event.OnBack)
        advanceUntilIdle()

        // No IntentAction => an ordinary in-app back. The DC-API counterpart (Finish) is asserted in
        // PresentationRequestViewModelAndroidTest, which can build a real Intent.
        assertIs<Effect.Navigation.Pop>(effect.await())
        assertNull(viewModel.viewState.value.intentAction)
    }
}
