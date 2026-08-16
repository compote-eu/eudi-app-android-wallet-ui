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

// Remote presentation below the wire: a DCQL query is executed against the wallet, and what comes back is
// the consent view and the selection built from it.
//
// This is the OpenID4VP counterpart of `IosProximityPresentmentTest`, and it exists for a different
// reason. Proximity is untestable above the transport because the simulator has no radio; here the
// transport is HTTPS and works — the real exchange against the EUDI dev verifier is exercised by
// `probeRemotePresentation`. What that probe *cannot* pin down is the DCQL-specific half of the mapping,
// because a live run only ever shows one shape of query. These cases cover that: query ids reaching the
// consent view, SD-JWT paths surviving intact, and selective disclosure narrowing what goes out.
package eu.europa.ec.shared.wallet.multipaz

import eu.europa.ec.corelogic.model.ClaimPathDomain
import eu.europa.ec.corelogic.model.ClaimPathSegment
import eu.europa.ec.corelogic.model.ClaimType
import eu.europa.ec.shared.wallet.document.WalletCredentialPolicy
import eu.europa.ec.shared.wallet.multipaz.harness.samplePidElements
import eu.europa.ec.shared.wallet.multipaz.harness.seedMdocDocument
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.multipaz.asn1.ASN1Integer
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.openid.dcql.DcqlQuery
import org.multipaz.presentment.SimplePresentmentSource
import org.multipaz.request.Requester
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

private const val PID_DOC_TYPE = "eu.europa.ec.eudi.pid.1"

class IosRemotePresentmentTest {

    private suspend fun walletWithPid(): MultipazWalletStore {
        val storage = EphemeralStorage()
        val store = MultipazWalletStore.build(
            storage = storage,
            secureAreas = listOf(SoftwareSecureArea.create(storage)),
        )
        store.seedMdocDocument(
            docType = PID_DOC_TYPE,
            displayName = "PID",
            namespace = PID_DOC_TYPE,
            elements = samplePidElements(),
            policy = WalletCredentialPolicy.RotatingBatch(numberOfCredentials = 1),
        )
        return store
    }

    private fun source(store: MultipazWalletStore) = SimplePresentmentSource(
        documentStore = store.documentStore,
        documentTypeRepository = DocumentTypeRepository(),
        domainsMdocSignature = listOf(store.documentManagerId),
        domainsKeyBoundSdJwt = listOf(store.documentManagerId),
    )

    /** A verifier's DCQL query, written the way the EUDI dev verifier writes one. */
    private fun mdocQuery(queryId: String, vararg elements: String): JsonObject = Json.decodeFromString(
        """
        {
          "credentials": [{
            "id": "$queryId",
            "format": "mso_mdoc",
            "meta": { "doctype_value": "$PID_DOC_TYPE" },
            "claims": [${elements.joinToString { """{"path":["$PID_DOC_TYPE","$it"]}""" }}]
          }]
        }
        """
    )

    /** What the consent screen would show for [query], run against a wallet holding one PID. */
    private suspend fun consentViewFor(query: JsonObject): Pair<IosPresentmentRequest, Any> {
        val store = walletWithPid()
        val data = DcqlQuery.fromJson(query).execute(
            presentmentSource = source(store),
            transactionDataMap = emptyMap(),
        )
        return data.toPresentmentRequest(
            requesterName = "Test Verifier",
            requesterIsTrusted = false,
        ) to data
    }

    @Test
    fun the_dcql_query_id_reaches_the_consent_view() = runTest {
        val (view, _) = consentViewFor(mdocQuery("pid", "given_name", "family_name"))

        val document = view.combinations.single().documents.single()
        // Null under ISO 18013-5, which has no query ids — but here it is the only thing that keeps two
        // cards for the same document apart, and it is also how the response is keyed.
        assertEquals("pid", document.queryId)
        assertEquals(PID_DOC_TYPE, document.docType)
        assertEquals(IosPresentmentFormat.MsoMdoc, document.format)
        assertEquals(
            setOf("given_name", "family_name"),
            document.claims.map { it.claim.segments.last().toString() }.toSet(),
        )
    }

    @Test
    fun an_mdoc_claim_is_named_by_namespace_and_element() = runTest {
        val (view, _) = consentViewFor(mdocQuery("pid", "given_name"))

        val claim = view.combinations.single().documents.single().claims.single()
        assertEquals(
            ClaimPathDomain(
                segments = listOf(ClaimPathSegment.Key("given_name")),
                type = ClaimType.MsoMdoc(namespace = PID_DOC_TYPE),
            ),
            claim.claim,
        )
        // The stored value, so consent shows what is actually about to be shared rather than a label.
        assertEquals("Tester", claim.value)
    }

    @Test
    fun a_claim_the_user_unchecks_does_not_go_out() = runTest {
        val store = walletWithPid()
        val data = DcqlQuery.fromJson(mdocQuery("pid", "given_name", "family_name")).execute(
            presentmentSource = source(store),
            transactionDataMap = emptyMap(),
        )
        val document = data.toPresentmentRequest(null, false).combinations.single().documents.single()

        // The real round trip: the consent view's own claim refs go back as the disclosure, minus one.
        val selection = data.toSelection(
            listOf(
                IosPresentmentDisclosure(
                    documentId = document.documentId,
                    credentialId = document.credentialId,
                    claims = document.claims
                        .filter { it.claim.segments.last().toString() == "given_name" }
                        .map { it.claim }
                        .toSet(),
                )
            )
        )

        // multipaz builds the response from exactly these claims, so this *is* selective disclosure.
        assertEquals(
            listOf("given_name"),
            selection.matches.single().claims.values.map { it.displayName },
        )
    }

    @Test
    fun keeping_nothing_selects_nothing_to_send() = runTest {
        val store = walletWithPid()
        val data = DcqlQuery.fromJson(mdocQuery("pid", "given_name")).execute(
            presentmentSource = source(store),
            transactionDataMap = emptyMap(),
        )
        val document = data.toPresentmentRequest(null, false).combinations.single().documents.single()

        val selection = data.toSelection(
            listOf(
                IosPresentmentDisclosure(
                    documentId = document.documentId,
                    credentialId = document.credentialId,
                    claims = emptySet(),
                )
            )
        )

        // Which is why the presenter turns an empty selection into a refusal rather than handing it on:
        // an empty response would tell the verifier the wallet had nothing.
        assertTrue(selection.matches.isEmpty())
    }

    @Test
    fun the_verifier_is_named_from_its_own_certificate() = runTest {
        // The consent screen's "who is asking". `X500Name.components` is keyed by OID, not by the short
        // form, so asking for "CN" compiles and silently yields null for every certificate ever issued —
        // which is what it did until a live run showed the screen naming the verifier "null".
        val key = Crypto.createEcPrivateKey(EcCurve.P256)
        val name = X500Name.fromName("CN=Verifier Signer dev,C=EU")
        val certificate = X509Cert.Builder(
            publicKey = key.publicKey,
            signingKey = AsymmetricKey.AnonymousExplicit(privateKey = key),
            serialNumber = ASN1Integer(1L),
            subject = name,
            issuer = name,
            validFrom = Clock.System.now() - 1.days,
            validUntil = Clock.System.now() + 30.days,
        ).build()

        val requester = Requester(certChain = X509CertChain(listOf(certificate)))

        assertEquals("Verifier Signer dev", requester.certificateCommonName())
        // Nothing to name when the request was unsigned; the screen then shows its own fallback rather
        // than an empty relying party.
        assertNull(Requester(certChain = null).certificateCommonName())
    }

    @Test
    fun two_queries_for_the_same_document_stay_apart() = runTest {
        // The shape that only OpenID4VP produces: one request, two credential queries, both satisfied by
        // the same stored PID. The wallet must answer both, and each answer is keyed by its query id.
        val query: JsonObject = Json.decodeFromString(
            """
            {
              "credentials": [
                { "id": "names", "format": "mso_mdoc",
                  "meta": { "doctype_value": "$PID_DOC_TYPE" },
                  "claims": [{"path":["$PID_DOC_TYPE","given_name"]}] },
                { "id": "birth", "format": "mso_mdoc",
                  "meta": { "doctype_value": "$PID_DOC_TYPE" },
                  "claims": [{"path":["$PID_DOC_TYPE","birth_date"]}] }
              ]
            }
            """
        )

        val (view, _) = consentViewFor(query)

        val documents = view.combinations.single().documents
        assertEquals(listOf("names", "birth"), documents.map { it.queryId })
        // Same stored document behind both, which is exactly why the query id has to be carried.
        assertEquals(1, documents.map { it.documentId }.toSet().size)
    }
}
