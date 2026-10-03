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

// Upstream bf514519's `TestTrustMarkInteractor`, every case kept, over fakes instead of Mockito so it runs in
// commonTest: the URL rules (cases 3–5) are then pinned on iOS too, where they rest on `ParsedUri` rather
// than on `java.net.URI`.
package eu.europa.ec.commonfeature.interactor

import eu.europa.ec.businesslogic.controller.storage.TrustMarkIntroductionStore
import eu.europa.ec.commonfeature.ui.trustmark.model.TrustMarkParagraphUi
import eu.europa.ec.commonfeature.ui.trustmark.model.TrustMarkUi
import eu.europa.ec.corelogic.controller.WalletCoreTrustMarkController
import eu.europa.ec.corelogic.model.TrustMarkDomain
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.StringCatalog
import eu.europa.ec.shared.resources.generic_error_message
import eu.europa.ec.shared.resources.trust_mark_certification_description
import eu.europa.ec.shared.resources.trust_mark_certification_information_description
import eu.europa.ec.shared.resources.trust_mark_certification_information_link
import eu.europa.ec.shared.resources.trust_mark_certified_wallets_link
import eu.europa.ec.shared.resources.trust_mark_load_error
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.StringResource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TestTrustMarkInteractor {

    private val controller = FakeTrustMarkController()
    private val introductionStore = FakeIntroductionStore()
    private val strings = FakeTrustMarkStrings()

    private fun TestScope.interactor(): TrustMarkInteractor = TrustMarkInteractorImpl(
        walletCoreTrustMarkController = controller,
        introductionStore = introductionStore,
        strings = strings,
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    @BeforeTest
    fun before() {
        strings.paragraph(
            textRes = Res.string.trust_mark_certification_description,
            linkRes = Res.string.trust_mark_certified_wallets_link,
            label = mockedTrustedListLabel,
            text = mockedCertificationDescription.text,
        )
        strings.paragraph(
            textRes = Res.string.trust_mark_certification_information_description,
            linkRes = Res.string.trust_mark_certification_information_link,
            label = mockedInformationLabel,
            text = mockedInformationDescription.text,
        )
        strings.values[Res.string.trust_mark_load_error] = mockedTrustMarkLoadError
        strings.values[Res.string.generic_error_message] = mockedGenericErrorMessage
    }

    //region getTrustMark

    // Case 1:
    // 1. The domain value includes localized text and absolute web URLs.
    // Case 1 Expected Result:
    // Display data preserves the text and targets without completing the introduction.
    @Test
    fun given_case_1_when_getTrustMark_is_called_then_case_1_expected_result_is_returned() = runTest {
        // Given
        controller.result = Result.success(mockedTrustMark)

        // When
        val result = interactor().getTrustMark()

        // Then
        assertEquals(LoadTrustMarkPartialState.Success(mockedTrustMarkUi), result)
        assertTrue(introductionStore.writes.isEmpty())
    }

    // Case 2:
    // 1. The domain value has no localized text.
    // Case 2 Expected Result:
    // The paragraph is omitted while the image and links remain available.
    @Test
    fun given_case_2_when_getTrustMark_is_called_then_case_2_expected_result_is_returned() = runTest {
        // Given
        controller.result = Result.success(mockedTrustMark.copy(localisedText = null))

        // When
        val result = interactor().getTrustMark()

        // Then
        assertEquals(
            LoadTrustMarkPartialState.Success(mockedTrustMarkUi.copy(text = null)),
            result,
        )
    }

    // Case 3:
    // 1. The image has a relative path and an SVG extension.
    // Case 3 Expected Result:
    // Its URL is resolved against the resource location, independently of the image name.
    @Test
    fun given_case_3_when_getTrustMark_is_called_then_case_3_expected_result_is_returned() = runTest {
        // Given
        controller.result = Result.success(mockedTrustMark.copy(imageUrl = "../assets/logo.svg"))

        // When
        val result = interactor().getTrustMark()

        // Then
        assertEquals(LoadTrustMarkPartialState.Success(mockedTrustMarkUi), result)
    }

    // Case 4:
    // 1. The image URL is blank, malformed or uses a non-web scheme.
    // Case 4 Expected Result:
    // Only the image is unavailable.
    @Test
    fun given_case_4_when_getTrustMark_is_called_then_case_4_expected_result_is_returned() = runTest {
        // Given
        mockedInvalidImageUrls.forEach { imageUrl ->
            controller.result = Result.success(mockedTrustMark.copy(imageUrl = imageUrl))

            // When
            val result = interactor().getTrustMark()

            // Then
            assertEquals(
                LoadTrustMarkPartialState.Success(mockedTrustMarkUi.copy(imageUrl = null)),
                result,
                "imageUrl = '$imageUrl'",
            )
        }
    }

    // Case 5:
    // 1. Link targets are relative, malformed, non-web or include user information.
    // Case 5 Expected Result:
    // Invalid targets are omitted, retaining the usable content.
    @Test
    fun given_case_5_when_getTrustMark_is_called_then_case_5_expected_result_is_returned() = runTest {
        // Given
        mockedInvalidLinkUrls.forEach { url ->
            controller.result = Result.success(
                mockedTrustMark.copy(certifiedWalletsUrl = url, walletSolutionUrl = url)
            )

            // When
            val result = interactor().getTrustMark()

            // Then
            assertEquals(
                LoadTrustMarkPartialState.Success(
                    mockedTrustMarkUi.copy(certifiedWalletsUrl = null, walletSolutionUrl = null)
                ),
                result,
                "url = '$url'",
            )
        }
    }

    // Case 6:
    // 1. The controller reports a missing Trust Mark manager.
    // Case 6 Expected Result:
    // The Trust Mark load error is returned without completing the introduction.
    @Test
    fun given_case_6_when_getTrustMark_is_called_then_case_6_expected_result_is_returned() = runTest {
        // Given
        controller.result = Result.failure(IllegalStateException("Missing Trust Mark manager"))

        // When
        val result = interactor().getTrustMark()

        // Then
        assertEquals(LoadTrustMarkPartialState.Failure(mockedTrustMarkLoadError), result)
        assertTrue(introductionStore.writes.isEmpty())
    }

    // Case 7:
    // 1. Core reports a resource fetch or parsing failure.
    // Case 7 Expected Result:
    // The Trust Mark load error is returned without completing the introduction.
    @Test
    fun given_case_7_when_getTrustMark_is_called_then_case_7_expected_result_is_returned() = runTest {
        // Given
        controller.result = Result.failure(mockedExceptionWithMessage)

        // When
        val result = interactor().getTrustMark()

        // Then
        assertEquals(LoadTrustMarkPartialState.Failure(mockedTrustMarkLoadError), result)
        assertTrue(introductionStore.writes.isEmpty())
    }

    // Case 8:
    // 1. The controller throws before returning a result.
    // Case 8 Expected Result:
    // The interactor returns the Trust Mark load error at its asynchronous boundary.
    @Test
    fun given_case_8_when_getTrustMark_is_called_then_case_8_expected_result_is_returned() = runTest {
        // Given
        controller.thrown = mockedExceptionWithMessage

        // When
        val result = interactor().getTrustMark()

        // Then
        assertEquals(LoadTrustMarkPartialState.Failure(mockedTrustMarkLoadError), result)
    }

    // Case 9:
    // 1. A translation places the linked phrase first and includes literal markup characters.
    // Case 9 Expected Result:
    // The full text is preserved and only the named phrase is marked as actionable.
    @Test
    fun given_case_9_when_getTrustMark_is_called_then_case_9_expected_result_is_returned() = runTest {
        // Given
        controller.result = Result.success(mockedTrustMark)
        val translatedText = "Details <wallet> & privacy: learn more."
        strings.paragraph(
            textRes = Res.string.trust_mark_certification_information_description,
            linkRes = Res.string.trust_mark_certification_information_link,
            label = "Details <wallet>",
            text = translatedText,
        )

        // When
        val result = interactor().getTrustMark()

        // Then
        assertEquals(
            LoadTrustMarkPartialState.Success(
                mockedTrustMarkUi.copy(
                    certificationInformationDescription = TrustMarkParagraphUi(
                        text = translatedText,
                        linkRange = 0..15,
                    )
                )
            ),
            result,
        )
    }

    // Case 10:
    // 1. The linked label is absent from the translated paragraph or blank.
    // Case 10 Expected Result:
    // The paragraph remains plain text, without an invalid actionable range.
    @Test
    fun given_case_10_when_getTrustMark_is_called_then_case_10_expected_result_is_returned() = runTest {
        // Given
        controller.result = Result.success(mockedTrustMark)
        val translatedText = "Information is available online."
        listOf(mockedInformationLabel, " ").forEach { label ->
            strings.paragraph(
                textRes = Res.string.trust_mark_certification_information_description,
                linkRes = Res.string.trust_mark_certification_information_link,
                label = label,
                text = translatedText,
            )

            // When
            val result = interactor().getTrustMark()

            // Then
            assertEquals(
                LoadTrustMarkPartialState.Success(
                    mockedTrustMarkUi.copy(
                        certificationInformationDescription = TrustMarkParagraphUi(
                            text = translatedText,
                            linkRange = null,
                        )
                    )
                ),
                result,
                "label = '$label'",
            )
        }
    }

    // Case 11:
    // 1. Loading fails with an exception that has no message.
    // Case 11 Expected Result:
    // The same Trust Mark load error is returned without completing the introduction.
    @Test
    fun given_case_11_when_getTrustMark_is_called_then_case_11_expected_result_is_returned() = runTest {
        // Given
        controller.result = Result.failure(mockedExceptionWithNoMessage)

        // When
        val result = interactor().getTrustMark()

        // Then
        assertEquals(LoadTrustMarkPartialState.Failure(mockedTrustMarkLoadError), result)
        assertTrue(introductionStore.writes.isEmpty())
    }

    //endregion

    //region completeIntroduction

    // Case 1:
    // 1. Resource loading has failed.
    // Case 1 Expected Result:
    // Completion is still saved successfully.
    @Test
    fun given_case_1_when_completeIntroduction_is_called_then_case_1_expected_result_is_returned() = runTest {
        // Given
        controller.result = Result.failure(mockedExceptionWithMessage)
        val interactor = interactor()
        interactor.getTrustMark()

        // When
        val result = interactor.completeIntroduction()

        // Then
        assertEquals(CompleteTrustMarkIntroductionPartialState.Success, result)
        assertEquals(listOf(true), introductionStore.writes)
    }

    // Case 2:
    // 1. Saving the completion flag fails.
    // Case 2 Expected Result:
    // The exception's message is returned as a completion failure.
    @Test
    fun given_case_2_when_completeIntroduction_is_called_then_case_2_expected_result_is_returned() = runTest {
        // Given
        introductionStore.writeFailure = mockedExceptionWithMessage

        // When
        val result = interactor().completeIntroduction()

        // Then
        assertEquals(
            CompleteTrustMarkIntroductionPartialState.Failure(mockedExceptionWithMessage.message!!),
            result,
        )
    }

    // Case 3:
    // 1. Saving the completion flag fails with an exception that has no message.
    // Case 3 Expected Result:
    // The generic error message is returned as a completion failure.
    @Test
    fun given_case_3_when_completeIntroduction_is_called_then_case_3_expected_result_is_returned() = runTest {
        // Given
        introductionStore.writeFailure = mockedExceptionWithNoMessage

        // When
        val result = interactor().completeIntroduction()

        // Then
        assertEquals(
            CompleteTrustMarkIntroductionPartialState.Failure(mockedGenericErrorMessage),
            result,
        )
    }

    //endregion

    //region fakes and mocked objects

    private class FakeTrustMarkController : WalletCoreTrustMarkController {
        var result: Result<TrustMarkDomain> = Result.failure(IllegalStateException("not set"))
        var thrown: Throwable? = null

        override suspend fun getTrustMark(): Result<TrustMarkDomain> {
            thrown?.let { throw it }
            return result
        }
    }

    private class FakeIntroductionStore : TrustMarkIntroductionStore {
        val writes = mutableListOf<Boolean>()
        var writeFailure: Throwable? = null

        override suspend fun getTrustMarkIntroductionCompleted(): Boolean = writes.lastOrNull() ?: false

        override suspend fun setTrustMarkIntroductionCompleted(value: Boolean) {
            writeFailure?.let { throw it }
            writes += value
        }
    }

    /** Answers `get(textRes, label)` only for the label configured with it, as upstream's mocks did. */
    private class FakeTrustMarkStrings : StringCatalog {
        val values = mutableMapOf<StringResource, String>()
        private val paragraphs = mutableMapOf<Pair<StringResource, String>, String>()

        fun paragraph(textRes: StringResource, linkRes: StringResource, label: String, text: String) {
            values[linkRes] = label
            paragraphs[textRes to label] = text
        }

        override fun get(resource: StringResource): String = values.getValue(resource)

        override fun get(resource: StringResource, vararg args: Any): String =
            paragraphs.getValue(resource to args.single() as String)

        override suspend fun warm() = Unit
    }

    private val mockedExceptionWithMessage = RuntimeException("Exception to test interactor.")
    private val mockedExceptionWithNoMessage = RuntimeException()
    private val mockedGenericErrorMessage = "Something went wrong"
    private val mockedTrustMarkLoadError = "Unable to load Trust Mark information. Please try again."
    private val mockedEnglishText = "SampleText-for-Users"
    private val mockedTrustedListLabel = "Trusted wallets"
    private val mockedInformationLabel = "details"
    private val mockedCertificationDescription = TrustMarkParagraphUi(
        text = "Listed in Trusted wallets.",
        linkRange = 10..24,
    )
    private val mockedInformationDescription = TrustMarkParagraphUi(
        text = "Read details.",
        linkRange = 5..11,
    )
    private val mockedTrustMark = TrustMarkDomain(
        resourceUrl = "https://example.com/resources/TrustMarkResource.json",
        imageName = "trust-mark.png",
        imageUrl = "https://example.com/assets/logo.svg",
        localisedText = mockedEnglishText,
        certifiedWalletsUrl = "https://eidas.ec.europa.eu/efda/wallet/certified",
        walletSolutionUrl = "https://eidas.ec.europa.eu/efda/wallet/certified?id=WALLET_SOLUTION_ID",
    )
    private val mockedTrustMarkUi = TrustMarkUi(
        imageUrl = mockedTrustMark.imageUrl,
        text = mockedEnglishText,
        certifiedWalletsUrl = mockedTrustMark.certifiedWalletsUrl,
        walletSolutionUrl = mockedTrustMark.walletSolutionUrl,
        certificationDescription = mockedCertificationDescription,
        certificationInformationDescription = mockedInformationDescription,
    )
    private val mockedInvalidImageUrls = listOf(
        "", " ", "file:///logo.svg", "data:image/svg+xml,logo", "https://example.com/bad url.svg",
    )
    private val mockedInvalidLinkUrls = listOf(
        "/wallets", "javascript:alert(1)", "https://", "https://user@example.com/wallets",
    )

    //endregion
}
