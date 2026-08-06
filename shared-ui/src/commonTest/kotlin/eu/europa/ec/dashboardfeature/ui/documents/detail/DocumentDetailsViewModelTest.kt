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

// DocumentDetailsViewModel. Everything that does not need a `PlatformContext` is here; the
// re-issuance path carries one (it may raise a device-auth prompt), so it lives in
// DocumentDetailsViewModelAndroidTest.
package eu.europa.ec.dashboardfeature.ui.documents.detail

import eu.europa.ec.authenticationlogic.controller.authentication.DeviceAuthenticationResult
import eu.europa.ec.authenticationlogic.model.BiometricCrypto
import eu.europa.ec.commonfeature.config.PresentationMode
import eu.europa.ec.corelogic.model.ClaimDomain
import eu.europa.ec.corelogic.model.ClaimPathDomain
import eu.europa.ec.corelogic.model.ClaimType
import eu.europa.ec.corelogic.model.DocumentIdentifier
import eu.europa.ec.dashboardfeature.interactor.DocumentDetailsInteractor
import eu.europa.ec.dashboardfeature.interactor.DocumentDetailsInteractorDeleteBookmarkPartialState
import eu.europa.ec.dashboardfeature.interactor.DocumentDetailsInteractorDeleteDocumentPartialState
import eu.europa.ec.dashboardfeature.interactor.DocumentDetailsInteractorIssuancePartialState
import eu.europa.ec.dashboardfeature.interactor.DocumentDetailsInteractorPartialState
import eu.europa.ec.dashboardfeature.interactor.DocumentDetailsInteractorStoreBookmarkPartialState
import eu.europa.ec.dashboardfeature.ui.documents.detail.model.DocumentDetailsDomain
import eu.europa.ec.dashboardfeature.ui.documents.model.DocumentCredentialsInfoUi
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.navigation.DocumentDetailsRoute
import eu.europa.ec.shared.navigation.PresentationRequestRoute
import eu.europa.ec.shared.navigation.SplashRoute
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.uilogic.component.IssuerDetailsCardDataUi
import eu.europa.ec.uilogic.component.wrap.ExpandableListItemUi
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

internal const val TEST_DOC_ID = "doc-1"

internal fun claimDomain(path: String, title: String = path) = ClaimDomain.Primitive(
    key = path,
    value = "value of $path",
    displayTitle = title,
    path = ClaimPathDomain.ofPlainKeys(listOf(path), ClaimType.SdJwtVc),
    isRequired = false,
)

internal fun documentDetailsDomain(
    docId: String = TEST_DOC_ID,
    claims: List<ClaimDomain> = listOf(claimDomain("family_name"), claimDomain("given_name")),
) = DocumentDetailsDomain(
    docName = "PID",
    docId = docId,
    issuerId = "issuer-1",
    documentConfigId = "config-1",
    documentIdentifier = DocumentIdentifier.MdocPid,
    documentClaims = claims,
    documentIssuanceDate = "01-01-2024 10:00",
    documentExpirationDate = "01 Jan 2030",
)

internal fun issuerDetails(
    isExpanded: Boolean = false,
    documentState: IssuerDetailsCardDataUi.DocumentState = IssuerDetailsCardDataUi.DocumentState.Issued(
        issuanceDate = "01-01-2024 10:00",
        expirationDate = "01 Jan 2030",
    ),
) = IssuerDetailsCardDataUi(
    issuerName = "Test Issuer",
    issuerLogo = "https://issuer.test/logo.png",
    documentState = documentState,
    isExpanded = isExpanded,
)

internal class FakeDocumentDetailsInteractor(
    private val detailsResults: List<DocumentDetailsInteractorPartialState> = listOf(
        DocumentDetailsInteractorPartialState.Success(
            issuerDetails = issuerDetails(),
            documentDetailsDomain = documentDetailsDomain(),
            documentIsBookmarked = false,
            documentCredentialsInfoUi = DocumentCredentialsInfoUi(
                availableCredentials = 2,
                totalCredentials = 5,
                title = "2/5",
            ),
        )
    ),
    private val deleteResult: DocumentDetailsInteractorDeleteDocumentPartialState? = null,
    private val storeBookmarkResult: DocumentDetailsInteractorStoreBookmarkPartialState =
        DocumentDetailsInteractorStoreBookmarkPartialState.Success(bookmarkId = TEST_DOC_ID),
    private val deleteBookmarkResult: DocumentDetailsInteractorDeleteBookmarkPartialState =
        DocumentDetailsInteractorDeleteBookmarkPartialState.Success,
    private val reIssueResults: List<DocumentDetailsInteractorIssuancePartialState> = emptyList(),
) : DocumentDetailsInteractor {

    var detailsCalls: Int = 0
        private set
    var lastWasIssuerDetailsExpanded: Boolean? = null
        private set
    var deletedIds: MutableList<String> = mutableListOf()
        private set
    var storedBookmarks: MutableList<String> = mutableListOf()
        private set
    var deletedBookmarks: MutableList<String> = mutableListOf()
        private set
    var reIssuedWith: MutableList<Pair<String, String>> = mutableListOf()
        private set
    var resumedUris: MutableList<String> = mutableListOf()
        private set
    var userAuthCalls: Int = 0
        private set
    var lastAuthResultHandler: DeviceAuthenticationResult? = null
        private set

    override fun getDocumentDetails(
        documentId: String,
        wasIssuerDetailsExpanded: Boolean?,
    ): Flow<DocumentDetailsInteractorPartialState> = flow {
        val index = detailsCalls.coerceAtMost(detailsResults.lastIndex)
        detailsCalls++
        lastWasIssuerDetailsExpanded = wasIssuerDetailsExpanded
        detailsResults.getOrNull(index)?.let { emit(it) }
    }

    override fun deleteDocument(
        documentId: String,
    ): Flow<DocumentDetailsInteractorDeleteDocumentPartialState> = flow {
        deletedIds.add(documentId)
        deleteResult?.let { emit(it) }
    }

    override fun storeBookmark(
        documentId: String,
    ): Flow<DocumentDetailsInteractorStoreBookmarkPartialState> = flow {
        storedBookmarks.add(documentId)
        emit(storeBookmarkResult)
    }

    override fun deleteBookmark(
        documentId: String,
    ): Flow<DocumentDetailsInteractorDeleteBookmarkPartialState> = flow {
        deletedBookmarks.add(documentId)
        emit(deleteBookmarkResult)
    }

    override fun reIssueDocument(
        documentId: String,
        issuerId: String,
    ): Flow<DocumentDetailsInteractorIssuancePartialState> = flow {
        reIssuedWith.add(documentId to issuerId)
        reIssueResults.forEach { emit(it) }
    }

    override fun handleUserAuth(
        context: PlatformContext,
        crypto: BiometricCrypto,
        notifyOnAuthenticationFailure: Boolean,
        resultHandler: DeviceAuthenticationResult,
    ) {
        // onAuthenticationSuccess is a suspend lambda and this contract method is not, so the fake
        // only records the call; the Android test drives the handler where it can.
        userAuthCalls++
        lastAuthResultHandler = resultHandler
    }

    override fun resumeOpenId4VciWithAuthorization(uri: String) {
        resumedUris.add(uri)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentDetailsViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        interactor: DocumentDetailsInteractor = FakeDocumentDetailsInteractor(),
        classifier: DeepLinkClassifier = FakeDeepLinkClassifier(),
        documentId: String = TEST_DOC_ID,
    ) = DocumentDetailsViewModel(interactor, classifier, documentId)

    /** See DocumentsViewModelTest: the effect channel is rendezvous, so collect before sending. */
    private fun CoroutineScope.collectEffects(
        viewModel: DocumentDetailsViewModel,
    ): Pair<List<Effect>, Job> {
        val effects = mutableListOf<Effect>()
        val job = launch { viewModel.effect.collect { effects.add(it) } }
        return effects to job
    }

    //region loading the details

    @Test
    fun init_loads_the_details_and_fills_the_screen() = runTest(mainDispatcher) {
        val interactor = FakeDocumentDetailsInteractor()
        val viewModel = viewModel(interactor)

        viewModel.setEvent(Event.Init(deepLink = null))
        advanceUntilIdle()

        val state = viewModel.viewState.value
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertEquals("PID", state.title)
        assertEquals(TEST_DOC_ID, state.documentDetailsUi?.documentId)
        // The claim tree is expanded into rows by the shared transformer.
        assertEquals(2, state.documentDetailsUi?.documentClaims?.size)
        assertEquals("Test Issuer", state.issuerDetails?.issuerName)
        assertEquals("2/5", state.documentCredentialsInfoUi?.title)
        assertFalse(state.isDocumentBookmarked)
        // Claim values start hidden — this screen shows real identity data.
        assertTrue(state.hideSensitiveContent)
    }

    @Test
    fun a_second_init_does_not_reload_the_details() = runTest(mainDispatcher) {
        val interactor = FakeDocumentDetailsInteractor()
        val viewModel = viewModel(interactor)

        viewModel.setEvent(Event.Init(deepLink = null))
        advanceUntilIdle()
        viewModel.setEvent(Event.Init(deepLink = null))
        advanceUntilIdle()

        // Once loaded, Init is only a deep-link delivery point; reloading would discard the user's
        // claim expansions.
        assertEquals(1, interactor.detailsCalls)
    }

    @Test
    fun reloading_preserves_whether_the_issuer_card_was_expanded() = runTest(mainDispatcher) {
        val interactor = FakeDocumentDetailsInteractor(
            detailsResults = listOf(
                DocumentDetailsInteractorPartialState.Success(
                    issuerDetails = issuerDetails(isExpanded = true),
                    documentDetailsDomain = documentDetailsDomain(),
                    documentIsBookmarked = false,
                    documentCredentialsInfoUi = null,
                )
            )
        )
        val viewModel = viewModel(interactor)

        viewModel.setEvent(Event.Init(deepLink = null))
        advanceUntilIdle()
        // A revocation update triggers a reload; the card must not collapse under the user.
        viewModel.setEvent(Event.OnRevocationStatusChanged(revokedIds = listOf(TEST_DOC_ID)))
        advanceUntilIdle()

        assertEquals(2, interactor.detailsCalls)
        assertEquals(true, interactor.lastWasIssuerDetailsExpanded)
    }

    @Test
    fun a_load_failure_offers_retry_and_a_cancel_that_leaves() = runTest(mainDispatcher) {
        val interactor = FakeDocumentDetailsInteractor(
            detailsResults = listOf(
                DocumentDetailsInteractorPartialState.Failure(error = "boom"),
                DocumentDetailsInteractorPartialState.Success(
                    issuerDetails = issuerDetails(),
                    documentDetailsDomain = documentDetailsDomain(),
                    documentIsBookmarked = false,
                    documentCredentialsInfoUi = null,
                ),
            )
        )
        val viewModel = viewModel(interactor)

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.Init(deepLink = null))
        advanceUntilIdle()

        val error = assertNotNull(viewModel.viewState.value.error)
        assertFalse(viewModel.viewState.value.isLoading)

        error.onRetry!!.invoke()
        advanceUntilIdle()
        assertNull(viewModel.viewState.value.error)
        assertEquals(2, interactor.detailsCalls)
        job.cancel()
        assertTrue(effects.isEmpty())
    }

    @Test
    fun cancelling_a_load_failure_leaves_the_screen() = runTest(mainDispatcher) {
        val interactor = FakeDocumentDetailsInteractor(
            detailsResults = listOf(DocumentDetailsInteractorPartialState.Failure(error = "boom"))
        )
        val viewModel = viewModel(interactor)

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.Init(deepLink = null))
        advanceUntilIdle()
        assertNotNull(viewModel.viewState.value.error).onCancel()
        advanceUntilIdle()
        job.cancel()

        // There is nothing to show without the document, so Cancel pops rather than dismissing.
        assertTrue(effects.any { it is Effect.Navigation.Pop })
    }

    //endregion

    //region claims and visibility

    @Test
    fun clicking_a_claim_toggles_only_that_row() = runTest(mainDispatcher) {
        val interactor = FakeDocumentDetailsInteractor(
            detailsResults = listOf(
                DocumentDetailsInteractorPartialState.Success(
                    issuerDetails = issuerDetails(),
                    documentDetailsDomain = documentDetailsDomain(
                        claims = listOf(
                            ClaimDomain.Group(
                                key = "address",
                                displayTitle = "Address",
                                path = ClaimPathDomain.ofPlainKeys(listOf("address"), ClaimType.SdJwtVc),
                                items = listOf(claimDomain("street")),
                            ),
                            ClaimDomain.Group(
                                key = "other",
                                displayTitle = "Other",
                                path = ClaimPathDomain.ofPlainKeys(listOf("other"), ClaimType.SdJwtVc),
                                items = listOf(claimDomain("thing")),
                            ),
                        )
                    ),
                    documentIsBookmarked = false,
                    documentCredentialsInfoUi = null,
                )
            )
        )
        val viewModel = viewModel(interactor)
        viewModel.setEvent(Event.Init(deepLink = null))
        advanceUntilIdle()

        val groups = viewModel.viewState.value.documentDetailsUi!!.documentClaims
            .filterIsInstance<ExpandableListItemUi.NestedListItem>()
        assertEquals(2, groups.size)
        assertTrue(groups.none { it.isExpanded })

        viewModel.setEvent(Event.ClaimClicked(itemId = groups.first().header.itemId))
        advanceUntilIdle()

        val after = viewModel.viewState.value.documentDetailsUi!!.documentClaims
            .filterIsInstance<ExpandableListItemUi.NestedListItem>()
        assertTrue(after.first().isExpanded)
        assertFalse(after.last().isExpanded)
    }

    @Test
    fun claim_clicks_before_the_document_loads_are_ignored() = runTest(mainDispatcher) {
        val viewModel = viewModel()

        viewModel.setEvent(Event.ClaimClicked(itemId = "anything"))
        advanceUntilIdle()

        assertNull(viewModel.viewState.value.documentDetailsUi)
    }

    @Test
    fun content_visibility_toggles_both_ways() = runTest(mainDispatcher) {
        val viewModel = viewModel()

        viewModel.setEvent(Event.ChangeContentVisibility)
        advanceUntilIdle()
        assertFalse(viewModel.viewState.value.hideSensitiveContent)

        viewModel.setEvent(Event.ChangeContentVisibility)
        advanceUntilIdle()
        assertTrue(viewModel.viewState.value.hideSensitiveContent)
    }

    //endregion

    //region bookmarks

    @Test
    fun bookmarking_an_unbookmarked_document_stores_it() = runTest(mainDispatcher) {
        val interactor = FakeDocumentDetailsInteractor()
        val viewModel = viewModel(interactor)

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.BookmarkPressed)
        advanceUntilIdle()
        job.cancel()

        assertEquals(listOf(TEST_DOC_ID), interactor.storedBookmarks)
        assertTrue(viewModel.viewState.value.isDocumentBookmarked)
        assertTrue(effects.any { it is Effect.BookmarkStored })
    }

    @Test
    fun bookmarking_a_bookmarked_document_removes_it() = runTest(mainDispatcher) {
        val interactor = FakeDocumentDetailsInteractor(
            detailsResults = listOf(
                DocumentDetailsInteractorPartialState.Success(
                    issuerDetails = issuerDetails(),
                    documentDetailsDomain = documentDetailsDomain(),
                    documentIsBookmarked = true,
                    documentCredentialsInfoUi = null,
                )
            )
        )
        val viewModel = viewModel(interactor)
        viewModel.setEvent(Event.Init(deepLink = null))
        advanceUntilIdle()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.BookmarkPressed)
        advanceUntilIdle()
        job.cancel()

        assertEquals(listOf(TEST_DOC_ID), interactor.deletedBookmarks)
        assertFalse(viewModel.viewState.value.isDocumentBookmarked)
        assertTrue(effects.any { it is Effect.BookmarkRemoved })
    }

    @Test
    fun a_failed_bookmark_store_changes_nothing() = runTest(mainDispatcher) {
        val interactor = FakeDocumentDetailsInteractor(
            storeBookmarkResult = DocumentDetailsInteractorStoreBookmarkPartialState.Failure,
        )
        val viewModel = viewModel(interactor)

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.BookmarkPressed)
        advanceUntilIdle()
        job.cancel()

        // The flag must follow the store, not the tap — otherwise the star lies about what is saved.
        assertFalse(viewModel.viewState.value.isDocumentBookmarked)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun the_bookmark_info_sheets_carry_their_own_text() = runTest(mainDispatcher) {
        val viewModel = viewModel()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.OnBookmarkStored)
        advanceUntilIdle()
        val stored = assertIs<DocumentDetailsBottomSheetContent.BookmarkStoredInfo>(
            viewModel.viewState.value.sheetContent
        )

        viewModel.setEvent(Event.OnBookmarkRemoved)
        advanceUntilIdle()
        val removed = assertIs<DocumentDetailsBottomSheetContent.BookmarkRemovedInfo>(
            viewModel.viewState.value.sheetContent
        )
        job.cancel()

        // Different wording for the two directions, which is the whole point of separate cases.
        assertTrue(stored.bottomSheetTextData.title != removed.bottomSheetTextData.title)
        assertEquals(2, effects.count { it is Effect.ShowBottomSheet })
    }

    @Test
    fun the_issuer_card_opens_the_trusted_relying_party_sheet() = runTest(mainDispatcher) {
        val viewModel = viewModel()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.IssuerCardPressed)
        advanceUntilIdle()
        job.cancel()

        assertIs<DocumentDetailsBottomSheetContent.TrustedRelyingPartyInfo>(
            viewModel.viewState.value.sheetContent
        )
        assertIs<Effect.ShowBottomSheet>(effects.single())
    }

    //endregion

    //region deletion

    @Test
    fun the_delete_button_asks_for_confirmation_first() = runTest(mainDispatcher) {
        val interactor = FakeDocumentDetailsInteractor()
        val viewModel = viewModel(interactor)

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.SecondaryButtonPressed)
        advanceUntilIdle()
        job.cancel()

        assertIs<DocumentDetailsBottomSheetContent.DeleteDocumentConfirmation>(
            viewModel.viewState.value.sheetContent
        )
        assertIs<Effect.ShowBottomSheet>(effects.single())
        // Nothing is deleted until the sheet's primary button is pressed.
        assertTrue(interactor.deletedIds.isEmpty())
    }

    @Test
    fun declining_the_confirmation_deletes_nothing() = runTest(mainDispatcher) {
        val interactor = FakeDocumentDetailsInteractor()
        val viewModel = viewModel(interactor)

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.BottomSheet.Delete.SecondaryButtonPressed)
        advanceUntilIdle()
        job.cancel()

        assertIs<Effect.CloseBottomSheet>(effects.single())
        assertTrue(interactor.deletedIds.isEmpty())
    }

    @Test
    fun deleting_one_document_returns_to_the_list() = runTest(mainDispatcher) {
        val interactor = FakeDocumentDetailsInteractor(
            deleteResult = DocumentDetailsInteractorDeleteDocumentPartialState.SingleDocumentDeleted,
        )
        val viewModel = viewModel(interactor)

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.BottomSheet.Delete.PrimaryButtonPressed)
        advanceUntilIdle()
        job.cancel()

        assertEquals(listOf(TEST_DOC_ID), interactor.deletedIds)
        assertTrue(effects.any { it is Effect.Navigation.Pop })
        assertFalse(viewModel.viewState.value.isLoading)
    }

    @Test
    fun deleting_the_last_document_restarts_at_the_splash_screen() = runTest(mainDispatcher) {
        val interactor = FakeDocumentDetailsInteractor(
            deleteResult = DocumentDetailsInteractorDeleteDocumentPartialState.AllDocumentsDeleted,
        )
        val viewModel = viewModel(interactor)

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.BottomSheet.Delete.PrimaryButtonPressed)
        advanceUntilIdle()
        job.cancel()

        // Deleting the last PID under forced activation cannot leave the user on the dashboard.
        val switch = effects.filterIsInstance<Effect.Navigation.SwitchScreen>().single()
        assertEquals(SplashRoute, switch.route)
        assertEquals(DashboardRoute, switch.popUpTo)
        assertEquals(true, switch.inclusive)
    }

    @Test
    fun a_delete_failure_stays_on_the_screen() = runTest(mainDispatcher) {
        val interactor = FakeDocumentDetailsInteractor(
            deleteResult = DocumentDetailsInteractorDeleteDocumentPartialState.Failure(
                errorMessage = "could not delete",
            ),
        )
        val viewModel = viewModel(interactor)

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.BottomSheet.Delete.PrimaryButtonPressed)
        advanceUntilIdle()

        val error = assertNotNull(viewModel.viewState.value.error)
        assertFalse(viewModel.viewState.value.isLoading)
        error.onCancel()
        advanceUntilIdle()
        job.cancel()

        assertNull(viewModel.viewState.value.error)
        assertTrue(effects.none { it is Effect.Navigation })
    }

    //endregion

    //region re-issuance and revocation

    @Test
    fun a_reissued_document_that_disappeared_pops_the_screen() = runTest(mainDispatcher) {
        val interactor = FakeDocumentDetailsInteractor()
        val viewModel = viewModel(interactor)
        viewModel.setEvent(Event.Init(deepLink = null))
        advanceUntilIdle()

        val (effects, job) = collectEffects(viewModel)
        // Re-issuance replaces the document, so this id no longer exists to show.
        viewModel.setEvent(Event.OnReIssuanceTriggered(reIssuedIds = listOf(TEST_DOC_ID)))
        advanceUntilIdle()
        job.cancel()

        assertTrue(effects.any { it is Effect.Navigation.Pop })
    }

    @Test
    fun a_reissuance_of_some_other_document_is_ignored() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        viewModel.setEvent(Event.Init(deepLink = null))
        advanceUntilIdle()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.OnReIssuanceTriggered(reIssuedIds = listOf("some-other-doc")))
        advanceUntilIdle()
        job.cancel()

        assertTrue(effects.isEmpty())
    }

    @Test
    fun the_issuer_card_expansion_toggles() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        viewModel.setEvent(Event.Init(deepLink = null))
        advanceUntilIdle()
        assertFalse(viewModel.viewState.value.issuerDetails!!.isExpanded)

        viewModel.setEvent(Event.IssuerDetails.OnExpandedStateChanged)
        advanceUntilIdle()
        assertTrue(viewModel.viewState.value.issuerDetails!!.isExpanded)

        viewModel.setEvent(Event.IssuerDetails.OnExpandedStateChanged)
        advanceUntilIdle()
        assertFalse(viewModel.viewState.value.issuerDetails!!.isExpanded)
    }

    //endregion

    //region deep links and presentation

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
        assertEquals(listOf("https://issuer.test/info"), classifier.classifiedLinks)
    }

    @Test
    fun a_non_external_deep_link_is_left_to_the_flow_that_owns_it() = runTest(mainDispatcher) {
        // An OPENID4VP link arriving here belongs to the presentation flow already handling it;
        // re-emitting it would hijack that flow.
        val classifier = FakeDeepLinkClassifier(kind = DeepLinkKind.OPENID4VP)
        val viewModel = viewModel(classifier = classifier)
        viewModel.setEvent(Event.Init(deepLink = null))
        advanceUntilIdle()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.Init(deepLink = "eudi-openid4vp://request"))
        advanceUntilIdle()
        job.cancel()

        assertTrue(effects.isEmpty())
    }

    @Test
    fun an_unrecognised_link_is_ignored() = runTest(mainDispatcher) {
        val classifier = FakeDeepLinkClassifier(kind = null)
        val viewModel = viewModel(classifier = classifier)
        viewModel.setEvent(Event.Init(deepLink = null))
        advanceUntilIdle()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.Init(deepLink = "not a link"))
        advanceUntilIdle()
        job.cancel()

        assertTrue(effects.isEmpty())
    }

    @Test
    fun a_dynamic_presentation_opens_the_request_screen_and_can_come_back_here() =
        runTest(mainDispatcher) {
            val viewModel = viewModel()

            val (effects, job) = collectEffects(viewModel)
            viewModel.setEvent(Event.OnDynamicPresentation(uri = "openid4vp://request"))
            advanceUntilIdle()
            job.cancel()

            val switch = effects.filterIsInstance<Effect.Navigation.SwitchScreen>().single()
            val route = assertIs<PresentationRequestRoute>(switch.route)
            val mode = assertIs<PresentationMode.OpenId4Vp>(route.config.mode)
            assertEquals("openid4vp://request", mode.uri)
            // The initiator is this document's own route, so cancelling the request returns here...
            assertEquals(DocumentDetailsRoute(documentId = TEST_DOC_ID), mode.initiatorRoute)
            // ...and this screen is not left on the stack twice.
            assertEquals(DocumentDetailsRoute(documentId = TEST_DOC_ID), switch.popUpTo)
            assertEquals(false, switch.inclusive)
        }

    @Test
    fun resuming_issuance_shows_progress_and_forwards_the_uri() = runTest(mainDispatcher) {
        val interactor = FakeDocumentDetailsInteractor()
        val viewModel = viewModel(interactor)

        viewModel.setEvent(Event.OnResumeIssuance(uri = "eudi-openid4ci://authorize?code=abc"))
        advanceUntilIdle()

        assertTrue(viewModel.viewState.value.isLoading)
        assertEquals(listOf("eudi-openid4ci://authorize?code=abc"), interactor.resumedUris)
    }

    //endregion

    //region lifecycle and errors

    @Test
    fun pausing_with_a_loaded_document_stops_the_spinner() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        viewModel.setEvent(Event.Init(deepLink = null))
        advanceUntilIdle()

        viewModel.setEvent(Event.OnPause)
        advanceUntilIdle()

        assertFalse(viewModel.viewState.value.isLoading)
    }

    @Test
    fun pausing_before_anything_loaded_keeps_the_spinner() = runTest(mainDispatcher) {
        // Without a document there is nothing to show instead, so the screen stays in its loading
        // state rather than rendering blank.
        val viewModel = viewModel()

        viewModel.setEvent(Event.OnPause)
        advanceUntilIdle()

        assertTrue(viewModel.viewState.value.isLoading)
    }

    @Test
    fun dismissing_an_error_clears_it_without_navigating() = runTest(mainDispatcher) {
        val interactor = FakeDocumentDetailsInteractor(
            detailsResults = listOf(DocumentDetailsInteractorPartialState.Failure(error = "boom"))
        )
        val viewModel = viewModel(interactor)
        viewModel.setEvent(Event.Init(deepLink = null))
        advanceUntilIdle()
        assertNotNull(viewModel.viewState.value.error)

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.DismissError)
        advanceUntilIdle()
        job.cancel()

        assertNull(viewModel.viewState.value.error)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun popping_clears_any_error_first() = runTest(mainDispatcher) {
        val interactor = FakeDocumentDetailsInteractor(
            detailsResults = listOf(DocumentDetailsInteractorPartialState.Failure(error = "boom"))
        )
        val viewModel = viewModel(interactor)
        viewModel.setEvent(Event.Init(deepLink = null))
        advanceUntilIdle()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.Pop)
        advanceUntilIdle()
        job.cancel()

        // Otherwise the error card would still be up if this screen were re-entered.
        assertNull(viewModel.viewState.value.error)
        assertTrue(effects.any { it is Effect.Navigation.Pop })
    }

    @Test
    fun the_bottom_sheet_open_flag_follows_the_host() = runTest(mainDispatcher) {
        val viewModel = viewModel()

        viewModel.setEvent(Event.BottomSheet.UpdateBottomSheetState(isOpen = true))
        advanceUntilIdle()
        assertTrue(viewModel.viewState.value.isBottomSheetOpen)

        viewModel.setEvent(Event.BottomSheet.UpdateBottomSheetState(isOpen = false))
        advanceUntilIdle()
        assertFalse(viewModel.viewState.value.isBottomSheetOpen)
    }

    @Test
    fun closing_the_issuer_not_trusted_sheet_only_closes_it() = runTest(mainDispatcher) {
        val viewModel = viewModel()

        val (effects, job) = collectEffects(viewModel)
        viewModel.setEvent(Event.BottomSheet.IssuerNotTrusted.CloseButtonPressed)
        advanceUntilIdle()
        job.cancel()

        assertIs<Effect.CloseBottomSheet>(effects.single())
    }

    //endregion
}
