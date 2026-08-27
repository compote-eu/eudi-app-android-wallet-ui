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

import eu.europa.ec.shared.wallet.WalletDocumentIssuanceState
import eu.europa.ec.shared.wallet.document.WalletCredentialPolicy
import eu.europa.ec.shared.wallet.multipaz.harness.MDOC_PID_DOC_TYPE
import eu.europa.ec.shared.wallet.multipaz.harness.isEmpty
import eu.europa.ec.shared.wallet.multipaz.harness.sampleIssuerMetadata
import eu.europa.ec.shared.wallet.multipaz.harness.samplePidElements
import eu.europa.ec.shared.wallet.multipaz.harness.seedMdocDocument
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import eu.europa.ec.shared.wallet.revocation.StatusTrustPolicyDomain
import kotlinx.io.bytestring.ByteString
import org.multipaz.asn1.ASN1Integer
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.revocation.RevocationStatus
import org.multipaz.revocation.StatusList
import org.multipaz.cbor.Tstr
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * The iOS document layer against a **real** multipaz `DocumentStore` — real documents, real
 * `MdocCredential`s certified with a real Mobile Security Object, read back through the real
 * `WalletEngine`. Ephemeral storage and a software secure area keep it deterministic and fast, which
 * is the only reason it is not the Secure Enclave.
 *
 * iosTest rather than commonTest by necessity: multipaz's `DocumentStore` is an iOS-only dependency
 * of this module, precisely so it stays off the Android app's classpath. The *decisions* the engine
 * makes about a document live in commonMain and are tested there
 * ([eu.europa.ec.shared.wallet.document.WalletDocumentProjectionTest]); what these tests add is the
 * half that only multipaz can answer — that the metadata round-trips through CBOR, that credentials
 * are found and counted, and that mdoc claims come out as the Android side spells them.
 */
class MultipazWalletEngineTest {

    private suspend fun store(
        storage: Storage = EphemeralStorage(),
        documentManagerId: String = MultipazWalletStore.DEFAULT_DOCUMENT_MANAGER_ID,
    ): MultipazWalletStore = MultipazWalletStore.build(
        storage = storage,
        secureAreas = listOf(SoftwareSecureArea.create(storage)),
        documentManagerId = documentManagerId,
    )

    private suspend fun MultipazWalletStore.seedPid(
        docType: String = MDOC_PID_DOC_TYPE,
        displayName: String = "PID MSO MDoc",
        givenName: String = "Tester",
        policy: WalletCredentialPolicy = WalletCredentialPolicy.RotatingBatch(numberOfCredentials = 3),
        markIssued: Boolean = true,
        validUntil: kotlin.time.Instant = Clock.System.now() + 30.days,
        revocationStatus: RevocationStatus? = null,
    ): String = seedMdocDocument(
        docType = docType,
        displayName = displayName,
        namespace = docType,
        elements = samplePidElements(givenName = givenName),
        policy = policy,
        issuerMetadata = sampleIssuerMetadata(docType),
        markIssued = markIssued,
        // Derived from validUntil rather than from `now`, so an already-expired window is still a
        // well-formed one — ISO 18013-5 requires validUntil to be strictly later than validFrom, and
        // the MSO truncates both to whole seconds.
        validFrom = validUntil - 30.days,
        validUntil = validUntil,
        revocationStatus = revocationStatus,
    )

    //region the empty wallet

    @Test
    fun an_empty_store_yields_no_documents_at_all() = runTest {
        val engine = MultipazWalletEngine(store())

        assertTrue(engine.getAllDocuments().isEmpty())
        assertTrue(engine.getAllDocumentsWithDetails(locale = "en").isEmpty())
        assertNull(engine.getMainPidDocument())
    }

    //endregion

    //region reading a seeded document

    @Test
    fun a_seeded_mdoc_document_is_listed_by_the_cheap_projection_with_only_its_id() = runTest {
        val store = store()
        val documentId = store.seedPid()

        val documents = MultipazWalletEngine(store).getAllDocuments()

        assertEquals(1, documents.size)
        assertEquals(documentId, documents.single().id)
        // The cheap projection reads no credentials, so everything else keeps its absent default.
        assertEquals("", documents.single().name)
        assertEquals(0, documents.single().credentialsCount)
    }

    @Test
    fun a_seeded_mdoc_document_reads_back_with_every_detail_field() = runTest {
        val store = store()
        val documentId = store.seedPid()

        val document = MultipazWalletEngine(store)
            .getAllDocumentsWithDetails(locale = "en")
            .single()

        assertEquals(documentId, document.id)
        assertEquals("PID MSO MDoc", document.name)
        assertEquals(MDOC_PID_DOC_TYPE, document.formatType)
        assertEquals(WalletDocumentIssuanceState.Issued, document.issuanceState)
        assertNotNull(document.issuedAt)
        assertNotNull(document.expiresAt)
        assertFalse(document.isExpired)
        assertEquals(3, document.credentialsCount)
        assertEquals(3, document.initialCredentialsCount)
        assertFalse(document.isLowOnCredentials)
        assertEquals("Fixture Issuer", document.issuerName)
        assertEquals("https://fixture.issuer.invalid/en.png", document.issuerLogoUri)
        // Not read by the list projection, matching Android.
        assertTrue(document.claims.isEmpty())
    }

    @Test
    fun the_credential_validity_window_comes_from_the_signed_mso() = runTest {
        val store = store()
        val expiry = Clock.System.now() + 400.days
        store.seedPid(validUntil = expiry)

        val document = MultipazWalletEngine(store)
            .getAllDocumentsWithDetails(locale = "en")
            .single()

        // The MSO drops fractional seconds, so compare at second granularity.
        assertEquals(expiry.epochSeconds, document.expiresAt?.epochSeconds)
    }

    @Test
    fun an_expired_document_is_reported_as_expired_and_keeps_its_expiry() = runTest {
        val store = store()
        store.seedPid(validUntil = Clock.System.now() - 1.days)

        val document = MultipazWalletEngine(store)
            .getAllDocumentsWithDetails(locale = "en")
            .single()

        assertTrue(document.isExpired)
        assertNotNull(document.expiresAt)
        assertEquals(3, document.credentialsCount)
    }

    @Test
    fun the_requested_locale_selects_the_issuer_display_read_out_of_stored_metadata() = runTest {
        val store = store()
        store.seedPid()

        val engine = MultipazWalletEngine(store)

        assertEquals(
            "Fixture Vydavatel",
            engine.getAllDocumentsWithDetails(locale = "sk-SK").single().issuerName,
        )
        assertEquals(
            "Fixture Issuer",
            engine.getAllDocumentsWithDetails(locale = "en-GB").single().issuerName,
        )
    }

    @Test
    fun a_pending_document_reads_back_as_pending() = runTest {
        val store = store()
        store.seedPid(markIssued = false)

        val document = MultipazWalletEngine(store)
            .getAllDocumentsWithDetails(locale = "en")
            .single()

        assertEquals(WalletDocumentIssuanceState.Pending, document.issuanceState)
        assertNull(document.issuedAt)
        assertNull(document.expiresAt)
        assertEquals(0, document.credentialsCount)
        // Issuer display still resolves — it was published up front, not with the credential.
        assertEquals("Fixture Issuer", document.issuerName)
    }

    //endregion

    //region metadata persistence

    @Test
    fun the_document_metadata_survives_a_round_trip_through_storage() = runTest {
        // A second store over the same storage has its own document cache, so this really does
        // re-read and re-parse the CBOR metadata rather than returning the in-memory instance.
        val storage = EphemeralStorage()
        store(storage).seedPid(
            policy = WalletCredentialPolicy.OnceOnly(numberOfCredentials = 5),
        )

        val document = MultipazWalletEngine(store(storage))
            .getAllDocumentsWithDetails(locale = "en")
            .single()

        assertEquals(MDOC_PID_DOC_TYPE, document.formatType)
        assertEquals(WalletDocumentIssuanceState.Issued, document.issuanceState)
        // initialCredentialsCount comes from the credential policy, so reading it back proves the
        // policy — the field with the platform-specific CBOR tag — survived serialization.
        assertEquals(5, document.initialCredentialsCount)
        assertEquals("Fixture Issuer", document.issuerName)
    }

    @Test
    fun a_document_belonging_to_another_document_manager_is_not_listed() = runTest {
        val storage = EphemeralStorage()
        store(storage, documentManagerId = "some-other-wallet").seedPid()

        val engine = MultipazWalletEngine(store(storage))

        assertTrue(engine.getAllDocuments().isEmpty())
        assertTrue(engine.getAllDocumentsWithDetails(locale = "en").isEmpty())
        assertNull(engine.getMainPidDocument())
    }

    @Test
    fun a_once_only_document_that_has_spent_a_credential_reports_the_remainder() = runTest {
        val store = store()
        store.seedPid(policy = WalletCredentialPolicy.OnceOnly(numberOfCredentials = 3))

        // Spend one credential the way a presentation would.
        store.documentStore.listDocuments().single().getCertifiedCredentials()
            .first().increaseUsageCount()

        val document = MultipazWalletEngine(store)
            .getAllDocumentsWithDetails(locale = "en")
            .single()

        assertEquals(2, document.credentialsCount)
        assertEquals(3, document.initialCredentialsCount)
        assertFalse(document.isLowOnCredentials)
    }

    //endregion

    //region claims — the mdoc-only part

    @Test
    fun the_main_pid_document_carries_its_claims_keyed_by_data_element_name() = runTest {
        val store = store()
        val documentId = store.seedPid(givenName = "Tester")

        val pid = assertNotNull(MultipazWalletEngine(store).getMainPidDocument())

        assertEquals(documentId, pid.id)
        assertEquals("Tester", pid.claims["given_name"])
        assertEquals("Kotlin", pid.claims["family_name"])
        // A tagged full-date must come out as the plain date, as Android's upokecenter path does.
        assertEquals("1990-01-01", pid.claims["birth_date"])
        assertEquals("true", pid.claims["age_over_18"])
        assertEquals("36", pid.claims["age_in_years"])
        // A byte string becomes base64url; `0x01 0x02 0x03` is `AQID`.
        assertEquals("AQID", pid.claims["portrait"])
    }

    @Test
    fun the_main_pid_document_returns_id_and_claims_and_nothing_else() = runTest {
        // Deliberately the same narrow projection the Android engine returns, so a consumer cannot
        // start depending on fields one platform fills and the other does not.
        val store = store()
        store.seedPid()

        val pid = assertNotNull(MultipazWalletEngine(store).getMainPidDocument())

        assertEquals("", pid.name)
        assertEquals("", pid.formatType)
        assertEquals(0, pid.credentialsCount)
        assertNull(pid.issuedAt)
        assertNull(pid.issuerName)
    }

    @Test
    fun the_main_pid_is_the_oldest_issued_pid() = runTest {
        val store = store()
        val first = store.seedPid(givenName = "First")
        store.seedPid(givenName = "Second")

        val pid = assertNotNull(MultipazWalletEngine(store).getMainPidDocument())

        assertEquals(first, pid.id)
        assertEquals("First", pid.claims["given_name"])
    }

    @Test
    fun a_non_pid_document_is_never_the_main_pid() = runTest {
        val store = store()
        store.seedPid(docType = "org.iso.18013.5.1.mDL", displayName = "mDL")

        assertNull(MultipazWalletEngine(store).getMainPidDocument())
        // It is still a perfectly readable document, though.
        assertEquals("mDL", MultipazWalletEngine(store).getAllDocumentsWithDetails("en").single().name)
    }

    @Test
    fun a_pending_pid_is_never_the_main_pid() = runTest {
        val store = store()
        store.seedPid(markIssued = false)

        assertNull(MultipazWalletEngine(store).getMainPidDocument())
    }

    @Test
    fun a_document_whose_display_name_is_absent_falls_back_to_its_format_identifier() = runTest {
        val store = store()
        store.seedMdocDocument(
            docType = MDOC_PID_DOC_TYPE,
            displayName = MDOC_PID_DOC_TYPE,
            namespace = MDOC_PID_DOC_TYPE,
            elements = listOf("given_name" to Tstr("Tester")),
            policy = WalletCredentialPolicy.RotatingBatch(numberOfCredentials = 1),
        )

        assertEquals(
            MDOC_PID_DOC_TYPE,
            MultipazWalletEngine(store).getAllDocumentsWithDetails("en").single().name,
        )
    }

    //endregion

    //region bookmarks

    @Test
    fun a_bookmark_is_stored_read_back_and_removed() = runTest {
        val store = store()
        val engine = MultipazWalletEngine(store)

        assertFalse(engine.isDocumentBookmarked("doc-1"))

        engine.storeBookmark("doc-1")
        assertTrue(engine.isDocumentBookmarked("doc-1"))
        assertFalse(engine.isDocumentBookmarked("doc-2"))

        engine.deleteBookmark("doc-1")
        assertFalse(engine.isDocumentBookmarked("doc-1"))
    }

    @Test
    fun bookmarking_twice_is_a_no_op_rather_than_an_error() = runTest {
        val engine = MultipazWalletEngine(store())

        engine.storeBookmark("doc-1")
        engine.storeBookmark("doc-1")

        assertTrue(engine.isDocumentBookmarked("doc-1"))
    }

    @Test
    fun deleting_a_bookmark_that_was_never_stored_is_a_no_op() = runTest {
        val engine = MultipazWalletEngine(store())

        engine.deleteBookmark("never-bookmarked")

        assertFalse(engine.isDocumentBookmarked("never-bookmarked"))
    }

    @Test
    fun bookmarks_survive_a_round_trip_through_storage() = runTest {
        val storage = EphemeralStorage()
        MultipazWalletEngine(store(storage)).storeBookmark("doc-1")

        assertTrue(MultipazWalletEngine(store(storage)).isDocumentBookmarked("doc-1"))
    }

    //endregion

    //region revocation

    /** A status entry pointing at [uri], with [key]'s certificate pinned, as an issuer would write. */
    private suspend fun statusEntry(key: EcPrivateKey, idx: Int): RevocationStatus.StatusList {
        val name = X500Name.fromName("CN=Status List Signer")
        return RevocationStatus.StatusList(
            idx = idx,
            uri = "https://issuer.test/statuslists/1",
            certificate = X509Cert.Builder(
                publicKey = key.publicKey,
                signingKey = AsymmetricKey.AnonymousExplicit(privateKey = key),
                serialNumber = ASN1Integer(1L),
                subject = name,
                issuer = name,
                validFrom = Clock.System.now() - 1.days,
                validUntil = Clock.System.now() + 30.days,
            ).build(),
        )
    }

    private suspend fun tokenSaying(key: EcPrivateKey, revoked: List<Int>): String =
        StatusList.Builder(1)
            .apply { revoked.forEach { addStatus(it, 1) } }
            .build()
            .compress()
            .serializeAsJwt(
                key = AsymmetricKey.AnonymousExplicit(privateKey = key),
                subject = "https://issuer.test/statuslists/1",
            )

    /**
     * The same list signed with an `x5c` chain rather than anonymously — a root plus a leaf, because
     * `buildJwt` emits `toX5c(excludeRoot = true)` and a lone self-signed certificate would leave the
     * header's `x5c` empty. Paired with a credential naming no `certificate`, this is the unanchored
     * case: readable, but nothing ties it to a trusted party.
     */
    private suspend fun x5cTokenSaying(key: EcPrivateKey, revoked: List<Int>): String {
        val rootKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val rootName = X500Name.fromName("CN=Status List Root")
        val root = X509Cert.Builder(
            publicKey = rootKey.publicKey,
            signingKey = AsymmetricKey.AnonymousExplicit(privateKey = rootKey),
            serialNumber = ASN1Integer(20L),
            subject = rootName,
            issuer = rootName,
            validFrom = Clock.System.now() - 1.days,
            validUntil = Clock.System.now() + 30.days,
        ).build()
        val leaf = X509Cert.Builder(
            publicKey = key.publicKey,
            signingKey = AsymmetricKey.AnonymousExplicit(privateKey = rootKey),
            serialNumber = ASN1Integer(21L),
            subject = X500Name.fromName("CN=Status List Signer"),
            issuer = rootName,
            validFrom = Clock.System.now() - 1.days,
            validUntil = Clock.System.now() + 30.days,
        ).build()
        return StatusList.Builder(1)
            .apply { revoked.forEach { addStatus(it, 1) } }
            .build()
            .compress()
            .serializeAsJwt(
                key = AsymmetricKey.X509CertifiedExplicit(
                    certChain = X509CertChain(listOf(leaf, root)),
                    privateKey = key,
                ),
                subject = "https://issuer.test/statuslists/1",
            )
    }

    private fun checkerServing(token: String) =
        MultipazRevocationChecker(HttpClient(MockEngine { respond(token) }))

    private fun failingChecker() =
        MultipazRevocationChecker(HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) }))

    @Test
    fun nothing_is_flagged_before_the_first_refresh_runs() = runTest {
        val store = store()
        store.seedPid()
        val engine = MultipazWalletEngine(store)

        // Same as Android before its first WorkManager period elapses: the cache is simply empty.
        assertTrue(engine.getRevokedDocumentIds().isEmpty())
        assertFalse(engine.isDocumentRevoked("doc-1"))
        assertFalse(engine.getAllDocumentsWithDetails("en").single().isRevoked)
    }

    @Test
    fun a_refresh_flags_a_document_its_status_list_says_is_revoked() = runTest {
        val key = Crypto.createEcPrivateKey(EcCurve.P256)
        val store = store()
        val documentId = store.seedPid(revocationStatus = statusEntry(key, idx = 4))
        val engine = MultipazWalletEngine(store)

        val newlyRevoked = engine.refreshRevocationStatuses(
            checker = checkerServing(tokenSaying(key, revoked = listOf(4)))
        )

        assertEquals(listOf(documentId), newlyRevoked.map { it.id })
        assertTrue(engine.isDocumentRevoked(documentId))
        assertEquals(listOf(documentId), engine.getRevokedDocumentIds())
        // And the flag reaches the projection the document list renders.
        assertTrue(engine.getAllDocumentsWithDetails("en").single().isRevoked)
    }

    // ---- the configured trust policy decides what an unanchored reading may do ----------------

    @Test
    fun under_inform_an_unanchored_reading_flags_a_document_it_calls_revoked() = runTest {
        // The shipped posture, and the reference wallets': the status decides. Being unable to
        // anchor the signer must not mean a revoked credential keeps displaying as valid.
        val key = Crypto.createEcPrivateKey(EcCurve.P256)
        val store = store()
        val documentId = store.seedPid(
            revocationStatus = RevocationStatus.StatusList(
                idx = 4,
                uri = "https://issuer.test/statuslists/1",
                certificate = null,
            )
        )
        val engine = MultipazWalletEngine(store)
        val outcomes = mutableMapOf<String, RevocationOutcome>()

        val newlyRevoked = engine.refreshRevocationStatuses(
            checker = checkerServing(x5cTokenSaying(key, revoked = listOf(4))),
            policy = StatusTrustPolicyDomain.Inform,
            onOutcome = { id, outcome -> outcomes[id] = outcome },
        )

        // The reading is honest about what it could establish...
        val outcome = outcomes.getValue(documentId)
        assertTrue(outcome.isRevoked)
        assertFalse(outcome.signerAnchored)
        // ...and under Inform it still decides.
        assertEquals(listOf(documentId), newlyRevoked.map { it.id })
        assertTrue(engine.isDocumentRevoked(documentId))
    }

    @Test
    fun under_enforce_an_unanchored_reading_decides_nothing() = runTest {
        // The other posture the config allows. Same token, same reading, opposite outcome — which is
        // the whole point of the knob being config rather than a hard-coded gate.
        val key = Crypto.createEcPrivateKey(EcCurve.P256)
        val store = store()
        val documentId = store.seedPid(
            revocationStatus = RevocationStatus.StatusList(
                idx = 4,
                uri = "https://issuer.test/statuslists/1",
                certificate = null,
            )
        )
        val engine = MultipazWalletEngine(store)

        val newlyRevoked = engine.refreshRevocationStatuses(
            checker = checkerServing(x5cTokenSaying(key, revoked = listOf(4))),
            policy = StatusTrustPolicyDomain.Enforce,
        )

        assertTrue(newlyRevoked.isEmpty())
        assertFalse(engine.isDocumentRevoked(documentId))
    }

    @Test
    fun under_enforce_an_unanchored_valid_cannot_clear_an_existing_revocation() = runTest {
        // The dangerous direction. Under Enforce, answering the status list URL must not be enough
        // to un-revoke a credential — which is the whole purpose of verifying the list's signature.
        val key = Crypto.createEcPrivateKey(EcCurve.P256)
        val store = store()
        val documentId = store.seedPid(
            revocationStatus = RevocationStatus.StatusList(
                idx = 4,
                uri = "https://issuer.test/statuslists/1",
                certificate = null,
            )
        )
        store.revokedDocumentsTable().insert(key = documentId, data = ByteString())
        val engine = MultipazWalletEngine(store)
        assertTrue(engine.isDocumentRevoked(documentId))

        engine.refreshRevocationStatuses(
            checker = checkerServing(x5cTokenSaying(key, revoked = emptyList())),
            policy = StatusTrustPolicyDomain.Enforce,
        )

        assertTrue(engine.isDocumentRevoked(documentId), "an unanchored Valid must not un-revoke")
    }

    @Test
    fun a_document_whose_status_list_says_valid_is_not_flagged() = runTest {
        val key = Crypto.createEcPrivateKey(EcCurve.P256)
        val store = store()
        store.seedPid(revocationStatus = statusEntry(key, idx = 4))
        val engine = MultipazWalletEngine(store)

        val newlyRevoked = engine.refreshRevocationStatuses(
            checker = checkerServing(tokenSaying(key, revoked = listOf(9)))
        )

        assertTrue(newlyRevoked.isEmpty())
        assertTrue(engine.getRevokedDocumentIds().isEmpty())
    }

    @Test
    fun a_second_refresh_reports_nothing_new_for_an_already_flagged_document() = runTest {
        val key = Crypto.createEcPrivateKey(EcCurve.P256)
        val store = store()
        val documentId = store.seedPid(revocationStatus = statusEntry(key, idx = 4))
        val engine = MultipazWalletEngine(store)
        val token = tokenSaying(key, revoked = listOf(4))

        engine.refreshRevocationStatuses(checker = checkerServing(token))
        val second = engine.refreshRevocationStatuses(checker = checkerServing(token))

        // "Newly revoked" is what drives the user-facing notification, so re-reporting it every
        // refresh would nag once per period — the same reason the Android worker diffs against Room.
        assertTrue(second.isEmpty())
        assertTrue(engine.isDocumentRevoked(documentId))
    }

    @Test
    fun a_document_that_becomes_valid_again_is_unflagged() = runTest {
        val key = Crypto.createEcPrivateKey(EcCurve.P256)
        val store = store()
        val documentId = store.seedPid(revocationStatus = statusEntry(key, idx = 4))
        val engine = MultipazWalletEngine(store)

        engine.refreshRevocationStatuses(checker = checkerServing(tokenSaying(key, listOf(4))))
        assertTrue(engine.isDocumentRevoked(documentId))

        // Revocation is not a one-way door — a suspended credential can be reinstated, and the
        // Android worker removes the row in exactly this case.
        engine.refreshRevocationStatuses(checker = checkerServing(tokenSaying(key, emptyList())))

        assertFalse(engine.isDocumentRevoked(documentId))
        assertTrue(engine.getRevokedDocumentIds().isEmpty())
    }

    @Test
    fun an_unreachable_status_list_leaves_an_existing_flag_in_place() = runTest {
        val key = Crypto.createEcPrivateKey(EcCurve.P256)
        val store = store()
        val documentId = store.seedPid(revocationStatus = statusEntry(key, idx = 4))
        val engine = MultipazWalletEngine(store)

        engine.refreshRevocationStatuses(checker = checkerServing(tokenSaying(key, listOf(4))))

        // THE asymmetry that matters: going offline must not un-revoke a document. Only a positive
        // "valid" answer clears the flag; `Unknown` leaves it alone.
        engine.refreshRevocationStatuses(checker = failingChecker())

        assertTrue(engine.isDocumentRevoked(documentId))
    }

    @Test
    fun a_document_with_no_status_entry_is_reported_unknown_and_left_alone() = runTest {
        val key = Crypto.createEcPrivateKey(EcCurve.P256)
        val store = store()
        val documentId = store.seedPid() // no status element, like every test issuer's credential
        val engine = MultipazWalletEngine(store)
        val outcomes = mutableMapOf<String, RevocationOutcome>()

        val newlyRevoked = engine.refreshRevocationStatuses(
            checker = checkerServing(tokenSaying(key, listOf(4))),
            onOutcome = { id, outcome -> outcomes[id] = outcome },
        )

        assertTrue(newlyRevoked.isEmpty())
        assertIs<RevocationOutcome.Unknown>(outcomes[documentId])
        assertFalse(engine.isDocumentRevoked(documentId))
    }

    //endregion

    //region the fixture's own contract

    @Test
    fun the_store_reports_itself_empty_only_until_a_document_is_seeded() = runTest {
        val store = store()

        assertTrue(store.isEmpty())

        store.seedPid()

        assertFalse(store.isEmpty())
    }

    //endregion
}
