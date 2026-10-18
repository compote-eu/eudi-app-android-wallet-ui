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

package eu.europa.ec.corelogic.extension

import eu.europa.ec.eudi.wallet.document.IssuedDocument
import eu.europa.ec.eudi.wallet.document.metadata.IssuerMetadata
import eu.europa.ec.testlogic.extension.runTest
import eu.europa.ec.testlogic.rule.CoroutineTestRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.multipaz.credential.SecureAreaBoundCredential
import java.util.Locale
import kotlin.time.Instant

class TestDocumentExtensions {

    @get:Rule
    val coroutineRule = CoroutineTestRule()

    //region getExpiryDate

    @Test
    fun `Given several credentials, When getExpiryDate is called, Then the latest validUntil is returned`() {
        coroutineRule.runTest {
            // Given the batch's credentials expire on different dates.
            val earlier = Instant.parse("2030-01-01T00:00:00Z")
            val latest = Instant.parse("2035-01-01T00:00:00Z")
            val document = mockDocumentWithCredentials(earlier, latest)

            // When
            val result = document.getExpiryDate()

            // Then the document's expiry is the latest credential's validUntil.
            assertEquals(latest, result)
        }
    }

    @Test
    fun `Given no credentials, When getExpiryDate is called, Then null is returned`() {
        coroutineRule.runTest {
            // Given
            val document = mockDocumentWithCredentials()

            // When / Then
            assertNull(document.getExpiryDate())
        }
    }

    //endregion

    //region isExpired

    @Test
    fun `Given the latest credential is in the past, When isExpired is called, Then true is returned`() {
        coroutineRule.runTest {
            // Given
            val document = mockDocumentWithCredentials(Instant.parse("2020-01-01T00:00:00Z"))

            // When / Then
            assertTrue(document.isExpired())
        }
    }

    @Test
    fun `Given the latest credential is in the future, When isExpired is called, Then false is returned`() {
        coroutineRule.runTest {
            // Given
            val document = mockDocumentWithCredentials(Instant.parse("2100-01-01T00:00:00Z"))

            // When / Then
            assertFalse(document.isExpired())
        }
    }

    @Test
    fun `Given no credentials, When isExpired is called, Then false is returned`() {
        coroutineRule.runTest {
            // Given
            val document = mockDocumentWithCredentials()

            // When / Then
            assertFalse(document.isExpired())
        }
    }

    //endregion

    //region localizedName

    @Test
    fun `Given the issuer names the document in the user's language, When localizedName is called, Then that name is returned`() {
        val document = mockDocumentNamed(
            name = "PID",
            display = listOf("PID" to "en", "Osobný doklad" to "sk"),
        )

        assertEquals("Osobný doklad", document.localizedName(Locale.forLanguageTag("sk-SK")))
    }

    @Test
    fun `Given no name in the user's language, When localizedName is called, Then the first published name is returned`() {
        val document = mockDocumentNamed(
            name = "PID",
            display = listOf("PID" to "en", "Osobný doklad" to "sk"),
        )

        assertEquals("PID", document.localizedName(Locale.forLanguageTag("de-DE")))
    }

    @Test
    fun `Given no issuer metadata, When localizedName is called, Then the document's name is returned`() {
        val document = mock<IssuedDocument>()
        whenever(document.name).thenReturn("PID")
        whenever(document.issuerMetadata).thenReturn(null)

        assertEquals("PID", document.localizedName(Locale.forLanguageTag("sk-SK")))
    }

    @Test
    fun `Given issuer metadata without names, When localizedName is called, Then the document's name is returned`() {
        val document = mockDocumentNamed(name = "PID", display = emptyList())

        assertEquals("PID", document.localizedName(Locale.forLanguageTag("sk-SK")))
    }

    //endregion

    //region helper functions

    private fun mockDocumentNamed(
        name: String,
        display: List<Pair<String, String>>,
    ): IssuedDocument {
        val document = mock<IssuedDocument>()
        whenever(document.name).thenReturn(name)
        whenever(document.issuerMetadata).thenReturn(
            IssuerMetadata(
                documentConfigurationIdentifier = "eu.europa.ec.eudi.pid_mdoc",
                display = display.map { (displayName, language) ->
                    IssuerMetadata.Display(name = displayName, locale = Locale.forLanguageTag(language))
                },
                claims = emptyList(),
                credentialIssuerIdentifier = "https://issuer.example",
                issuerDisplay = emptyList(),
            )
        )
        return document
    }

    private suspend fun mockDocumentWithCredentials(vararg validUntil: Instant): IssuedDocument {
        val credentials = validUntil.map { instant ->
            val credential = mock<SecureAreaBoundCredential>()
            whenever(credential.validUntil).thenReturn(instant)
            credential
        }
        val document = mock<IssuedDocument>()
        whenever(document.getCredentials()).thenReturn(credentials)
        return document
    }

    //endregion
}