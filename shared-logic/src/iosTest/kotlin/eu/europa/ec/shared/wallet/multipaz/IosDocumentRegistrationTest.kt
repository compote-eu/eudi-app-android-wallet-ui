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

package eu.europa.ec.shared.wallet.multipaz

import eu.europa.ec.shared.wallet.document.WalletCredentialPolicy
import eu.europa.ec.shared.wallet.multipaz.harness.MDOC_PID_DOC_TYPE
import eu.europa.ec.shared.wallet.multipaz.harness.sampleIssuerMetadata
import eu.europa.ec.shared.wallet.multipaz.harness.samplePidElements
import eu.europa.ec.shared.wallet.multipaz.harness.seedMdocDocument
import kotlinx.coroutines.test.runTest
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import platform.Foundation.timeIntervalSince1970
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * What this wallet would hand iOS's document-provider registry, read from a real `DocumentStore`.
 *
 * The Swift half cannot be tested from here — it talks to `IdentityDocumentProviderRegistrationStore`,
 * which needs an entitlement this fork does not yet carry and, on the simulator, answers
 * `notAuthorized` regardless. So the line is drawn where it can be: **everything up to the Apple call**
 * is Kotlin, and all of it is asserted here. What is left unproven is one `addRegistration` invocation
 * with four fields, and that is deliberate rather than accidental.
 */
class IosDocumentRegistrationTest {

    private suspend fun store(
        storage: Storage = EphemeralStorage(),
    ): MultipazWalletStore = MultipazWalletStore.build(
        storage = storage,
        secureAreas = listOf(SoftwareSecureArea.create(storage)),
        documentManagerId = MultipazWalletStore.DEFAULT_DOCUMENT_MANAGER_ID,
    )

    private suspend fun MultipazWalletStore.seedPid(
        docType: String = MDOC_PID_DOC_TYPE,
        validUntil: kotlin.time.Instant = Clock.System.now() + 30.days,
        numberOfCredentials: Int = 3,
    ): String = seedMdocDocument(
        docType = docType,
        displayName = "PID MSO MDoc",
        namespace = docType,
        elements = samplePidElements(givenName = "Tester"),
        policy = WalletCredentialPolicy.RotatingBatch(numberOfCredentials = numberOfCredentials),
        issuerMetadata = sampleIssuerMetadata(docType),
        // ISO 18013-5 requires validUntil strictly after validFrom, so derive rather than use `now`.
        validFrom = validUntil - 30.days,
        validUntil = validUntil,
    )

    @Test
    fun an_empty_wallet_registers_nothing() = runTest {
        assertTrue(store().registrableDocuments().isEmpty())
    }

    @Test
    fun a_seeded_mdoc_document_is_offered_with_its_doctype_and_identifier() = runTest {
        val store = store()
        val documentId = store.seedPid()

        val registrable = store.registrableDocuments()

        assertEquals(1, registrable.size)
        assertEquals(documentId, registrable.single().documentIdentifier)
        assertEquals(MDOC_PID_DOC_TYPE, registrable.single().mobileDocumentType)
    }

    /**
     * The field Apple keys the picker on is the *mdoc* doctype, not our display name or identifier —
     * so a document of another type must come back under that type, unchanged.
     */
    @Test
    fun the_doctype_is_the_issuer_s_and_is_not_rewritten() = runTest {
        val store = store()
        store.seedPid(docType = "org.iso.18013.5.1.mDL")

        assertEquals("org.iso.18013.5.1.mDL", store.registrableDocuments().single().mobileDocumentType)
    }

    /**
     * The registration must outlive the *last* usable credential, not the first.
     *
     * Seeding twice puts two documents in the store with different windows; each carries its own
     * invalidation date, which is what proves the date is read per document rather than globally.
     */
    @Test
    fun each_document_carries_its_own_invalidation_date() = runTest {
        val store = store()
        val soon = Clock.System.now() + 2.days
        val later = Clock.System.now() + 60.days
        store.seedPid(validUntil = soon)
        store.seedPid(validUntil = later)

        val dates = store.registrableDocuments()
            .map { assertNotNull(it.invalidationDate).timeIntervalSince1970 }
            .sorted()

        assertEquals(2, dates.size)
        // Whole seconds: the MSO truncates, so compare at that resolution rather than exactly.
        assertEquals(soon.epochSeconds.toDouble(), dates[0], 1.0)
        assertEquals(later.epochSeconds.toDouble(), dates[1], 1.0)
    }

    /**
     * An expired document is still registered, and that is the correct behaviour rather than an
     * oversight: `invalidationDate` is how iOS is *told* it has expired. Filtering it out here would
     * leave a stale registration in the OS registry with nothing to ever remove it.
     */
    @Test
    fun an_expired_document_is_still_offered_so_the_OS_learns_its_invalidation_date() = runTest {
        val store = store()
        val expiry = Clock.System.now() - 1.days
        store.seedPid(validUntil = expiry)

        val registrable = store.registrableDocuments()

        assertEquals(1, registrable.size)
        assertEquals(
            expiry.epochSeconds.toDouble(),
            assertNotNull(registrable.single().invalidationDate).timeIntervalSince1970,
            1.0,
        )
    }

    /**
     * A document with no *certified* credential has no `MdocCredential` to read a doctype from, so it
     * cannot be registered — there is nothing to present. This is the same filter that keeps SD-JWT-only
     * documents out, reached by the other route.
     */
    @Test
    fun a_document_with_no_certified_credential_is_skipped() = runTest {
        val store = store()
        store.seedPid(numberOfCredentials = 0)

        assertTrue(store.registrableDocuments().isEmpty())
    }

    @Test
    fun the_summary_string_names_the_doctype_and_the_document() = runTest {
        val store = store()
        val documentId = store.seedPid()

        assertEquals(
            "$MDOC_PID_DOC_TYPE/$documentId",
            store.registrableDocuments().single().toString(),
        )
    }

    /**
     * One entry per *document*, however many credentials it holds.
     *
     * Worth pinning because the natural mistake is to map over credentials rather than documents: a
     * rotating batch of three would then register the same document three times, and the picker would
     * show it three times.
     */
    @Test
    fun a_document_with_a_batch_of_credentials_is_registered_once() = runTest {
        val store = store()
        val documentId = store.seedPid(numberOfCredentials = 3)

        val registrable = store.registrableDocuments()

        assertEquals(1, registrable.size)
        assertEquals(documentId, registrable.single().documentIdentifier)
    }
}
