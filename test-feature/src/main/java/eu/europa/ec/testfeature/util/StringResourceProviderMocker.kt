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

package eu.europa.ec.testfeature.util

import androidx.annotation.VisibleForTesting
import eu.europa.ec.resourceslogic.provider.ResourceProvider
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.document_details_boolean_item_false_readable_value
import eu.europa.ec.shared.resources.document_details_boolean_item_true_readable_value
import eu.europa.ec.shared.resources.document_details_document_credentials_info_text
import eu.europa.ec.shared.resources.document_success_collapsed_supporting_text
import eu.europa.ec.shared.resources.issuance_success_header_issuer_default_name
import eu.europa.ec.shared.resources.request_collapsed_supporting_text
import eu.europa.ec.shared.resources.request_gender_female
import eu.europa.ec.shared.resources.request_gender_male
import eu.europa.ec.shared.resources.request_gender_not_applicable
import eu.europa.ec.shared.resources.request_gender_not_known
import org.jetbrains.compose.resources.StringResource
import org.mockito.kotlin.whenever

@VisibleForTesting(otherwise = VisibleForTesting.Companion.NONE)
object StringResourceProviderMocker {

    /**
     * Mocks ResourceProvider.getString(...) for each (resource → returnValue) pair.
     */
    fun mockResourceProviderStrings(
        resourceProvider: ResourceProvider,
        pairs: List<Pair<StringResource, String>>,
    ) {
        pairs.forEach { (resource, returnValue) ->
            whenever(resourceProvider.getString(resource)).thenReturn(returnValue)
        }
    }

    fun mockGetDocumentDetailsStrings(
        resourceProvider: ResourceProvider,
        availableCredentials: Int,
        totalCredentials: Int,
    ) {
        mockCreateDocumentCredentialsInfoStrings(
            resourceProvider = resourceProvider,
            availableCredentials = availableCredentials,
            totalCredentials = totalCredentials
        )

        mockTransformToDocumentDetailsDomainStrings(resourceProvider)
    }

    fun mockCreateDocumentCredentialsInfoStrings(
        resourceProvider: ResourceProvider,
        availableCredentials: Int,
        totalCredentials: Int,
    ) {
        whenever(
            resourceProvider.getString(
                Res.string.document_details_document_credentials_info_text,
                availableCredentials,
                totalCredentials
            )
        ).thenReturn("$availableCredentials/$totalCredentials instances remaining")
    }

    fun mockTransformToDocumentDetailsDomainStrings(resourceProvider: ResourceProvider) {
        mockCreateKeyValueStrings(resourceProvider)
    }

    fun mockCreateKeyValueStrings(resourceProvider: ResourceProvider) {
        val mockedStrings = listOf(
            Res.string.document_details_boolean_item_true_readable_value to "yes",
            Res.string.document_details_boolean_item_false_readable_value to "no",
        )

        mockResourceProviderStrings(resourceProvider, mockedStrings)
        mockGetGenderValueStrings(resourceProvider)
    }

    fun mockGetGenderValueStrings(resourceProvider: ResourceProvider) {
        val mockedStrings = listOf(
            Res.string.request_gender_male to "Male",
            Res.string.request_gender_female to "Female",
            Res.string.request_gender_not_known to "Not known",
            Res.string.request_gender_not_applicable to "Not applicable",
        )

        mockResourceProviderStrings(resourceProvider, mockedStrings)
    }

    fun mockTransformToUiItemsStrings(
        resourceProvider: ResourceProvider,
    ) {
        mockCreateKeyValueStrings(resourceProvider)

        // The document header's collapsed supporting text. It is stubbed here rather than per-suite
        // because ListItemSupportingContentDataUi.Text takes a non-null String, so an unstubbed mock
        // returning null now fails the construction instead of quietly producing a null field.
        whenever(resourceProvider.getString(Res.string.request_collapsed_supporting_text))
            .thenReturn(mockedRequestCollapsedSupportingText)

        whenever(resourceProvider.getLocale())
            .thenReturn(mockedDefaultLocale)
    }

    fun mockIssuerName(
        resourceProvider: ResourceProvider,
        name: String
    ) {
        whenever(resourceProvider.getString(Res.string.issuance_success_header_issuer_default_name))
            .thenReturn(name)
    }

    fun mockGetUiItemsStrings(
        resourceProvider: ResourceProvider,
        supportingText: String,
    ) {
        whenever(resourceProvider.getString(Res.string.document_success_collapsed_supporting_text))
            .thenReturn(supportingText)
    }
}