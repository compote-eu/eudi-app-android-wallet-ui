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

// The re-issuance branch of DocumentDetailsViewModel — Android-only because
// `IssuerDetails.OnActionButtonClicked` carries a `PlatformContext` (re-issuing a document may need
// to raise a device-authentication prompt).
//
// The assertions that matter here: an untrusted issuer must NOT surface as a generic error but as its
// own sheet (the user can act on that), and a revoked document must not offer re-issuance at all —
// re-issuing from a revoked credential is exactly what the state is there to prevent.
package eu.europa.ec.dashboardfeature.ui.documents.detail

import android.content.Context
import eu.europa.ec.corelogic.model.UntrustedIssuerReasonDomain
import eu.europa.ec.authenticationlogic.model.BiometricCrypto
import eu.europa.ec.dashboardfeature.interactor.DocumentDetailsInteractorIssuancePartialState
import eu.europa.ec.dashboardfeature.interactor.DocumentDetailsInteractorPartialState
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.uilogic.component.IssuerDetailsCardDataUi
import eu.europa.ec.uilogic.navigation.helper.FakeDeepLinkClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentDetailsViewModelAndroidTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    // A mocked Context is faithful here, not a compromise: the view-model only forwards it.
    private val context: PlatformContext = mock<Context>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun loadedViewModel(
        interactor: FakeDocumentDetailsInteractor,
    ): DocumentDetailsViewModel = DocumentDetailsViewModel(
        interactor,
        FakeDeepLinkClassifier(),
        TEST_DOC_ID,
    )

    private fun interactorWith(
        documentState: IssuerDetailsCardDataUi.DocumentState,
        reIssueResults: List<DocumentDetailsInteractorIssuancePartialState> = emptyList(),
    ) = FakeDocumentDetailsInteractor(
        detailsResults = listOf(
            DocumentDetailsInteractorPartialState.Success(
                issuerDetails = issuerDetails(documentState = documentState),
                documentDetailsDomain = documentDetailsDomain(),
                documentIsBookmarked = false,
                documentCredentialsInfoUi = null,
            )
        ),
        reIssueResults = reIssueResults,
    )

    @Test
    fun `an issued document can be re-issued and the screen closes on success`() =
        runTest(mainDispatcher) {
            val interactor = interactorWith(
                documentState = IssuerDetailsCardDataUi.DocumentState.Issued(
                    issuanceDate = "01-01-2024 10:00",
                    expirationDate = "01 Jan 2030",
                ),
                reIssueResults = listOf(DocumentDetailsInteractorIssuancePartialState.Success),
            )
            val viewModel = loadedViewModel(interactor)
            viewModel.setEvent(Event.Init(deepLink = null))
            advanceUntilIdle()

            val effect = async { viewModel.effect.first() }
            viewModel.setEvent(Event.IssuerDetails.OnActionButtonClicked(context))
            advanceUntilIdle()

            // The document's own id and its issuer are what the re-issuance needs.
            assertEquals(listOf(TEST_DOC_ID to "issuer-1"), interactor.reIssuedWith)
            assertIs<Effect.Navigation.Pop>(effect.await())
        }

    @Test
    fun `an expired document can also be re-issued`() = runTest(mainDispatcher) {
        // Expiry is the main reason to re-issue, so this branch must behave like Issued rather than
        // being blocked.
        val interactor = interactorWith(
            documentState = IssuerDetailsCardDataUi.DocumentState.Expired(
                issuanceDate = "01-01-2024 10:00",
                expirationDate = "01 Jan 2020",
            ),
            reIssueResults = listOf(DocumentDetailsInteractorIssuancePartialState.Success),
        )
        val viewModel = loadedViewModel(interactor)
        viewModel.setEvent(Event.Init(deepLink = null))
        advanceUntilIdle()

        viewModel.setEvent(Event.IssuerDetails.OnActionButtonClicked(context))
        advanceUntilIdle()

        assertEquals(listOf(TEST_DOC_ID to "issuer-1"), interactor.reIssuedWith)
    }

    @Test
    fun `a revoked document is never re-issued`() = runTest(mainDispatcher) {
        val interactor = interactorWith(
            documentState = IssuerDetailsCardDataUi.DocumentState.Revoked,
        )
        val viewModel = loadedViewModel(interactor)
        viewModel.setEvent(Event.Init(deepLink = null))
        advanceUntilIdle()

        viewModel.setEvent(Event.IssuerDetails.OnActionButtonClicked(context))
        advanceUntilIdle()

        // Revocation is terminal: asking the issuer again would either fail or, worse, mint a
        // credential from a revoked one.
        assertTrue(interactor.reIssuedWith.isEmpty())
        assertNull(viewModel.viewState.value.error)
    }

    @Test
    fun `an untrusted issuer opens its own sheet instead of an error`() = runTest(mainDispatcher) {
        val interactor = interactorWith(
            documentState = IssuerDetailsCardDataUi.DocumentState.Issued(
                issuanceDate = "01-01-2024 10:00",
                expirationDate = "01 Jan 2030",
            ),
            reIssueResults = listOf(DocumentDetailsInteractorIssuancePartialState.IssuerNotTrusted(
                reason = UntrustedIssuerReasonDomain.ACCESS_CERTIFICATE
            )),
        )
        val viewModel = loadedViewModel(interactor)
        viewModel.setEvent(Event.Init(deepLink = null))
        advanceUntilIdle()

        val effect = async { viewModel.effect.first() }
        viewModel.setEvent(Event.IssuerDetails.OnActionButtonClicked(context))
        advanceUntilIdle()

        assertIs<Effect.ShowBottomSheet>(effect.await())
        assertIs<DocumentDetailsBottomSheetContent.IssuerNotTrusted>(
            viewModel.viewState.value.sheetContent
        )
        // Deliberately not an error card: the spinner stops and the sheet explains the situation.
        assertNull(viewModel.viewState.value.error)
        assertEquals(false, viewModel.viewState.value.isLoading)
    }

    @Test
    fun `a re-issuance failure surfaces a retryable error`() = runTest(mainDispatcher) {
        val interactor = interactorWith(
            documentState = IssuerDetailsCardDataUi.DocumentState.Issued(
                issuanceDate = "01-01-2024 10:00",
                expirationDate = "01 Jan 2030",
            ),
            reIssueResults = listOf(
                DocumentDetailsInteractorIssuancePartialState.Failure(errorMessage = "issuer down")
            ),
        )
        val viewModel = loadedViewModel(interactor)
        viewModel.setEvent(Event.Init(deepLink = null))
        advanceUntilIdle()

        viewModel.setEvent(Event.IssuerDetails.OnActionButtonClicked(context))
        advanceUntilIdle()

        val error = assertNotNull(viewModel.viewState.value.error)
        assertEquals(false, viewModel.viewState.value.isLoading)
        // Cancel only dismisses: the document is still there to look at.
        error.onCancel()
        advanceUntilIdle()
        assertNull(viewModel.viewState.value.error)
    }

    @Test
    fun `re-issuance that needs device auth asks for it, forwarding the crypto handle`() =
        runTest(mainDispatcher) {
            val crypto = BiometricCrypto(cryptoObject = null)
            val interactor = interactorWith(
                documentState = IssuerDetailsCardDataUi.DocumentState.Issued(
                    issuanceDate = "01-01-2024 10:00",
                    expirationDate = "01 Jan 2030",
                ),
                reIssueResults = listOf(
                    DocumentDetailsInteractorIssuancePartialState.UserAuthRequired(
                        crypto = crypto,
                        resultHandler = eu.europa.ec.authenticationlogic.controller.authentication
                            .DeviceAuthenticationResult(),
                    )
                ),
            )
            val viewModel = loadedViewModel(interactor)
            viewModel.setEvent(Event.Init(deepLink = null))
            advanceUntilIdle()

            viewModel.setEvent(Event.IssuerDetails.OnActionButtonClicked(context))
            advanceUntilIdle()

            // The prompt is raised through the platform handle, and the view-model wraps the
            // interactor's handler rather than replacing it, so issuance can continue after auth.
            assertEquals(1, interactor.userAuthCalls)
            assertNotNull(interactor.lastAuthResultHandler)
        }

    @Test
    fun `the action button does nothing before the document has loaded`() = runTest(mainDispatcher) {
        val interactor = FakeDocumentDetailsInteractor()
        val viewModel = loadedViewModel(interactor)

        viewModel.setEvent(Event.IssuerDetails.OnActionButtonClicked(context))
        advanceUntilIdle()

        // There is no issuer card yet, so there is nothing to act on.
        assertTrue(interactor.reIssuedWith.isEmpty())
    }
}
