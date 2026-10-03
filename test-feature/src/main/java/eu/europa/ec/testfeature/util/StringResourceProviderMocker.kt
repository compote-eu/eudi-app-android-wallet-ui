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
import eu.europa.ec.shared.resources.request_collapsed_supporting_text
import eu.europa.ec.shared.resources.request_gender_female
import eu.europa.ec.shared.resources.request_gender_male
import eu.europa.ec.shared.resources.request_gender_not_applicable
import eu.europa.ec.shared.resources.request_gender_not_known
import org.jetbrains.compose.resources.StringResource
import org.mockito.kotlin.whenever
import eu.europa.ec.shared.resources.request_transaction_aes
import eu.europa.ec.shared.resources.request_transaction_aeseal
import eu.europa.ec.shared.resources.request_transaction_aesealqc
import eu.europa.ec.shared.resources.request_transaction_aesqc
import eu.europa.ec.shared.resources.request_transaction_checksum_algorithm
import eu.europa.ec.shared.resources.request_transaction_conformance_level
import eu.europa.ec.shared.resources.request_transaction_details_title
import eu.europa.ec.shared.resources.request_transaction_document
import eu.europa.ec.shared.resources.request_transaction_document_hash
import eu.europa.ec.shared.resources.request_transaction_document_hash_algorithm
import eu.europa.ec.shared.resources.request_transaction_document_location
import eu.europa.ec.shared.resources.request_transaction_document_numbered
import eu.europa.ec.shared.resources.request_transaction_dtbsr_algorithm
import eu.europa.ec.shared.resources.request_transaction_dtbsr_hash
import eu.europa.ec.shared.resources.request_transaction_expected_checksum
import eu.europa.ec.shared.resources.request_transaction_hash_representation
import eu.europa.ec.shared.resources.request_transaction_numbered
import eu.europa.ec.shared.resources.request_transaction_open_document
import eu.europa.ec.shared.resources.request_transaction_otp
import eu.europa.ec.shared.resources.request_transaction_qes
import eu.europa.ec.shared.resources.request_transaction_qeseal
import eu.europa.ec.shared.resources.request_transaction_requested_credentials
import eu.europa.ec.shared.resources.request_transaction_response_uri
import eu.europa.ec.shared.resources.request_transaction_sdr_algorithm
import eu.europa.ec.shared.resources.request_transaction_sdr_hash
import eu.europa.ec.shared.resources.request_transaction_section_title
import eu.europa.ec.shared.resources.request_transaction_signature_count
import eu.europa.ec.shared.resources.request_transaction_signature_format
import eu.europa.ec.shared.resources.request_transaction_signature_type
import eu.europa.ec.shared.resources.request_transaction_signed_attributes
import eu.europa.ec.shared.resources.request_transaction_signing_credential_id
import eu.europa.ec.shared.resources.request_transaction_sodr_algorithm
import eu.europa.ec.shared.resources.request_transaction_sodr_hash
import eu.europa.ec.shared.resources.request_transaction_trust_framework
import eu.europa.ec.shared.resources.request_transaction_trust_framework_value
import eu.europa.ec.shared.resources.request_transaction_type
import eu.europa.ec.shared.resources.request_transaction_unavailable
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import eu.europa.ec.shared.resources.StringCatalog

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

    /** Upstream 2428c55d's transaction-data labels, shared by the provider and catalog mocks below. */
    private val mockedTransactionDataStrings: List<Pair<StringResource, String>> = listOf(
        Res.string.request_transaction_section_title to "Data to be signed",
        Res.string.request_transaction_details_title to "Signature details",
        Res.string.request_transaction_trust_framework to "Trust framework",
        Res.string.request_transaction_trust_framework_value to "eIDAS",
        Res.string.request_transaction_type to "Transaction type",
        Res.string.request_transaction_signature_type to "Signature type",
        Res.string.request_transaction_requested_credentials to "Requested credentials",
        Res.string.request_transaction_signing_credential_id to "Signing credential ID",
        Res.string.request_transaction_signature_count to "Number of signatures",
        Res.string.request_transaction_document to "Document",
        Res.string.request_transaction_document_location to "Document location",
        Res.string.request_transaction_open_document to "Open document",
        Res.string.request_transaction_expected_checksum to "Expected document checksum",
        Res.string.request_transaction_checksum_algorithm to "Checksum algorithm",
        Res.string.request_transaction_hash_representation to "Hash representation",
        Res.string.request_transaction_dtbsr_hash to "DTBSR hash",
        Res.string.request_transaction_dtbsr_algorithm to "DTBSR hash algorithm",
        Res.string.request_transaction_sdr_hash to "SDR hash",
        Res.string.request_transaction_sdr_algorithm to "SDR hash algorithm",
        Res.string.request_transaction_sodr_hash to "SODR hash",
        Res.string.request_transaction_sodr_algorithm to "SODR hash algorithm",
        Res.string.request_transaction_document_hash to "Document hash",
        Res.string.request_transaction_document_hash_algorithm to "Document hash algorithm",
        Res.string.request_transaction_signature_format to "Signature format",
        Res.string.request_transaction_conformance_level to "Conformance level",
        Res.string.request_transaction_signed_attributes to "Signed attributes",
        Res.string.request_transaction_otp to "One-time password (OTP)",
        Res.string.request_transaction_response_uri to "Response URI",
        Res.string.request_transaction_unavailable to "Details for this transaction are unavailable.",
        Res.string.request_transaction_qes to "Qualified electronic signature (QES)",
        Res.string.request_transaction_qeseal to "Qualified electronic seal",
        Res.string.request_transaction_aes to "Advanced electronic signature",
        Res.string.request_transaction_aeseal to "Advanced electronic seal",
        Res.string.request_transaction_aesqc to "Advanced electronic signature with a qualified certificate",
        Res.string.request_transaction_aesealqc to "Advanced electronic seal with a qualified certificate",
    )

    fun mockTransactionDataStrings(resourceProvider: ResourceProvider) {
        mockResourceProviderStrings(
            resourceProvider = resourceProvider,
            pairs = mockedTransactionDataStrings,
        )
        whenever(resourceProvider.getString(Res.string.request_collapsed_supporting_text))
            .thenReturn(mockedRequestCollapsedSupportingText)
        whenever(resourceProvider.getString(eq(Res.string.request_transaction_numbered), any()))
            .thenAnswer { invocation -> "Transaction ${invocation.getArgument<Any>(1)}" }
        whenever(resourceProvider.getString(eq(Res.string.request_transaction_document_numbered), any()))
            .thenAnswer { invocation -> "Document ${invocation.getArgument<Any>(1)}" }
    }

    /** The same labels on a [StringCatalog] mock, for shared code that reads strings from one. */
    fun mockTransactionDataStrings(strings: StringCatalog) {
        mockedTransactionDataStrings.forEach { (resource, value) ->
            whenever(strings[resource]).thenReturn(value)
        }
        whenever(strings[Res.string.request_collapsed_supporting_text])
            .thenReturn(mockedRequestCollapsedSupportingText)
        whenever(strings.get(eq(Res.string.request_transaction_numbered), any()))
            .thenAnswer { invocation -> "Transaction ${invocation.getArgument<Any>(1)}" }
        whenever(strings.get(eq(Res.string.request_transaction_document_numbered), any()))
            .thenAnswer { invocation -> "Document ${invocation.getArgument<Any>(1)}" }
    }
}
