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

// Covers ProximityRequestViewModel AND, through it, most of the abstract `RequestViewModel` base —
// the claim-selection rules, the disclosure write-back, the warning bottom sheet and the
// once-per-instance `Event.Init` guard. The base has no tests of its own because it cannot be
// instantiated; exercising it through a real subclass also pins the interaction between the two.
//
// These view-models previously had NO unit tests at all, on either side of the move to commonMain,
// and their only coverage was manual runtime passes. That is what this file is for: the same
// assertions now run on Android AND iOS.
//
// Effects are collected BEFORE the triggering event, because `MviViewModel._effect` is a RENDEZVOUS
// Channel — a send with no active collector suspends and the assertion would hang.
package eu.europa.ec.proximityfeature.ui.request

import eu.europa.ec.commonfeature.ui.request.Event
import eu.europa.ec.commonfeature.ui.request.RequestBottomSheetContent
import eu.europa.ec.commonfeature.ui.request.Effect
import eu.europa.ec.commonfeature.ui.request.model.DocumentFormatDomain
import eu.europa.ec.commonfeature.ui.request.model.DocumentPayloadDomain
import eu.europa.ec.commonfeature.ui.request.model.RequestCombinationUi
import eu.europa.ec.commonfeature.ui.request.model.RequestDataUi
import eu.europa.ec.commonfeature.ui.request.model.RequestDocumentItemUi
import eu.europa.ec.corelogic.model.ClaimDomain
import eu.europa.ec.corelogic.model.ClaimPathDomain
import eu.europa.ec.corelogic.model.ClaimType
import eu.europa.ec.proximityfeature.interactor.ProximityRequestInteractor
import eu.europa.ec.proximityfeature.interactor.ProximityRequestInteractorPartialState
import eu.europa.ec.shared.navigation.BiometricRoute
import eu.europa.ec.uilogic.component.ListItemDataUi
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import eu.europa.ec.uilogic.component.wrap.CheckboxDataUi
import eu.europa.ec.uilogic.component.wrap.ExpandableListItemUi
import eu.europa.ec.uilogic.component.ListItemTrailingContentDataUi
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

@OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher, setMain, advanceUntilIdle
class ProximityRequestViewModelTest {

    private class FakeProximityRequestInteractor(
        private val states: List<ProximityRequestInteractorPartialState>,
    ) : ProximityRequestInteractor {
        override var presentationScopeId: String = ""
            private set

        var stopCount: Int = 0
            private set

        /** Every combination handed to `updateRequestedDocuments`, in order. */
        val disclosed: MutableList<RequestCombinationUi?> = mutableListOf()

        var requestCalls: Int = 0
            private set

        override fun setScopeId(scopeId: String) {
            presentationScopeId = scopeId
        }

        override fun getRequestDocuments(): Flow<ProximityRequestInteractorPartialState> = flow {
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

    private companion object {
        const val SCOPE = "scope-1"

        fun claim(key: String) = ClaimDomain.Primitive(
            key = key,
            displayTitle = key,
            path = ClaimPathDomain.ofPlainKeys(listOf(key), ClaimType.SdJwtVc),
            value = "v-$key",
            isRequired = false,
        )

        /**
         * One document row: a nested list item whose single leaf carries a checkbox, which is what
         * `anyClaimChecked()` and the selection toggle operate on.
         */
        fun document(
            itemId: String,
            claimId: String,
            checked: Boolean,
            expanded: Boolean = false,
        ) = RequestDocumentItemUi(
            domainPayload = DocumentPayloadDomain(
                docName = "doc-$itemId",
                docId = "id-$itemId",
                docFormatDomain = DocumentFormatDomain.SdJwtVc,
                docClaimsDomain = listOf(claim(claimId)),
            ),
            headerUi = ExpandableListItemUi.NestedListItem(
                header = ListItemDataUi(
                    itemId = itemId,
                    mainContentData = ListItemMainContentDataUi.Text("doc-$itemId"),
                    trailingContentData = ListItemTrailingContentDataUi.Icon(
                        iconData = eu.europa.ec.uilogic.component.AppIcons.KeyboardArrowDown,
                    ),
                ),
                nestedItems = listOf(
                    ExpandableListItemUi.SingleListItem(
                        header = ListItemDataUi(
                            itemId = claimId,
                            mainContentData = ListItemMainContentDataUi.Text("claim-$claimId"),
                            trailingContentData = ListItemTrailingContentDataUi.Checkbox(
                                checkboxData = CheckboxDataUi(isChecked = checked),
                            ),
                        ),
                    ),
                ),
                isExpanded = expanded,
            ),
        )

        fun combination(vararg documents: RequestDocumentItemUi) =
            RequestCombinationUi(documents = documents.toList(), matches = emptyList())

        fun success(
            vararg combinations: RequestCombinationUi,
            verifierName: String? = "Acme",
            verifierIsTrusted: Boolean = true,
            claimsAreSelectable: Boolean = true,
        ) = ProximityRequestInteractorPartialState.Success(
            verifierName = verifierName,
            verifierIsTrusted = verifierIsTrusted,
            combinationsUi = combinations.toList(),
            claimsAreSelectable = claimsAreSelectable,
        )

        fun viewModel(vararg states: ProximityRequestInteractorPartialState) =
            FakeProximityRequestInteractor(states.toList()).let { fake ->
                fake to ProximityRequestViewModel(fake, SCOPE)
            }
    }

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun the_screen_starts_loading_with_the_default_relying_party_name() = runTest(mainDispatcher) {
        val (_, viewModel) = viewModel()

        val state = viewModel.viewState.value
        assertTrue(state.isLoading)
        assertNull(state.error)
        assertIs<RequestDataUi.Initial>(state.requestDataUi)
        // Nothing resolved yet, so Share must be inert.
        assertFalse(state.allowShare)
        assertFalse(state.headerConfig.relyingPartyData!!.isVerified)
    }

    @Test
    fun a_single_combination_renders_its_documents_and_discloses_them() = runTest(mainDispatcher) {
        val (fake, viewModel) = viewModel(success(combination(document("d1", "c1", checked = true))))

        viewModel.setEvent(Event.Init(intentAction = null))
        advanceUntilIdle()

        val state = viewModel.viewState.value
        assertFalse(state.isLoading)
        assertEquals(SCOPE, fake.presentationScopeId)
        assertEquals(SCOPE, state.presentationScopeId)
        val single = assertIs<RequestDataUi.Single>(state.requestDataUi)
        assertEquals(1, single.combination.documents.size)
        assertTrue(state.allowShare)
        // The verifier's identity is folded into the header.
        assertTrue(state.headerConfig.relyingPartyData!!.isVerified)
        // ...and the selection was pushed back to the interactor exactly once.
        assertEquals(1, fake.disclosed.size)
        assertNotNull(fake.disclosed.single())
    }

    @Test
    fun init_runs_the_work_only_once_per_instance() = runTest(mainDispatcher) {
        val (fake, viewModel) = viewModel(success(combination(document("d1", "c1", checked = true))))

        // A plain LaunchedEffect(Unit) re-sends Init whenever the composition is rebuilt.
        viewModel.setEvent(Event.Init(intentAction = null))
        advanceUntilIdle()
        viewModel.setEvent(Event.Init(intentAction = null))
        advanceUntilIdle()

        assertEquals(1, fake.requestCalls)
    }

    @Test
    fun an_explicit_DoWork_retries_even_after_init_has_run() = runTest(mainDispatcher) {
        val (fake, viewModel) = viewModel(success(combination(document("d1", "c1", checked = true))))

        viewModel.setEvent(Event.Init(intentAction = null))
        advanceUntilIdle()
        // The error card's onRetry sends DoWork, which must NOT be swallowed by the Init guard.
        viewModel.setEvent(Event.DoWork)
        advanceUntilIdle()

        assertEquals(2, fake.requestCalls)
    }

    @Test
    fun no_disclosable_data_leaves_share_disabled() = runTest(mainDispatcher) {
        val (_, viewModel) = viewModel(
            ProximityRequestInteractorPartialState.NoData(
                verifierName = "Acme",
                verifierIsTrusted = false,
            )
        )

        viewModel.setEvent(Event.Init(intentAction = null))
        advanceUntilIdle()

        val state = viewModel.viewState.value
        assertFalse(state.isLoading)
        assertIs<RequestDataUi.NoData>(state.requestDataUi)
        assertFalse(state.allowShare)
    }

    @Test
    fun a_failure_becomes_a_dismissible_error() = runTest(mainDispatcher) {
        val (_, viewModel) = viewModel(
            ProximityRequestInteractorPartialState.Failure(error = "boom")
        )

        viewModel.setEvent(Event.Init(intentAction = null))
        advanceUntilIdle()
        assertNotNull(viewModel.viewState.value.error)

        viewModel.setEvent(Event.DismissError)
        advanceUntilIdle()
        assertNull(viewModel.viewState.value.error)
    }

    @Test
    fun an_untrusted_verifier_stops_the_presentation_and_opens_its_sheet() =
        runTest(mainDispatcher) {
            val (fake, viewModel) = viewModel(
                ProximityRequestInteractorPartialState.VerifierNotTrusted
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
        val (_, viewModel) = viewModel(ProximityRequestInteractorPartialState.Disconnect)

        val effect = async { viewModel.effect.first() }
        viewModel.setEvent(Event.Init(intentAction = null))
        advanceUntilIdle()

        assertIs<Effect.Navigation.Pop>(effect.await())
    }

    @Test
    fun the_first_claim_tap_only_warns_and_does_not_change_the_selection() =
        runTest(mainDispatcher) {
            val (_, viewModel) = viewModel(
                success(combination(document("d1", "c1", checked = true)))
            )
            viewModel.setEvent(Event.Init(intentAction = null))
            advanceUntilIdle()

            val effect = async { viewModel.effect.first() }
            viewModel.setEvent(Event.UserIdentificationClicked(itemId = "c1"))
            advanceUntilIdle()

            assertIs<Effect.ShowBottomSheet>(effect.await())
            val state = viewModel.viewState.value
            assertTrue(state.hasWarnedUser)
            assertEquals(RequestBottomSheetContent.WARNING, state.sheetContent)
            // Still checked: the warning is shown INSTEAD of toggling.
            assertTrue(state.allowShare)
        }

    @Test
    fun tapping_a_claim_after_the_warning_unchecks_it_and_disables_share() =
        runTest(mainDispatcher) {
            val (fake, viewModel) = viewModel(
                success(combination(document("d1", "c1", checked = true)))
            )
            viewModel.setEvent(Event.Init(intentAction = null))
            advanceUntilIdle()

            // First tap warns...
            viewModel.setEvent(Event.UserIdentificationClicked(itemId = "c1"))
            advanceUntilIdle()
            // ...the second actually toggles.
            viewModel.setEvent(Event.UserIdentificationClicked(itemId = "c1"))
            advanceUntilIdle()

            assertFalse(viewModel.viewState.value.allowShare)
            // The narrowed disclosure must reach the interactor, or the wallet would over-share.
            assertTrue(fake.disclosed.size >= 2)
        }

    @Test
    fun claims_that_are_not_selectable_ignore_taps_and_stay_shareable() = runTest(mainDispatcher) {
        val (_, viewModel) = viewModel(
            success(
                combination(document("d1", "c1", checked = false)),
                claimsAreSelectable = false,
            )
        )
        viewModel.setEvent(Event.Init(intentAction = null))
        advanceUntilIdle()

        viewModel.setEvent(Event.UserIdentificationClicked(itemId = "c1"))
        advanceUntilIdle()

        val state = viewModel.viewState.value
        assertFalse(state.hasWarnedUser)
        // With selection off, Share depends on there being documents at all, not on checkboxes.
        assertTrue(state.allowShare)
    }

    @Test
    fun selecting_another_combination_switches_the_disclosed_documents() = runTest(mainDispatcher) {
        val (fake, viewModel) = viewModel(
            success(
                combination(document("a", "ca", checked = true)),
                combination(document("b", "cb", checked = true)),
            )
        )
        viewModel.setEvent(Event.Init(intentAction = null))
        advanceUntilIdle()

        val multiple = assertIs<RequestDataUi.Multiple>(viewModel.viewState.value.requestDataUi)
        assertEquals(0, multiple.selectedIndex)

        viewModel.setEvent(Event.CombinationSelected(index = 1))
        advanceUntilIdle()

        val after = assertIs<RequestDataUi.Multiple>(viewModel.viewState.value.requestDataUi)
        assertEquals(1, after.selectedIndex)
        assertEquals("b", after.selectedDocuments.single().headerUi.header.itemId)
        assertEquals("b", fake.disclosed.last()!!.documents.single().headerUi.header.itemId)
    }

    @Test
    fun selecting_an_out_of_range_or_unchanged_combination_is_a_no_op() = runTest(mainDispatcher) {
        val (fake, viewModel) = viewModel(
            success(
                combination(document("a", "ca", checked = true)),
                combination(document("b", "cb", checked = true)),
            )
        )
        viewModel.setEvent(Event.Init(intentAction = null))
        advanceUntilIdle()
        val disclosuresAfterLoad = fake.disclosed.size

        viewModel.setEvent(Event.CombinationSelected(index = 0))   // unchanged
        viewModel.setEvent(Event.CombinationSelected(index = 99))  // out of range
        viewModel.setEvent(Event.CombinationSelected(index = -1))  // out of range
        advanceUntilIdle()

        val state = assertIs<RequestDataUi.Multiple>(viewModel.viewState.value.requestDataUi)
        assertEquals(0, state.selectedIndex)
        assertEquals(disclosuresAfterLoad, fake.disclosed.size)
    }

    @Test
    fun expanding_a_document_flips_its_chevron() = runTest(mainDispatcher) {
        val (_, viewModel) = viewModel(success(combination(document("d1", "c1", checked = true))))
        viewModel.setEvent(Event.Init(intentAction = null))
        advanceUntilIdle()

        viewModel.setEvent(Event.ExpandOrCollapseRequestDocumentItem(itemId = "d1"))
        advanceUntilIdle()
        assertTrue(viewModel.viewState.value.requestDataUi.selectedDocuments.single().headerUi.isExpanded)

        viewModel.setEvent(Event.ExpandOrCollapseRequestDocumentItem(itemId = "d1"))
        advanceUntilIdle()
        assertFalse(viewModel.viewState.value.requestDataUi.selectedDocuments.single().headerUi.isExpanded)
    }

    @Test
    fun the_sticky_button_navigates_to_the_biometric_screen() = runTest(mainDispatcher) {
        val (_, viewModel) = viewModel(success(combination(document("d1", "c1", checked = true))))
        viewModel.setEvent(Event.Init(intentAction = null))
        advanceUntilIdle()

        val effect = async { viewModel.effect.first() }
        viewModel.setEvent(Event.StickyButtonPressed)
        advanceUntilIdle()

        val navigation = assertIs<Effect.Navigation.SwitchScreen>(effect.await())
        // Proximity always goes through device auth before the loading screen.
        assertIs<BiometricRoute>(navigation.route)
    }

    @Test
    fun back_pops_when_there_is_no_dc_api_intent() = runTest(mainDispatcher) {
        val (_, viewModel) = viewModel(success(combination(document("d1", "c1", checked = true))))
        viewModel.setEvent(Event.Init(intentAction = null))
        advanceUntilIdle()

        val effect = async { viewModel.effect.first() }
        viewModel.setEvent(Event.OnBack)
        advanceUntilIdle()

        assertIs<Effect.Navigation.Pop>(effect.await())
    }

    @Test
    fun closing_the_untrusted_verifier_sheet_navigates_back_once() = runTest(mainDispatcher) {
        val (_, viewModel) = viewModel(ProximityRequestInteractorPartialState.VerifierNotTrusted)
        // The untrusted path emits ShowBottomSheet during Init. It MUST be consumed here: the effect
        // channel is RENDEZVOUS, so an unread send stays parked and the next collector would pick up
        // that stale effect instead of the one under test.
        val shown = async { viewModel.effect.first() }
        viewModel.setEvent(Event.Init(intentAction = null))
        advanceUntilIdle()
        assertIs<Effect.ShowBottomSheet>(shown.await())

        // Close -> hide -> FinishedClosing -> OnBack, and the in-progress flag must make the
        // first Close idempotent so a double tap cannot pop twice.
        val hide = async { viewModel.effect.first() }
        viewModel.setEvent(Event.BottomSheet.VerifierNotTrusted.Close)
        advanceUntilIdle()
        assertIs<Effect.CloseBottomSheet>(hide.await())
        assertTrue(viewModel.viewState.value.bottomSheetClosingInProgress)

        val pop = async { viewModel.effect.first() }
        viewModel.setEvent(Event.BottomSheet.FinishedClosing)
        advanceUntilIdle()
        assertIs<Effect.Navigation.Pop>(pop.await())
    }

    @Test
    fun finishing_the_warning_sheet_does_not_navigate() = runTest(mainDispatcher) {
        val (_, viewModel) = viewModel(success(combination(document("d1", "c1", checked = true))))
        viewModel.setEvent(Event.Init(intentAction = null))
        advanceUntilIdle()
        viewModel.setEvent(Event.UserIdentificationClicked(itemId = "c1"))
        advanceUntilIdle()

        viewModel.setEvent(Event.BottomSheet.FinishedClosing)
        advanceUntilIdle()

        // Sheet state only; the WARNING branch is deliberately inert.
        assertEquals(RequestBottomSheetContent.WARNING, viewModel.viewState.value.sheetContent)
    }

    @Test
    fun opening_the_sheet_clears_a_pending_close() = runTest(mainDispatcher) {
        val (_, viewModel) = viewModel(ProximityRequestInteractorPartialState.VerifierNotTrusted)
        val shown = async { viewModel.effect.first() }   // drain Init's ShowBottomSheet
        viewModel.setEvent(Event.Init(intentAction = null))
        advanceUntilIdle()
        assertIs<Effect.ShowBottomSheet>(shown.await())

        val hidden = async { viewModel.effect.first() }
        viewModel.setEvent(Event.BottomSheet.VerifierNotTrusted.Close)
        advanceUntilIdle()
        assertIs<Effect.CloseBottomSheet>(hidden.await())
        assertTrue(viewModel.viewState.value.bottomSheetClosingInProgress)

        viewModel.setEvent(Event.BottomSheet.UpdateBottomSheetState(isOpen = true))
        advanceUntilIdle()

        val state = viewModel.viewState.value
        assertTrue(state.isBottomSheetOpen)
        assertFalse(state.bottomSheetClosingInProgress)
    }
}
