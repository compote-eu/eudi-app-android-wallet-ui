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

package eu.europa.ec.corelogic.wallet

import eu.europa.ec.corelogic.controller.WalletCoreDocumentsController
import eu.europa.ec.eudi.wallet.document.IssuedDocument
import eu.europa.ec.eudi.wallet.document.UnsignedDocument
import eu.europa.ec.eudi.wallet.document.format.DocumentFormat
import eu.europa.ec.eudi.wallet.document.format.MsoMdocFormat
import eu.europa.ec.eudi.wallet.document.format.SdJwtVcFormat
import eu.europa.ec.eudi.wallet.document.metadata.IssuerMetadata
import eu.europa.ec.shared.wallet.WalletDocumentIssuanceState
import eu.europa.ec.testlogic.extension.runTest
import eu.europa.ec.testlogic.rule.CoroutineTestRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.multipaz.credential.SecureAreaBoundCredential
import java.net.URI
import java.util.Locale
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/**
 * The wallet-core → [eu.europa.ec.shared.wallet.WalletDocument] mapping is the whole point of the
 * WalletEngine seam: it is the only place in the app that still names
 * `eu.europa.ec.eudi.wallet.document.*` for the document list. These tests cover it directly, which
 * is where that coverage belongs now that `DocumentsInteractor` consumes the mapped model instead.
 */
class TestWalletEngineImpl {

    @get:Rule
    val coroutineRule = CoroutineTestRule()

    @Mock
    lateinit var documentsController: WalletCoreDocumentsController

    private lateinit var closeable: AutoCloseable
    private lateinit var engine: WalletEngineImpl

    @Before
    fun setUp() {
        closeable = MockitoAnnotations.openMocks(this)
        engine = WalletEngineImpl(documentsController)
    }

    @After
    fun after() {
        closeable.close()
    }

    //region getAllDocumentsWithDetails

    @Test
    fun `Given an issued mdoc document, When getAllDocumentsWithDetails is called, Then every list field is mapped`() {
        coroutineRule.runTest {
            // Given
            val issuedAt = Instant.parse("2024-03-04T10:00:00Z")
            val expiry = Instant.parse("2030-01-01T00:00:00Z")
            val document = mockIssuedDocument(
                id = "pid-1",
                name = "PID",
                format = MsoMdocFormat(docType = "eu.europa.ec.eudi.pid.1"),
                issuedAt = issuedAt,
                credentialValidUntil = listOf(Instant.parse("2029-01-01T00:00:00Z"), expiry),
                credentialsCount = 3,
                initialCredentialsCount = 5,
                issuerDisplay = listOf(
                    IssuerMetadata.IssuerDisplay(
                        name = "Test Issuer",
                        locale = Locale.ENGLISH,
                        logo = IssuerMetadata.Logo(uri = URI.create("https://issuer.test/logo.png")),
                    )
                ),
            )
            whenever(documentsController.getAllDocuments()).thenReturn(listOf(document))
            whenever(documentsController.isDocumentRevoked("pid-1")).thenReturn(false)
            whenever(documentsController.isDocumentLowOnCredentials(document)).thenReturn(true)

            // When
            val result = engine.getAllDocumentsWithDetails(locale = "en")

            // Then
            val mapped = result.single()
            assertEquals("pid-1", mapped.id)
            assertEquals("PID", mapped.name)
            assertEquals("eu.europa.ec.eudi.pid.1", mapped.formatType)
            assertEquals(WalletDocumentIssuanceState.Issued, mapped.issuanceState)
            assertEquals(issuedAt, mapped.issuedAt)
            // The latest credential's validUntil, not the first.
            assertEquals(expiry, mapped.expiresAt)
            assertFalse(mapped.isExpired)
            assertFalse(mapped.isRevoked)
            assertEquals(3, mapped.credentialsCount)
            assertEquals(5, mapped.initialCredentialsCount)
            assertTrue(mapped.isLowOnCredentials)
            assertEquals("Test Issuer", mapped.issuerName)
            assertEquals("https://issuer.test/logo.png", mapped.issuerLogoUri)
        }
    }

    @Test
    fun `Given an SD-JWT VC document, When getAllDocumentsWithDetails is called, Then formatType is the vct`() {
        coroutineRule.runTest {
            // Given
            val document = mockIssuedDocument(
                id = "sdjwt-1",
                format = SdJwtVcFormat(vct = "urn:eudi:pid:1"),
            )
            whenever(documentsController.getAllDocuments()).thenReturn(listOf(document))
            whenever(documentsController.isDocumentRevoked("sdjwt-1")).thenReturn(false)
            whenever(documentsController.isDocumentLowOnCredentials(document)).thenReturn(false)

            // When
            val result = engine.getAllDocumentsWithDetails(locale = "en")

            // Then
            assertEquals("urn:eudi:pid:1", result.single().formatType)
        }
    }

    @Test
    fun `Given a document whose credentials have all expired, When mapped, Then isExpired is true and expiresAt survives`() {
        coroutineRule.runTest {
            // Given — an expiry in the past. It must still be reported, since that is what lets the
            // UI say "expired on <date>" rather than showing nothing.
            val pastExpiry = Instant.parse("2020-01-01T00:00:00Z")
            val document = mockIssuedDocument(
                id = "expired-1",
                credentialValidUntil = listOf(pastExpiry),
            )
            whenever(documentsController.getAllDocuments()).thenReturn(listOf(document))
            whenever(documentsController.isDocumentRevoked("expired-1")).thenReturn(false)
            whenever(documentsController.isDocumentLowOnCredentials(document)).thenReturn(false)

            // When
            val mapped = engine.getAllDocumentsWithDetails(locale = "en").single()

            // Then
            assertTrue(mapped.isExpired)
            assertEquals(pastExpiry, mapped.expiresAt)
        }
    }

    @Test
    fun `Given a document with no credentials left, When mapped, Then expiresAt is null and isExpired is false`() {
        coroutineRule.runTest {
            // Given an exhausted once-only batch: absence of credentials is not expiry.
            val document = mockIssuedDocument(id = "exhausted-1", credentialValidUntil = emptyList())
            whenever(documentsController.getAllDocuments()).thenReturn(listOf(document))
            whenever(documentsController.isDocumentRevoked("exhausted-1")).thenReturn(false)
            whenever(documentsController.isDocumentLowOnCredentials(document)).thenReturn(false)

            // When
            val mapped = engine.getAllDocumentsWithDetails(locale = "en").single()

            // Then
            assertNull(mapped.expiresAt)
            assertFalse(mapped.isExpired)
        }
    }

    @Test
    fun `Given a revoked document, When mapped, Then isRevoked is true`() {
        coroutineRule.runTest {
            // Given
            val document = mockIssuedDocument(id = "revoked-1")
            whenever(documentsController.getAllDocuments()).thenReturn(listOf(document))
            whenever(documentsController.isDocumentRevoked("revoked-1")).thenReturn(true)
            whenever(documentsController.isDocumentLowOnCredentials(document)).thenReturn(false)

            // When / Then
            assertTrue(engine.getAllDocumentsWithDetails(locale = "en").single().isRevoked)
        }
    }

    @Test
    fun `Given an unsigned document, When mapped, Then it is Pending with no credential or validity data`() {
        coroutineRule.runTest {
            // Given — DeferredDocument extends UnsignedDocument, so this branch covers both. None of
            // the credential/validity fields are knowable yet, so they must stay absent rather than
            // be reported as a meaningful zero.
            val document = mock<UnsignedDocument>()
            whenever(document.id).thenReturn("unsigned-1")
            whenever(document.name).thenReturn("Awaiting issuer")
            whenever(document.format).thenReturn(MsoMdocFormat(docType = "eu.europa.ec.eudi.pid.1"))
            whenever(document.issuerMetadata).thenReturn(null)
            whenever(documentsController.getAllDocuments()).thenReturn(listOf(document))
            whenever(documentsController.isDocumentRevoked("unsigned-1")).thenReturn(false)

            // When
            val mapped = engine.getAllDocumentsWithDetails(locale = "en").single()

            // Then
            assertEquals(WalletDocumentIssuanceState.Pending, mapped.issuanceState)
            assertEquals("Awaiting issuer", mapped.name)
            assertNull(mapped.issuedAt)
            assertNull(mapped.expiresAt)
            assertFalse(mapped.isExpired)
            assertEquals(0, mapped.credentialsCount)
            assertEquals(0, mapped.initialCredentialsCount)
            assertFalse(mapped.isLowOnCredentials)
        }
    }

    @Test
    fun `Given issuer metadata in several locales, When mapped, Then the requested locale's display is used`() {
        coroutineRule.runTest {
            // Given
            val document = mockIssuedDocument(
                id = "multi-locale",
                issuerDisplay = listOf(
                    IssuerMetadata.IssuerDisplay(name = "English Issuer", locale = Locale.ENGLISH),
                    IssuerMetadata.IssuerDisplay(name = "Deutscher Aussteller", locale = Locale.GERMAN),
                ),
            )
            whenever(documentsController.getAllDocuments()).thenReturn(listOf(document))
            whenever(documentsController.isDocumentRevoked("multi-locale")).thenReturn(false)
            whenever(documentsController.isDocumentLowOnCredentials(document)).thenReturn(false)

            // When the caller asks with a BCP-47 tag, which is how the seam takes a locale.
            val mapped = engine.getAllDocumentsWithDetails(locale = "de-DE").single()

            // Then
            assertEquals("Deutscher Aussteller", mapped.issuerName)
        }
    }

    @Test
    fun `Given no issuer metadata, When mapped, Then issuerName and issuerLogoUri are null`() {
        coroutineRule.runTest {
            // Given — the engine resolves no strings, so the absence is reported as-is and the
            // caller supplies its own "unknown issuer" wording.
            val document = mockIssuedDocument(id = "no-meta", issuerDisplay = null)
            whenever(documentsController.getAllDocuments()).thenReturn(listOf(document))
            whenever(documentsController.isDocumentRevoked("no-meta")).thenReturn(false)
            whenever(documentsController.isDocumentLowOnCredentials(document)).thenReturn(false)

            // When
            val mapped = engine.getAllDocumentsWithDetails(locale = "en").single()

            // Then
            assertNull(mapped.issuerName)
            assertNull(mapped.issuerLogoUri)
        }
    }

    @Test
    fun `Given an empty wallet, When getAllDocumentsWithDetails is called, Then the list is empty`() {
        coroutineRule.runTest {
            // Given
            whenever(documentsController.getAllDocuments()).thenReturn(emptyList())

            // When / Then
            assertTrue(engine.getAllDocumentsWithDetails(locale = "en").isEmpty())
        }
    }

    //endregion

    //region getAllDocuments (cheap projection)

    @Test
    fun `Given documents, When getAllDocuments is called, Then only ids are mapped`() {
        // Given — this projection deliberately does no per-credential I/O, so the rest of the model
        // keeps its absent defaults even for a fully issued document.
        // `Document` itself is a sealed interface and cannot be mocked, so use a concrete subtype;
        // the projection reads only `id` regardless.
        val document = mock<IssuedDocument>()
        whenever(document.id).thenReturn("cheap-1")
        whenever(documentsController.getAllDocuments()).thenReturn(listOf(document))

        // When
        val mapped = engine.getAllDocuments().single()

        // Then
        assertEquals("cheap-1", mapped.id)
        assertEquals("", mapped.name)
        assertEquals("", mapped.formatType)
        assertEquals(0, mapped.credentialsCount)
    }

    //endregion

    //region helper functions

    private suspend fun mockIssuedDocument(
        id: String,
        name: String = "Document",
        format: DocumentFormat = MsoMdocFormat(docType = "eu.europa.ec.eudi.pid.1"),
        issuedAt: Instant = Instant.parse("2024-01-01T00:00:00Z"),
        credentialValidUntil: List<Instant> = listOf(Instant.parse("2030-01-01T00:00:00Z")),
        credentialsCount: Int = 1,
        initialCredentialsCount: Int = 1,
        issuerDisplay: List<IssuerMetadata.IssuerDisplay>? = null,
    ): IssuedDocument {
        val document = mock<IssuedDocument>()
        whenever(document.id).thenReturn(id)
        whenever(document.name).thenReturn(name)
        whenever(document.format).thenReturn(format)
        // IssuedDocument.issuedAt is a java.time.Instant; the seam exposes kotlin.time.Instant.
        whenever(document.issuedAt).thenReturn(issuedAt.toJavaInstant())
        whenever(document.credentialsCount()).thenReturn(credentialsCount)
        whenever(document.initialCredentialsCount()).thenReturn(initialCredentialsCount)
        val credentials = credentialValidUntil.map { validUntil ->
            mock<SecureAreaBoundCredential>().also {
                whenever(it.validUntil).thenReturn(validUntil)
            }
        }
        whenever(document.getCredentials()).thenReturn(credentials)
        val metadata = issuerDisplay?.let { displays ->
            mock<IssuerMetadata>().also { whenever(it.issuerDisplay).thenReturn(displays) }
        }
        whenever(document.issuerMetadata).thenReturn(metadata)
        return document
    }

    //endregion
}
