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

// OpenID4VP `transaction_data` on iOS: what a signing approval looks like on the consent screen, and the
// `qesApproval` the wallet answers it with. The digests are the CSC bindings wallet-core implements on
// Android; the expected values were computed independently (Python hashlib over the same strings), so a
// mistake in which bytes are hashed, or how the result is encoded, shows up here rather than at a QTSP.
package eu.europa.ec.shared.wallet.multipaz

import eu.europa.ec.corelogic.model.DocumentChecksumDomain
import eu.europa.ec.corelogic.model.PresentationTransactionDataDomain
import eu.europa.ec.corelogic.model.QesDocumentDigestDomain
import eu.europa.ec.shared.wallet.document.WalletCredentialPolicy
import eu.europa.ec.shared.wallet.multipaz.harness.samplePidElements
import eu.europa.ec.shared.wallet.multipaz.harness.seedMdocDocument
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.multipaz.cbor.Bstr
import org.multipaz.credential.Credential
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.openid.dcql.DcqlCredentialQueryException
import org.multipaz.openid.dcql.DcqlQuery
import org.multipaz.crypto.Algorithm
import org.multipaz.presentment.SimplePresentmentSource
import org.multipaz.presentment.TransactionData
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.util.toBase64Url
import org.multipaz.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

private const val PID_DOC_TYPE = "eu.europa.ec.eudi.pid.1"

class IosQesTransactionDataTest {

    private val repository = DocumentTypeRepository().apply { walletTransactionTypes.forEach(::addTransactionType) }

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
        documentTypeRepository = repository,
        domainsMdocSignature = listOf(store.documentManagerId),
        domainsKeyBoundSdJwt = listOf(store.documentManagerId),
    )

    private val pidQuery: JsonObject = Json.decodeFromString(
        """
        {
          "credentials": [{
            "id": "pid",
            "format": "mso_mdoc",
            "meta": { "doctype_value": "$PID_DOC_TYPE" },
            "claims": [{"path":["$PID_DOC_TYPE","family_name"]}]
          }]
        }
        """
    )

    private fun transactionData(vararg base64UrlJson: String) = repository.parseJsonTransactions(base64UrlJson.toList())

    /** The one transaction data item [base64UrlJson] holds for the `pid` query, as the QES types hold it. */
    @Suppress("UNCHECKED_CAST")
    private fun pidTransaction(base64UrlJson: String): TransactionData<String> =
        transactionData(base64UrlJson).getValue("pid").single() as TransactionData<String>

    private fun json(text: String) = text.trimIndent().replace("\n", "").encodeToByteArray().toBase64Url()

    /** The PID's credential, as the presentment would answer with it. */
    private suspend fun pidCredential(): Credential =
        DcqlQuery.fromJson(pidQuery).execute(
            presentmentSource = source(walletWithPid()),
            transactionDataMap = emptyMap(),
        ).credentialSets.first().options.first().members.first().matches.first().credential

    @Test
    fun an_approval_reaches_the_consent_view_on_the_credential_it_names() = runTest {
        val data = DcqlQuery.fromJson(pidQuery).execute(
            presentmentSource = source(walletWithPid()),
            transactionDataMap = transactionData(APPROVAL_SHA256),
        )

        val document = data.toPresentmentRequest(requesterName = "Signer", requesterIsTrusted = true)
            .combinations.single().documents.single()

        assertEquals(
            listOf(
                PresentationTransactionDataDomain.QesApproval(
                    displayName = "QES approval",
                    credentialIds = listOf("pid"),
                    credentialId = "signing-credential",
                    signatureQualifier = null,
                    numSignatures = 1,
                    hashAlgorithmOid = "2.16.840.1.101.3.4.2.1",
                    documentDigests = listOf(
                        QesDocumentDigestDomain(
                            label = "contract.pdf",
                            hash = "AQID",
                            hashType = "dtbsr",
                            signedProperties = null,
                            href = null,
                            checksum = null,
                            oneTimePassword = null,
                        )
                    ),
                )
            ),
            document.transactionData,
        )
    }

    @Test
    fun the_sd_jwt_approval_is_the_base64_digest_of_the_data_as_received() = runTest {
        val credential = pidCredential()

        // hashAlgorithmOID SHA-256, then SHA-384: the claim follows the request's algorithm.
        assertEquals(
            mapOf("qesApproval" to JsonPrimitive("I8iZ9UPuv+sPRAxLA7samXUbbwJ94LCsygaiE+QnmBI=")),
            IosQesApprovalTransactionType.generateSdJwtResponseClaims(
                pidTransaction(APPROVAL_SHA256),
                credential,
                null,
            ),
        )
        assertEquals(
            mapOf("qesApproval" to JsonPrimitive("Yxdu44AdlruIvoXGBcRVM5UK8T3c1BfoFAoKnLjF7rWr9YHXJNXNDy5J2obkGx7C")),
            IosQesApprovalTransactionType.generateSdJwtResponseClaims(
                pidTransaction(APPROVAL_SHA384),
                credential,
                null,
            ),
        )
        // multipaz puts a type's claims under one claim of its own, so this is the object the approval is in.
        assertEquals("org.cloudsignatureconsortium.dm.1", IosQesApprovalTransactionType.kbJwtResponseClaimName)
    }

    @Test
    fun the_mdoc_approval_is_the_sha256_of_the_decoded_data() = runTest {
        val element = IosQesApprovalTransactionType.generateMdocResponseElements(
            pidTransaction(APPROVAL_SHA256),
            pidCredential(),
            null,
        ).getValue("qesApproval") as Bstr

        assertEquals("5ef6ed9be1e4966f061f827f0cc43937d5a414bf231027d3b9b87b26ff42463d", element.value.toHex())
        assertEquals("org.cloudsignatureconsortium.dm.1", IosQesApprovalTransactionType.openId4VpMdocResponseNamespace)
    }

    @Test
    fun an_approval_with_an_unknown_field_cannot_be_answered() = runTest {
        val extended = json(
            """{"type":"$QES_APPROVAL_TYPE","credential_ids":["pid"],"credentialID":"c","numSignatures":1,
            "documentDigests":[{"hash":"AQID"}],"hashAlgorithmOID":"2.16.840.1.101.3.4.2.1","extra":true}"""
        )

        assertFailsWith<DcqlCredentialQueryException> {
            DcqlQuery.fromJson(pidQuery).execute(
                presentmentSource = source(walletWithPid()),
                transactionDataMap = transactionData(extended),
            )
        }
    }

    @Test
    fun a_qes_request_applies_to_sd_jwt_only_and_shows_its_document_reference() = runTest {
        val request = json(
            """{"type":"$QES_REQUEST_TYPE","credential_ids":["pid"],"signatureRequests":[{
            "signatureQualifier":"eu_eidas_qes","signAlgo":"1.2.840.10045.4.3.2","label":"Contract",
            "href":"https://documents.example.org/contract.pdf","checksum":{"value":"Y2hlY2s=",
            "algorithmOID":"2.16.840.1.101.3.4.2.1"},"access":{"type":"OTP","oneTimePassword":"000123"}}]}"""
        )
        val data = pidTransaction(request)

        assertFalse(IosQesRequestTransactionType.isApplicable(data, pidCredential()))
        val qes = data.toPresentationTransactionDataDomain() as PresentationTransactionDataDomain.Qes
        assertEquals("QES request", qes.displayName)
        val signature = qes.signatureRequests.single()
        assertEquals("https://documents.example.org/contract.pdf", signature.href)
        assertEquals(DocumentChecksumDomain("Y2hlY2s=", "2.16.840.1.101.3.4.2.1"), signature.checksum)
        assertEquals("000123", signature.oneTimePassword)
    }

    @Test
    fun a_signature_request_with_both_a_document_and_a_link_is_unavailable() = runTest {
        val both = json(
            """{"type":"$QES_REQUEST_TYPE","credential_ids":["pid"],"signatureRequests":[{
            "signatureQualifier":"eu_eidas_qes","signAlgo":"1.2.840.10045.4.3.2",
            "document":"JVBERi0=","href":"https://documents.example.org/contract.pdf"}]}"""
        )

        assertEquals(
            PresentationTransactionDataDomain.Unavailable,
            transactionData(both).getValue("pid").single().toPresentationTransactionDataDomain(),
        )
    }

    @Test
    fun the_hashes_use_the_first_algorithm_the_wallet_supports() {
        val named = json(
            """{"type":"$QES_APPROVAL_TYPE","credential_ids":["pid"],
            "transaction_data_hashes_alg":["md5","sha-384"],"credentialID":"c","numSignatures":1,
            "documentDigests":[{"hash":"AQID"}],"hashAlgorithmOID":"2.16.840.1.101.3.4.2.1"}"""
        )

        assertEquals(listOf(Algorithm.SHA384), pidTransaction(named).hashAlgorithms)
        assertEquals(null, pidTransaction(APPROVAL_SHA256).hashAlgorithms)
    }

    @Test
    fun hashes_named_only_in_algorithms_the_wallet_lacks_are_refused() {
        val unsupported = json(
            """{"type":"$QES_APPROVAL_TYPE","credential_ids":["pid"],
            "transaction_data_hashes_alg":["md5"],"credentialID":"c","numSignatures":1,
            "documentDigests":[{"hash":"AQID"}],"hashAlgorithmOID":"2.16.840.1.101.3.4.2.1"}"""
        )

        assertFailsWith<IllegalArgumentException> { transactionData(unsupported) }
    }

    private companion object {
        /** base64url of the approval JSON naming SHA-256 in `hashAlgorithmOID`. */
        const val APPROVAL_SHA256 =
            "eyJ0eXBlIjoiaHR0cHM6Ly9jbG91ZHNpZ25hdHVyZWNvbnNvcnRpdW0ub3JnLzIwMjUvcWVzLWFwcHJvdmFsIiwiY3JlZGVudGlhbF9pZHMiOlsicGlkIl0sImNyZWRlbnRpYWxJRCI6InNpZ25pbmctY3JlZGVudGlhbCIsIm51bVNpZ25hdHVyZXMiOjEsImRvY3VtZW50RGlnZXN0cyI6W3sibGFiZWwiOiJjb250cmFjdC5wZGYiLCJoYXNoIjoiQVFJRCJ9XSwiaGFzaEFsZ29yaXRobU9JRCI6IjIuMTYuODQwLjEuMTAxLjMuNC4yLjEifQ"

        /** The same approval naming SHA-384. */
        const val APPROVAL_SHA384 =
            "eyJ0eXBlIjoiaHR0cHM6Ly9jbG91ZHNpZ25hdHVyZWNvbnNvcnRpdW0ub3JnLzIwMjUvcWVzLWFwcHJvdmFsIiwiY3JlZGVudGlhbF9pZHMiOlsicGlkIl0sImNyZWRlbnRpYWxJRCI6InNpZ25pbmctY3JlZGVudGlhbCIsIm51bVNpZ25hdHVyZXMiOjEsImRvY3VtZW50RGlnZXN0cyI6W3sibGFiZWwiOiJjb250cmFjdC5wZGYiLCJoYXNoIjoiQVFJRCJ9XSwiaGFzaEFsZ29yaXRobU9JRCI6IjIuMTYuODQwLjEuMTAxLjMuNC4yLjIifQ"
    }
}
