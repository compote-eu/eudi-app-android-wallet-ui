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

import eu.europa.ec.corelogic.model.TransactionLogDomain
import eu.europa.ec.corelogic.model.TransactionResultDomain
import eu.europa.ec.shared.wallet.WalletDocumentIssuanceState
import eu.europa.ec.shared.wallet.document.IssuerMetadata
import eu.europa.ec.shared.wallet.document.WalletCredentialPolicy
import eu.europa.ec.shared.wallet.multipaz.harness.issuerNamespacesOf
import eu.europa.ec.shared.wallet.multipaz.harness.issuerSignedDataFor
import eu.europa.ec.shared.wallet.multipaz.harness.samplePidElements
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import eu.europa.ec.shared.wallet.trust.IssuerTrustSource
import eu.europa.ec.shared.wallet.trust.TrustVerdict
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.buildCborMap
import org.multipaz.document.Document
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.securearea.software.SoftwareCreateKeySettings
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.util.toBase64Url
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wallet half of deferred issuance: does a **parked document actually become an issued one** when
 * the issuer finally mints its credential?
 *
 * [IosDeferredCredentialCollectorTest] pins the protocol; this pins what the wallet does with the
 * answer, which is the part a user sees. The document here is built in the state a `202 Accepted`
 * leaves behind — a transaction id, stored authorization, and **pending (uncertified) credentials whose
 * keys multipaz created before the request** — because that state is the whole reason the flow is
 * possible: the credential the issuer mints is bound to those keys.
 */
class IosDeferredDocumentCompleterTest {

    private val docType = "eu.europa.ec.eudi.pid.1"
    private val issuerUrl = "https://issuer.test"
    private val deferredEndpoint = "https://issuer.test/wallet/deferredEndpoint"
    private val tokenEndpoint = "https://as.test/token"
    private val dpopAlias = "dpop-key-alias"
    private val policy = WalletCredentialPolicy.OnceOnly(numberOfCredentials = 1)

    private val issuer = IosVciIssuer(
        issuerUrl = issuerUrl,
        clientId = "eudiw-abca",
        redirectUri = "eu.europa.ec.euidi://authorization",
        order = 0,
    )

    private suspend fun storeOver(storage: Storage): MultipazWalletStore =
        MultipazWalletStore.build(
            storage = storage,
            secureAreas = listOf(SoftwareSecureArea.create(storage)),
        )

    /**
     * A document in exactly the state a deferred issuance leaves: the issuer's handle, the
     * authorization multipaz stores, and one pending credential.
     */
    private suspend fun MultipazWalletStore.parkWithPendingCredential(
        transactionId: String = "txn-abc-123",
        /** Null for an issuer that grants none: then multipaz stores no authorization data at all. */
        refreshToken: String? = "the-refresh-token",
        resume: DeferredResume? = null,
        /** How many credentials wait, each with its own key, as a batch issuance leaves them. */
        pendingCredentials: Int = 1,
        issuerParty: IssuerPartyRecord? = null,
        userTriggered: Boolean? = null,
    ): Document {
        keySecureArea.createKey(dpopAlias, SoftwareCreateKeySettings.Builder().build())
        val document = documentStore.createDocument(
            displayName = "PID (deferred)",
            metadata = EudiDocumentMetadata.create(
                documentManagerId = documentManagerId,
                format = StoredDocumentFormat.MsoMdoc(docType),
                credentialPolicy = policy,
                deferredTransactionId = transactionId,
                issuerMetadata = IssuerMetadata(
                    documentConfigurationIdentifier = "mso_mdoc",
                    credentialIssuerIdentifier = issuerUrl,
                ),
                issuerParty = issuerParty,
                userTriggered = userTriggered,
            ).also { it.park(transactionId, resume) },
        )
        // The CBOR multipaz writes. Only the two members this flow reads are set; the rest is absent,
        // which is legal there since every member is optional.
        if (refreshToken != null) {
            val authorization = Cbor.encode(
                buildCborMap {
                    put("dpopKeyAlias", Tstr(dpopAlias))
                    put("refreshToken", Tstr(refreshToken))
                }
            )
            document.edit { authorizationData = ByteString(*authorization) }
        }
        repeat(pendingCredentials) {
            MdocCredential.create(
                document = document,
                asReplacementForIdentifier = null,
                domain = documentManagerId,
                secureArea = keySecureArea,
                docType = docType,
                createKeySettings = SoftwareCreateKeySettings.Builder().build(),
            )
        }
        return document
    }

    /** The bytes an issuer would return for [document]'s waiting credential, base64url as on the wire. */
    private suspend fun issuedCredentialFor(document: Document): String =
        issuedCredentialFor(document.getPendingCredentials().single() as MdocCredential)

    /** The bytes an issuer would return for [pending], bound to its key. */
    private suspend fun issuedCredentialFor(pending: MdocCredential): String {
        val signer = testSigner()
        val data = issuerSignedDataFor(
            docType = docType,
            issuerNamespaces = issuerNamespacesOf(docType, samplePidElements(), Random.Default),
            deviceKey = pending.secureArea.getKeyInfo(pending.alias).publicKey,
            validFrom = Clock.System.now() - 1.days,
            validUntil = Clock.System.now() + 30.days,
            // A PID is stored only with a signer chain to check, as an issuer's always carries, and a
            // signature that verifies against it.
            issuerCertChain = signer.chain,
            issuerSigningKey = signer.key,
        )
        return data.toByteArray().toBase64Url()
    }

    /** What the stand-in issuer was asked, with the token each request presented. */
    private class Sent(val url: String, val authorization: String?)

    private fun completerOver(
        store: MultipazWalletStore,
        deferredStatus: HttpStatusCode,
        deferredBody: String,
        sent: MutableList<Sent> = mutableListOf(),
        /** What a refresh answers; a rotating server sends a new `refresh_token` here. */
        tokenBody: String = """{"access_token":"the-token"}""",
        /** A token the deferred endpoint refuses with `invalid_token`, as an expired one would be. */
        refusedToken: String? = null,
        /** Trusts every signer unless told otherwise: most cases here are about collecting, not trust. */
        issuerTrust: IssuerTrustSource = IssuerTrustSource { _, _ -> TrustVerdict.TRUSTED },
    ): IosDeferredDocumentCompleter {
        val engine = MockEngine { request ->
            val url = request.url.toString()
            sent += Sent(url, request.headers["Authorization"])
            when {
                url.endsWith(".well-known/openid-credential-issuer") -> respond(
                    """{"credential_issuer":"$issuerUrl",""" +
                            """"deferred_credential_endpoint":"$deferredEndpoint",""" +
                            """"authorization_servers":["https://as.test"]}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )

                url.endsWith(".well-known/openid-configuration") ->
                    respond("""{"token_endpoint":"$tokenEndpoint"}""", HttpStatusCode.OK, jsonHeaders)

                url.endsWith("/wallet-instance-attestation/jwk") -> respond(
                    """{"walletInstanceAttestation":"wia.jwt"}""", HttpStatusCode.OK, jsonHeaders
                )

                url == tokenEndpoint -> respond(tokenBody, HttpStatusCode.OK, jsonHeaders)

                url == deferredEndpoint && request.headers["Authorization"] == "DPoP $refusedToken" -> respond(
                    "",
                    HttpStatusCode.Unauthorized,
                    headersOf(HttpHeaders.WWWAuthenticate, """DPoP error="invalid_token""""),
                )

                url == deferredEndpoint -> respond(deferredBody, deferredStatus, jsonHeaders)

                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        return IosDeferredDocumentCompleter(
            store = store,
            httpClient = HttpClient(engine),
            walletProviderBaseUrl = "https://wallet-provider.test",
            issuers = listOf(issuer),
            issuerTrust = issuerTrust,
        )
    }

    @Test
    fun a_parked_document_is_finished_when_the_issuer_finally_mints_the_credential() = runTest {
        val storage = EphemeralStorage()
        val store = storeOver(storage)
        val document = store.parkWithPendingCredential()
        val credential = issuedCredentialFor(document)

        val result = completerOver(
            store = store,
            deferredStatus = HttpStatusCode.OK,
            deferredBody = """{"credentials":[{"credential":"$credential"}]}""",
        ).complete(document)

        assertIs<DeferredCollection.Issued>(result)

        // The document is now an ordinary issued one, read back the way the UI reads it.
        val read = MultipazWalletEngine(store).getAllDocumentsWithDetails(locale = "en").single()
        assertEquals(WalletDocumentIssuanceState.Issued, read.issuanceState)
        assertNotNull(read.issuedAt)
        assertEquals(1, read.credentialsCount)

        // And the handle is cleared, so nothing polls for it again.
        assertNull(assertNotNull(document.eudiMetadata).deferredTransactionId)
        assertTrue(document.getPendingCredentials().isEmpty())
    }

    @Test
    fun a_collected_pid_whose_signer_is_not_a_recognised_pid_provider_is_not_stored() = runTest {
        val store = storeOver(EphemeralStorage())
        val document = store.parkWithPendingCredential()
        val credential = issuedCredentialFor(document)

        val result = completerOver(
            store = store,
            deferredStatus = HttpStatusCode.OK,
            deferredBody = """{"credentials":[{"credential":"$credential"}]}""",
            issuerTrust = { _, _ -> TrustVerdict.NOT_TRUSTED },
        ).complete(document)

        // Android's deferred `IssuerNotTrusted`: the sweep reports it failed and removes the document.
        assertEquals(DeferredCollection.IssuerNotTrusted, result)
        assertTrue(document.getCertifiedCredentials().isEmpty(), "nothing is certified with a refused PID")
        assertNull(assertNotNull(document.eudiMetadata).issuedAt)
    }

    @Test
    fun an_issuer_still_working_on_it_leaves_the_document_exactly_as_it_was() = runTest {
        val storage = EphemeralStorage()
        val store = storeOver(storage)
        val document = store.parkWithPendingCredential()

        val result = completerOver(
            store = store,
            deferredStatus = HttpStatusCode.BadRequest,
            deferredBody = """{"error":"issuance_pending","interval":5}""",
        ).complete(document)

        assertIs<DeferredCollection.StillPending>(result)

        // Nothing may be consumed by a poll that came back empty-handed: the handle must survive for
        // the next attempt, and the credential must stay pending so the issuer's eventual answer still
        // has the key it was minted for.
        val read = MultipazWalletEngine(store).getAllDocumentsWithDetails(locale = "en").single()
        assertEquals(WalletDocumentIssuanceState.Pending, read.issuanceState)
        assertEquals("txn-abc-123", assertNotNull(document.eudiMetadata).deferredTransactionId)
        assertEquals(1, document.getPendingCredentials().size)
    }

    @Test
    fun a_document_whose_dpop_key_is_gone_reports_expired_rather_than_failing_obscurely() = runTest {
        val storage = EphemeralStorage()
        val store = storeOver(storage)
        val document = store.parkWithPendingCredential()
        store.keySecureArea.deleteKey(dpopAlias)

        val result = completerOver(
            store = store,
            deferredStatus = HttpStatusCode.OK,
            deferredBody = """{"credentials":[]}""",
        ).complete(document)

        // Nothing can be signed without that key, so the honest answer is "authorize it again".
        assertIs<DeferredCollection.AuthorizationExpired>(result)
        assertEquals("txn-abc-123", assertNotNull(document.eudiMetadata).deferredTransactionId)
    }

    @Test
    fun a_document_that_is_not_waiting_on_anything_is_left_alone() = runTest {
        val storage = EphemeralStorage()
        val store = storeOver(storage)
        val document = store.documentStore.createDocument(
            displayName = "PID",
            metadata = EudiDocumentMetadata.create(
                documentManagerId = store.documentManagerId,
                format = StoredDocumentFormat.MsoMdoc(docType),
                credentialPolicy = policy,
            ),
        )

        val result = completerOver(
            store = store,
            deferredStatus = HttpStatusCode.OK,
            deferredBody = """{"credentials":[]}""",
        ).complete(document)

        assertIs<DeferredCollection.Failed>(result)
    }

    private companion object {
        val jsonHeaders = headersOf("Content-Type", listOf("application/json"))
    }

    // ── An issuer that grants no refresh token ────────────────────────────────────────────────────

    private fun resumeFor(expiresIn: kotlin.time.Duration) = DeferredResume(
        accessToken = "the-sessions-access-token",
        dpopKeyAlias = dpopAlias,
        expiresAt = Clock.System.now() + expiresIn,
    )

    @Test
    fun without_a_refresh_token_the_document_is_collected_with_the_sessions_access_token() = runTest {
        val store = storeOver(EphemeralStorage())
        val parked = store.parkWithPendingCredential(refreshToken = null, resume = resumeFor(5.minutes))
        val sent = mutableListOf<Sent>()
        val credential = issuedCredentialFor(parked)

        val result = completerOver(
            store,
            HttpStatusCode.OK,
            """{"credentials":[{"credential":"$credential"}]}""",
            sent,
        ).complete(parked)

        assertIs<DeferredCollection.Issued>(result)
        // Nothing refreshed — there was nothing to refresh with — and the stored token was presented.
        assertTrue(sent.none { it.url == tokenEndpoint }, "no refresh was attempted")
        assertEquals("DPoP the-sessions-access-token", sent.single { it.url == deferredEndpoint }.authorization)
        // The token does not outlive the wait: cleared with the handle.
        val reread = store.documentStore.lookupDocument(parked.identifier)!!.eudiMetadata!!
        assertNull(reread.deferredTransactionId)
        assertNull(reread.deferredResume)
    }

    @Test
    fun an_expired_access_token_without_a_refresh_token_is_reported_expired_without_asking() = runTest {
        val store = storeOver(EphemeralStorage())
        val parked = store.parkWithPendingCredential(refreshToken = null, resume = resumeFor((-1).minutes))
        val sent = mutableListOf<Sent>()

        val result = completerOver(store, HttpStatusCode.OK, "{}", sent).complete(parked)

        assertIs<DeferredCollection.AuthorizationExpired>(result)
        assertTrue(sent.isEmpty(), "the issuer was not asked")
    }

    @Test
    fun a_document_with_neither_a_refresh_token_nor_an_access_token_is_reported_expired() = runTest {
        // Exactly the state Plaut's parked documents were in before the access token was kept.
        val store = storeOver(EphemeralStorage())
        val parked = store.parkWithPendingCredential(refreshToken = null, resume = null)
        val sent = mutableListOf<Sent>()

        val result = completerOver(store, HttpStatusCode.OK, "{}", sent).complete(parked)

        assertIs<DeferredCollection.AuthorizationExpired>(result)
        assertTrue(sent.isEmpty())
    }

    // ── An issuer that grants a refresh token too (Plaut's, from 2026-09-30, rotating it) ─────────────────

    @Test
    fun with_a_refresh_token_a_live_access_token_is_still_used_first_and_nothing_is_refreshed() = runTest {
        val store = storeOver(EphemeralStorage())
        val parked = store.parkWithPendingCredential(resume = resumeFor(5.minutes))
        val sent = mutableListOf<Sent>()
        val credential = issuedCredentialFor(parked)

        val result = completerOver(store, HttpStatusCode.OK, """{"credentials":[{"credential":"$credential"}]}""", sent)
            .complete(parked)

        assertIs<DeferredCollection.Issued>(result)
        // Android's order: the session's own token while it lives. A refresh here is what lost Plaut's
        // deferred PIDs once its server began rotating refresh tokens.
        assertTrue(sent.none { it.url == tokenEndpoint }, "no refresh while the access token lives")
        assertEquals("DPoP the-sessions-access-token", sent.single { it.url == deferredEndpoint }.authorization)
    }

    @Test
    fun an_expired_access_token_falls_back_to_the_refresh_token() = runTest {
        val store = storeOver(EphemeralStorage())
        val parked = store.parkWithPendingCredential(resume = resumeFor((-1).minutes))
        val sent = mutableListOf<Sent>()
        val credential = issuedCredentialFor(parked)

        val result = completerOver(store, HttpStatusCode.OK, """{"credentials":[{"credential":"$credential"}]}""", sent)
            .complete(parked)

        assertIs<DeferredCollection.Issued>(result)
        assertEquals(1, sent.count { it.url == tokenEndpoint })
        assertEquals("DPoP the-token", sent.single { it.url == deferredEndpoint }.authorization)
    }

    @Test
    fun a_refused_access_token_falls_back_to_the_refresh_token_instead_of_giving_up() = runTest {
        val store = storeOver(EphemeralStorage())
        val parked = store.parkWithPendingCredential(resume = resumeFor(5.minutes))
        val sent = mutableListOf<Sent>()
        val credential = issuedCredentialFor(parked)

        val result = completerOver(
            store,
            HttpStatusCode.OK,
            """{"credentials":[{"credential":"$credential"}]}""",
            sent,
            refusedToken = "the-sessions-access-token",
        ).complete(parked)

        // Plaut's issuer once refused its own token ~40 s in; that must not cost a document that can
        // still refresh.
        assertIs<DeferredCollection.Issued>(result)
        assertEquals(
            listOf("DPoP the-sessions-access-token", "DPoP the-token"),
            sent.filter { it.url == deferredEndpoint }.map { it.authorization },
        )
    }

    @Test
    fun a_refresh_token_the_server_rotated_is_kept_for_the_next_poll() = runTest {
        val store = storeOver(EphemeralStorage())
        val parked = store.parkWithPendingCredential()

        val result = completerOver(
            store,
            HttpStatusCode.BadRequest,
            """{"error":"issuance_pending","interval":5}""",
            tokenBody = """{"access_token":"the-token","refresh_token":"the-rotated-refresh-token"}""",
        ).complete(parked)

        assertIs<DeferredCollection.StillPending>(result)
        // The one presented is spent on a rotating server: presenting it on the next poll is refused, and
        // that refusal used to delete the document as "can never be collected".
        val stored = store.documentStore.lookupDocument(parked.identifier)!!.authorizationData!!
            .openID4VciAuthorization()
        assertEquals("the-rotated-refresh-token", assertNotNull(stored).refreshToken)
        assertEquals(dpopAlias, stored.dpopKeyAlias, "only the refresh token changes")
    }

    @Test
    fun each_collected_credential_is_certified_onto_the_key_it_was_issued_for() = runTest {
        val store = storeOver(EphemeralStorage())
        val document = store.parkWithPendingCredential(pendingCredentials = 3)
        val waiting = document.getPendingCredentials().map { it as MdocCredential }
        // The issuer answers in the order the proofs were sent when the request was deferred, which need
        // not be the order the store lists the waiting credentials in. Reversed is one such order.
        val answered = waiting.reversed().map { issuedCredentialFor(it) }

        val result = completerOver(
            store = store,
            deferredStatus = HttpStatusCode.OK,
            deferredBody = """{"credentials":[${answered.joinToString { """{"credential":"$it"}""" }}]}""",
        ).complete(document)

        assertIs<DeferredCollection.Issued>(result)
        val certified = store.documentStore.lookupDocument(document.identifier)!!
            .getCertifiedCredentials().map { it as MdocCredential }
        assertEquals(3, certified.size)
        certified.forEach { credential ->
            assertEquals(
                credential.secureArea.getKeyInfo(credential.alias).publicKey,
                credential.mso.deviceKey,
                "credential ${credential.identifier} holds a key other than the one its data is bound to",
            )
        }
    }

    // ---- the History row ----------------------------------------------------------------------------
    //
    // wallet-core's `deferredResolutionEntry`: a collection that ended brings the credential's
    // "awaiting" row up to date — the same row, so the History does not grow a second one.

    private suspend fun MultipazWalletStore.recordAwaiting(document: Document) = recordTransaction(
        IosTransactionRecord.Issuance(
            id = deferredRowId(document.identifier),
            timeEpochMillis = (Clock.System.now() - 5.minutes).toEpochMilliseconds(),
            completed = false,
            reason = REASON_ISSUANCE_DEFERRED,
            requestedCount = 1,
            issuedCount = 0,
            userTriggered = true,
            issuer = IssuerPartyRecord(name = "Test PID Provider", type = "PIDProvider"),
        )
    )

    @Test
    fun a_collected_credential_completes_its_awaiting_row() = runTest {
        val store = storeOver(EphemeralStorage())
        val document = store.parkWithPendingCredential()
        store.recordAwaiting(document)
        val credential = issuedCredentialFor(document)

        completerOver(
            store = store,
            deferredStatus = HttpStatusCode.OK,
            deferredBody = """{"credentials":[{"credential":"$credential"}]}""",
        ).complete(document)

        val row = assertIs<IosTransactionRecord.Issuance>(store.transactionRecord(deferredRowId(document.identifier)))
        assertTrue(row.completed)
        assertEquals(1, row.issuedCount)
        assertEquals(listOf(docType), row.credentialIdentifiers)
        // Who issued it, and whether the user asked, are the awaiting row's.
        assertEquals("Test PID Provider", row.issuer.name)
        assertEquals(true, row.userTriggered)
        assertEquals(1, store.transactionLogs().size)
    }

    @Test
    fun an_issuer_still_working_on_it_leaves_the_row_awaiting() = runTest {
        val store = storeOver(EphemeralStorage())
        val document = store.parkWithPendingCredential()
        store.recordAwaiting(document)

        completerOver(
            store = store,
            deferredStatus = HttpStatusCode.BadRequest,
            deferredBody = """{"error":"issuance_pending","interval":5}""",
        ).complete(document)

        val row = assertIs<IosTransactionRecord.Issuance>(store.transactionRecord(deferredRowId(document.identifier)))
        assertEquals(false, row.completed)
        assertEquals(REASON_ISSUANCE_DEFERRED, row.reason)
    }

    @Test
    fun a_collection_that_can_never_succeed_closes_the_row_as_not_completed() = runTest {
        val store = storeOver(EphemeralStorage())
        val document = store.parkWithPendingCredential()
        store.recordAwaiting(document)
        val credential = issuedCredentialFor(document)

        completerOver(
            store = store,
            deferredStatus = HttpStatusCode.OK,
            deferredBody = """{"credentials":[{"credential":"$credential"}]}""",
            issuerTrust = { _, _ -> TrustVerdict.NOT_TRUSTED },
        ).complete(document)

        val row = assertIs<IosTransactionRecord.Issuance>(store.transactionRecord(deferredRowId(document.identifier)))
        assertEquals(false, row.completed)
        assertEquals("$REASON_DEFERRED_COLLECTION_FAILED: the issuer is not trusted", row.reason)
        assertEquals(0, row.issuedCount)
    }

    @Test
    fun a_credential_parked_before_rows_were_kept_still_gets_one_when_collected() = runTest {
        val store = storeOver(EphemeralStorage())
        val document = store.parkWithPendingCredential()
        val credential = issuedCredentialFor(document)

        completerOver(
            store = store,
            deferredStatus = HttpStatusCode.OK,
            deferredBody = """{"credentials":[{"credential":"$credential"}]}""",
        ).complete(document)

        val issuance = assertIs<TransactionLogDomain.CredentialIssuance>(store.transactionLogs().single())
        assertEquals(TransactionResultDomain.Completed, issuance.result)
        // Nothing recorded whether the user asked; the fixture's issuer publishes no display name.
        assertNull(issuance.details.isUserTriggered)
    }

    @Test
    fun a_session_that_deferred_everything_still_names_its_issuer_when_collected() = runTest {
        // No awaiting row exists then, so the document's own record of its issuance names the issuer —
        // as wallet-core reads the registration it stored with the deferred document.
        val store = storeOver(EphemeralStorage())
        val document = store.parkWithPendingCredential(
            issuerParty = IssuerPartyRecord(name = "Fixture Issuer s.r.o.", type = "PIDProvider"),
            userTriggered = true,
        )
        val credential = issuedCredentialFor(document)

        completerOver(
            store = store,
            deferredStatus = HttpStatusCode.OK,
            deferredBody = """{"credentials":[{"credential":"$credential"}]}""",
        ).complete(document)

        val issuance = assertIs<TransactionLogDomain.CredentialIssuance>(store.transactionLogs().single())
        assertEquals("Fixture Issuer s.r.o.", issuance.details.issuer.name?.text)
        assertEquals("PIDProvider", issuance.details.issuerType)
        assertEquals(true, issuance.details.isUserTriggered)
    }
}
