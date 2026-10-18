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

// Which document links the shared transaction-data transformer makes actionable, on both platforms.
// Upstream's `TestTransactionDataTransformer` runs on the JVM, where its rule reads URLs with java.net.URI;
// here it reads them with `ParsedUri`, so upstream's own Case 9 locations are repeated to pin the rule on
// iOS as well. Labels are the resources' keys, so nothing depends on a translation.
package eu.europa.ec.commonfeature.ui.request.transformer

import eu.europa.ec.corelogic.model.PresentationMatchDomain
import eu.europa.ec.corelogic.model.PresentationTransactionDataDomain
import eu.europa.ec.corelogic.model.QesSignatureRequestDomain
import eu.europa.ec.shared.resources.StringCatalog
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import org.jetbrains.compose.resources.StringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransactionDataTransformerLinkTest {

    private val transformer = TransactionDataTransformer(strings = KeyStrings)

    private val valid = listOf(
        "https://documents.example.org/contract.pdf?token=a%2Fb#page=1",
        "http://example.org:8080/file",
        "HTTPS://example.org/file#page=2",
    )
    private val invalid = listOf(
        "file:///document.pdf", "content://documents/1", "javascript:alert(1)", "/relative.pdf",
        "https:///missing-host", "https://user:password@example.org/file", "https://example.org/has space",
        "https://example.org/%zz", "https://example.org:99999/file", "https://example.org:0/file",
        "https://example.org/\nfile", "",
    )

    private fun request(locations: List<String>) = PresentationTransactionDataDomain.Qes(
        displayName = "QES request",
        credentialIds = emptyList(),
        signatureRequests = locations.map { href ->
            QesSignatureRequestDomain(
                label = "Inline.pdf",
                signatureQualifier = "eu_eidas_qes",
                responseUri = "https://signer.example.org/response",
                signatureFormat = null,
                conformanceLevel = null,
                signedProperties = null,
                href = href,
                checksum = null,
                oneTimePassword = null,
            )
        },
    )

    @Test
    fun only_http_and_https_locations_with_a_host_and_no_credentials_can_be_opened() {
        val section = transformer.transformToUi(
            matches = listOf(match(transactionData = listOf(request(valid + invalid)))),
            sectionId = "transaction-data:request:0",
        )!!

        assertEquals(valid, section.documentUrlsByItemId.values.toList())
        assertEquals(
            valid.size,
            section.details.nestedItems.count { item -> item.header.text() == "request_transaction_open_document" },
        )
        assertEquals(
            valid + invalid,
            section.details.nestedItems
                .filter { item -> item.header.overlineText == "request_transaction_document_location" }
                .map { item -> item.header.text() },
        )
    }

    @Test
    fun a_recorded_request_shows_its_locations_without_opening_them() {
        val rows = transformer.transformRecordedToUi(
            transactions = listOf(request(valid)),
            sectionId = "transaction-data",
        )

        assertTrue(rows.none { item -> item.header.text() == "request_transaction_open_document" })
        assertEquals(
            valid,
            rows.filter { item -> item.header.overlineText == "request_transaction_document_location" }
                .map { item -> item.header.text() },
        )
    }

    private fun match(transactionData: List<PresentationTransactionDataDomain>) = PresentationMatchDomain(
        documentId = "pid",
        credentialId = "pid-cred",
        queryId = "query_0",
        requestedClaims = emptyList(),
        transactionData = transactionData,
    )

    private fun eu.europa.ec.uilogic.component.ListItemDataUi.text(): String =
        (mainContentData as ListItemMainContentDataUi.Text).text

    /** Every label is its resource key; a formatted one is the key and its arguments. */
    private object KeyStrings : StringCatalog {
        override fun get(resource: StringResource): String = resource.key
        override fun get(resource: StringResource, vararg args: Any): String =
            resource.key + args.joinToString(prefix = ":", separator = ",")
        override suspend fun warm() = Unit
    }
}
