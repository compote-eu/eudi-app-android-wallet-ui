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

// The behaviours the issuance-success list had to keep when its interactor moved to commonMain. There
// was no test on the Android implementation, and three of these are exactly the parts that would have
// gone unnoticed if the port had got them wrong: a document the platform cannot read is *skipped*
// rather than failing the screen, the issuer display falls back to a default, and an empty list changes
// the header copy.
package eu.europa.ec.issuancefeature.interactor

import eu.europa.ec.corelogic.model.ClaimDomain
import eu.europa.ec.corelogic.model.ClaimPathDomain
import eu.europa.ec.corelogic.model.ClaimType
import eu.europa.ec.corelogic.model.DocumentIdentifier
import eu.europa.ec.dashboardfeature.interactor.DocumentDetailsInteractorDeleteDocumentPartialState
import eu.europa.ec.dashboardfeature.interactor.DocumentDetailsInteractorIssuancePartialState
import eu.europa.ec.dashboardfeature.interactor.DocumentDetailsPlatformBridge
import eu.europa.ec.dashboardfeature.interactor.PlatformDocumentDetails
import eu.europa.ec.dashboardfeature.ui.documents.detail.model.DocumentDetailsDomain
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.StringCatalog
import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.shared.resources.document_success_collapsed_supporting_text
import eu.europa.ec.shared.resources.generic_error_message
import eu.europa.ec.shared.resources.issuance_success_header_description
import eu.europa.ec.shared.resources.issuance_success_header_description_when_error
import eu.europa.ec.shared.resources.issuance_success_header_issuer_default_name
import eu.europa.ec.authenticationlogic.controller.authentication.DeviceAuthenticationResult
import eu.europa.ec.authenticationlogic.model.BiometricCrypto
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import eu.europa.ec.uilogic.component.wrap.ExpandableListItemUi
import eu.europa.ec.uilogic.component.ListItemSupportingContentDataUi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.StringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeStringCatalog : StringCatalog {
    override fun get(resource: StringResource): String = when (resource) {
        Res.string.issuance_success_header_issuer_default_name -> "Unknown issuer"
        Res.string.document_success_collapsed_supporting_text -> "VIEW DETAILS"
        Res.string.generic_error_message -> "Something went wrong"
        else -> "?"
    }

    override fun get(resource: StringResource, vararg args: Any): String = get(resource)
    override suspend fun warm() = Unit
}

/** Answers only [getDocumentDetails]; nothing else on the bridge is reachable from this interactor. */
private class FakeDetailsBridge(
    private val answers: Map<String, PlatformDocumentDetails?>,
    private val throwsFor: Set<String> = emptySet(),
    private val localeFailure: Throwable? = null,
) : DocumentDetailsPlatformBridge {

    var localeRequests: Int = 0
        private set

    override suspend fun getDocumentDetails(
        documentId: String,
        locale: String,
    ): PlatformDocumentDetails? {
        if (documentId in throwsFor) error("cannot read $documentId")
        return answers[documentId]
    }

    override fun localeTag(): String {
        localeRequests++
        localeFailure?.let { throw it }
        return "en"
    }

    // True, matching the stored preference's default on both platforms. This interactor does not read
    // the counter — these cases construct `credentialsInfo = null` regardless — so the value only has
    // to be the one that does not hide anything the tests do assert on.
    override suspend fun showBatchIssuanceCounter(): Boolean = true

    override fun deleteDocument(
        documentId: String,
    ): Flow<DocumentDetailsInteractorDeleteDocumentPartialState> = emptyFlow()

    override fun reIssueDocument(
        documentId: String,
        issuerId: String,
    ): Flow<DocumentDetailsInteractorIssuancePartialState> = emptyFlow()

    override fun handleUserAuth(
        context: PlatformContext,
        crypto: BiometricCrypto,
        notifyOnAuthenticationFailure: Boolean,
        resultHandler: DeviceAuthenticationResult,
    ) = Unit

    override fun resumeOpenId4VciWithAuthorization(uri: String) = Unit
}

private fun claim(name: String, value: String) = ClaimDomain.Primitive(
    key = name,
    displayTitle = name,
    path = ClaimPathDomain.ofPlainKeys(
        names = listOf(name),
        type = ClaimType.MsoMdoc(namespace = "eu.europa.ec.eudi.pid.1"),
    ),
    value = value,
    isRequired = false,
)

private fun details(
    documentId: String,
    name: String,
    issuerName: String? = "Digital Credentials Issuer",
    issuerLogoUri: String? = "https://issuer.test/logo.png",
    claims: List<ClaimDomain> = listOf(claim("given_name", "Tester")),
) = PlatformDocumentDetails(
    documentDetailsDomain = DocumentDetailsDomain(
        docName = name,
        docId = documentId,
        issuerId = "issuer",
        documentConfigId = "config",
        documentIdentifier = DocumentIdentifier.MdocPid,
        documentClaims = claims,
        documentIssuanceDate = "14 August 2026",
        documentExpirationDate = null,
    ),
    issuerName = issuerName,
    issuerLogoUri = issuerLogoUri,
    isExpired = false,
    credentialsInfo = null,
)

class DocumentIssuanceSuccessInteractorTest {

    private fun interactor(bridge: DocumentDetailsPlatformBridge) =
        DocumentIssuanceSuccessInteractorImpl(strings = FakeStringCatalog(), platform = bridge)

    @Test
    fun each_issued_document_becomes_a_collapsed_item_carrying_its_claims() = runTest {
        val bridge = FakeDetailsBridge(
            answers = mapOf(
                "doc-1" to details("doc-1", "PID (MSO MDoc)"),
                "doc-2" to details(
                    "doc-2",
                    "PID (SD-JWT VC Compact)",
                    claims = listOf(claim("given_name", "Tester"), claim("family_name", "Kotlin")),
                ),
            ),
        )

        val state = interactor(bridge).getUiItems(listOf("doc-1", "doc-2")).first()

        val success =
            assertIs<DocumentIssuanceSuccessInteractorGetUiItemsPartialState.Success>(state)
        assertEquals(2, success.documentsUi.size)
        val first = success.documentsUi.first()
        assertEquals("doc-1", first.header.itemId)
        assertEquals(
            "PID (MSO MDoc)",
            assertIs<ListItemMainContentDataUi.Text>(first.header.mainContentData).text,
        )
        assertEquals(
            ListItemSupportingContentDataUi.Text(text = "VIEW DETAILS"),
            first.header.supportingContentData
        )
        // Collapsed: the screen expands on tap, and an already-expanded list would hide the second
        // document below the fold.
        assertFalse(first.isExpanded)
        assertEquals(1, first.nestedItems.size)
        assertEquals(2, success.documentsUi[1].nestedItems.size)
        assertIs<ExpandableListItemUi.SingleListItem>(first.nestedItems.single())
    }

    @Test
    fun the_issuer_display_comes_from_the_documents() = runTest {
        val bridge = FakeDetailsBridge(answers = mapOf("doc-1" to details("doc-1", "PID")))

        val state = interactor(bridge).getUiItems(listOf("doc-1")).first()

        val header =
            assertIs<DocumentIssuanceSuccessInteractorGetUiItemsPartialState.Success>(state).headerConfig
        assertEquals("Digital Credentials Issuer", assertIs<UiText.Raw>(header.relyingPartyData?.name).value)
        assertEquals("https://issuer.test/logo.png", header.relyingPartyData?.logo)
        // Nothing verifies an issuer at issuance time, so claiming verification would be a lie.
        assertFalse(header.relyingPartyData?.isVerified == true)
    }

    @Test
    fun an_issuer_that_publishes_no_display_falls_back_to_the_default_name() = runTest {
        val bridge = FakeDetailsBridge(
            answers = mapOf(
                "doc-1" to details("doc-1", "PID", issuerName = null, issuerLogoUri = null)
            ),
        )

        val state = interactor(bridge).getUiItems(listOf("doc-1")).first()

        val header =
            assertIs<DocumentIssuanceSuccessInteractorGetUiItemsPartialState.Success>(state).headerConfig
        assertEquals("Unknown issuer", assertIs<UiText.Raw>(header.relyingPartyData?.name).value)
        assertNull(header.relyingPartyData?.logo)
    }

    @Test
    fun a_document_the_platform_cannot_read_is_left_out_rather_than_failing_the_screen() = runTest {
        val bridge = FakeDetailsBridge(
            answers = mapOf("doc-1" to null, "doc-2" to details("doc-2", "PID")),
            throwsFor = setOf("doc-3"),
        )

        val state = interactor(bridge).getUiItems(listOf("doc-1", "doc-2", "doc-3")).first()

        // The issuer has already issued the readable ones; reporting a failure would hide them.
        val success =
            assertIs<DocumentIssuanceSuccessInteractorGetUiItemsPartialState.Success>(state)
        assertEquals(listOf("doc-2"), success.documentsUi.map { it.header.itemId })
    }

    @Test
    fun a_run_that_lists_nothing_says_so_in_the_header() = runTest {
        val bridge = FakeDetailsBridge(answers = mapOf("doc-1" to null))

        val state = interactor(bridge).getUiItems(listOf("doc-1")).first()

        val success =
            assertIs<DocumentIssuanceSuccessInteractorGetUiItemsPartialState.Success>(state)
        assertTrue(success.documentsUi.isEmpty())
        assertEquals(
            UiText.Resource(Res.string.issuance_success_header_description_when_error),
            success.headerConfig.description,
        )
    }

    @Test
    fun a_run_that_lists_documents_uses_the_success_header() = runTest {
        val bridge = FakeDetailsBridge(answers = mapOf("doc-1" to details("doc-1", "PID")))

        val state = interactor(bridge).getUiItems(listOf("doc-1")).first()

        assertEquals(
            UiText.Resource(Res.string.issuance_success_header_description),
            assertIs<DocumentIssuanceSuccessInteractorGetUiItemsPartialState.Success>(state)
                .headerConfig.description,
        )
    }

    @Test
    fun a_failure_before_any_document_is_read_reports_its_message() = runTest {
        val bridge = FakeDetailsBridge(
            answers = emptyMap(),
            localeFailure = IllegalStateException("no wallet"),
        )

        val state = interactor(bridge).getUiItems(listOf("doc-1")).first()

        // Distinct from "no documents could be read": nothing was even attempted, so the screen says so
        // rather than showing an empty success.
        assertEquals(
            "no wallet",
            assertIs<DocumentIssuanceSuccessInteractorGetUiItemsPartialState.Failed>(state).errorMessage,
        )
    }

    @Test
    fun a_failure_with_no_message_falls_back_to_the_generic_error() = runTest {
        val bridge = FakeDetailsBridge(answers = emptyMap(), localeFailure = IllegalStateException())

        val state = interactor(bridge).getUiItems(listOf("doc-1")).first()

        assertEquals(
            "Something went wrong",
            assertIs<DocumentIssuanceSuccessInteractorGetUiItemsPartialState.Failed>(state).errorMessage,
        )
    }

    @Test
    fun the_locale_is_asked_for_once_per_run_not_once_per_document() = runTest {
        val bridge = FakeDetailsBridge(
            answers = mapOf(
                "doc-1" to details("doc-1", "PID"),
                "doc-2" to details("doc-2", "mDL"),
            ),
        )

        interactor(bridge).getUiItems(listOf("doc-1", "doc-2")).first()

        assertEquals(1, bridge.localeRequests)
    }
}
