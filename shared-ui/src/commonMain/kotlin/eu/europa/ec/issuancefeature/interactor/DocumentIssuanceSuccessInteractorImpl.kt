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

package eu.europa.ec.issuancefeature.interactor

import eu.europa.ec.businesslogic.extension.safeAsync
import eu.europa.ec.commonfeature.extension.toExpandableListItems
import eu.europa.ec.dashboardfeature.interactor.DocumentDetailsPlatformBridge
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.StringCatalog
import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.shared.resources.asUiText
import eu.europa.ec.shared.resources.document_success_collapsed_supporting_text
import eu.europa.ec.shared.resources.generic_error_message
import eu.europa.ec.shared.resources.issuance_success_header_description
import eu.europa.ec.shared.resources.issuance_success_header_description_when_error
import eu.europa.ec.shared.resources.issuance_success_header_issuer_default_name
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.ListItemDataUi
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import eu.europa.ec.uilogic.component.ListItemSupportingContentDataUi
import eu.europa.ec.uilogic.component.ListItemTrailingContentDataUi
import eu.europa.ec.uilogic.component.RelyingPartyDataUi
import eu.europa.ec.uilogic.component.content.ContentHeaderConfig
import eu.europa.ec.uilogic.component.wrap.ExpandableListItemUi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.coroutines.cancellation.CancellationException

/**
 * The freshly issued documents, as the success screen lists them.
 *
 * Shared rather than per-platform because the platform-specific half — reading a document and building
 * its claim tree, which on Android means wallet-core and on iOS means multipaz — is already behind
 * [DocumentDetailsPlatformBridge.getDocumentDetails], and that is the entirety of what this needed from
 * the platform. Reusing the details bridge rather than adding a near-identical issuance one is
 * deliberate: it is the same question ("what does this document contain?"), and duplicating it would
 * mean two implementations of the same work on both platforms. The details-only fields
 * ([eu.europa.ec.dashboardfeature.interactor.PlatformDocumentDetails.isExpired] and the credential
 * counter) simply go unread here.
 */
class DocumentIssuanceSuccessInteractorImpl(
    private val strings: StringCatalog,
    private val platform: DocumentDetailsPlatformBridge,
) : DocumentIssuanceSuccessInteractor {

    private val genericErrorMsg
        get() = strings[Res.string.generic_error_message]

    override fun getUiItems(
        documentIds: List<String>,
    ): Flow<DocumentIssuanceSuccessInteractorGetUiItemsPartialState> = flow {
        val documentsUi = mutableListOf<ExpandableListItemUi.NestedListItem>()

        var issuerName = strings[Res.string.issuance_success_header_issuer_default_name]
        var issuerLogo: String? = null
        val locale = platform.localeTag()

        documentIds.forEach { documentId ->
            val details = try {
                platform.getDocumentDetails(documentId = documentId, locale = locale)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A document that cannot be read is left out rather than failing the whole screen: the
                // issuer has already issued the others, and the header copy accounts for an empty list.
                null
            } ?: return@forEach

            // Last one wins, as before. Every document in one issuance comes from a single issuer, so
            // this only looks like a choice.
            details.issuerName?.let { issuerName = it }
            details.issuerLogoUri?.let { issuerLogo = it }

            documentsUi.add(
                ExpandableListItemUi.NestedListItem(
                    header = ListItemDataUi(
                        itemId = documentId,
                        mainContentData = ListItemMainContentDataUi.Text(
                            text = details.documentDetailsDomain.docName
                        ),
                        supportingContentData = ListItemSupportingContentDataUi.Text(
                            text = strings[Res.string.document_success_collapsed_supporting_text],
                        ),
                        trailingContentData = ListItemTrailingContentDataUi.Icon(
                            iconData = AppIcons.KeyboardArrowDown
                        )
                    ),
                    nestedItems = details.documentDetailsDomain.documentClaims.map { claim ->
                        claim.toExpandableListItems(docId = documentId, queryId = null)
                    },
                    isExpanded = false,
                )
            )
        }

        emit(
            DocumentIssuanceSuccessInteractorGetUiItemsPartialState.Success(
                documentsUi = documentsUi,
                headerConfig = ContentHeaderConfig(
                    description = UiText.Resource(
                        if (documentsUi.isEmpty()) {
                            Res.string.issuance_success_header_description_when_error
                        } else {
                            Res.string.issuance_success_header_description
                        }
                    ),
                    relyingPartyData = RelyingPartyDataUi(
                        logo = issuerLogo,
                        name = issuerName.asUiText(),
                        // Offer verification is not established at issuance time, as on Android.
                        isVerified = false,
                    )
                ),
            )
        )
    }.safeAsync {
        DocumentIssuanceSuccessInteractorGetUiItemsPartialState.Failed(
            errorMessage = it.message ?: genericErrorMsg
        )
    }
}
