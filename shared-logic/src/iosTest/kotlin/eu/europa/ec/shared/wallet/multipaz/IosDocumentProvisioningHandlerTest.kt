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
import kotlinx.coroutines.test.runTest
import org.multipaz.crypto.Algorithm
import org.multipaz.provisioning.CredentialFormat
import org.multipaz.provisioning.CredentialMetadata
import org.multipaz.provisioning.Display
import org.multipaz.provisioning.KeyBindingType
import org.multipaz.provisioning.ProvisioningMetadata
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a provisioned document must look like for the rest of this wallet to see it.
 *
 * The case that matters most is the first one. `MultipazDocumentReader` ignores documents whose metadata
 * it cannot interpret — that is how it avoids reading another app's documents out of a shared store — so
 * a document provisioned with multipaz's own metadata would be created, certified, and then simply
 * absent from the Documents list, with nothing anywhere reporting a failure. These tests read the
 * document back through the real `MultipazWalletEngine` rather than inspecting the metadata directly,
 * because "the reader can see it" is the actual requirement.
 */
class IosDocumentProvisioningHandlerTest {

    private suspend fun store(
        storage: Storage = EphemeralStorage(),
    ): MultipazWalletStore = MultipazWalletStore.build(
        storage = storage,
        secureAreas = listOf(SoftwareSecureArea.create(storage)),
    )

    private fun credentialMetadata(
        format: CredentialFormat = CredentialFormat.Mdoc("eu.europa.ec.eudi.pid.1"),
        name: String = "PID",
        maxBatchSize: Int = 20,
    ) = CredentialMetadata(
        display = Display(text = name, logo = null),
        format = format,
        keyBindingType = KeyBindingType.Attestation(algorithm = Algorithm.ESP256),
        maxBatchSize = maxBatchSize,
    )

    private fun issuerMetadata(name: String = "Digital Credentials Issuer") = ProvisioningMetadata(
        url = "https://issuer.test",
        display = Display(text = name, logo = null),
        credentials = emptyMap(),
    )

    @Test
    fun a_provisioned_document_is_visible_to_the_reader() = runTest {
        val store = store()
        val handler = IosDocumentProvisioningHandler(store)

        handler.createDocument(
            credentialMetadata = credentialMetadata(name = "PID (issued)"),
            issuerMetadata = issuerMetadata(),
            documentAuthorizationData = null,
        )

        val documents = MultipazWalletEngine(store).getAllDocumentsWithDetails(locale = "en")
        assertEquals(1, documents.size, "the reader could not see the provisioned document")
        assertEquals("PID (issued)", documents.single().name)
        assertEquals("eu.europa.ec.eudi.pid.1", documents.single().formatType)
        assertEquals("Digital Credentials Issuer", documents.single().issuerName)
    }

    @Test
    fun an_mdoc_configuration_becomes_an_mdoc_document_and_sd_jwt_becomes_sd_jwt() = runTest {
        val store = store()
        val handler = IosDocumentProvisioningHandler(store)

        handler.createDocument(
            credentialMetadata = credentialMetadata(
                format = CredentialFormat.Mdoc("org.iso.18013.5.1.mDL"),
                name = "mDL",
            ),
            issuerMetadata = issuerMetadata(),
            documentAuthorizationData = null,
        )
        handler.createDocument(
            credentialMetadata = credentialMetadata(
                format = CredentialFormat.SdJwt("urn:eudi:pid:1"),
                name = "PID SD-JWT",
            ),
            issuerMetadata = issuerMetadata(),
            documentAuthorizationData = null,
        )

        val formats = MultipazWalletEngine(store)
            .getAllDocumentsWithDetails(locale = "en")
            .associate { it.name to it.formatType }

        // The format is application metadata — multipaz stores credentials, not documents of a format —
        // so getting it from the issuer's configuration is the only way it can be right.
        assertEquals("org.iso.18013.5.1.mDL", formats["mDL"])
        assertEquals("urn:eudi:pid:1", formats["PID SD-JWT"])
    }

    @Test
    fun the_credential_policy_follows_the_batch_actually_requested() = runTest {
        val store = store()

        IosDocumentProvisioningHandler(store, batchSize = 3).createDocument(
            credentialMetadata = credentialMetadata(name = "batched"),
            issuerMetadata = issuerMetadata(),
            documentAuthorizationData = null,
        )

        val metadata = store.documentStore.listDocuments()
            .single { it.displayName == "batched" }
            .eudiMetadata!!
        val policy = assertIs<WalletCredentialPolicy.RotatingBatch>(metadata.credentialPolicy)
        assertEquals(3, policy.numberOfCredentials)
    }

    @Test
    fun an_issuer_that_allows_only_one_credential_yields_a_one_shot_policy() = runTest {
        val store = store()

        // The issuer's limit wins over what the wallet would like.
        IosDocumentProvisioningHandler(store, batchSize = 3).createDocument(
            credentialMetadata = credentialMetadata(name = "single", maxBatchSize = 1),
            issuerMetadata = issuerMetadata(),
            documentAuthorizationData = null,
        )

        val metadata = store.documentStore.listDocuments()
            .single { it.displayName == "single" }
            .eudiMetadata!!
        assertIs<WalletCredentialPolicy.OnceOnly>(metadata.credentialPolicy)
    }

    @Test
    fun a_document_stays_pending_until_a_credential_is_certified() = runTest {
        val store = store()
        val handler = IosDocumentProvisioningHandler(store)
        val document = handler.createDocument(
            credentialMetadata = credentialMetadata(name = "pending"),
            issuerMetadata = issuerMetadata(),
            documentAuthorizationData = null,
        )

        // multipaz calls updateDocument after each provisioning step; with nothing certified yet the
        // document must not claim to be issued — the Documents list shows those differently.
        handler.updateDocument(document, display = null, documentAuthorizationData = null)

        assertNull(document.eudiMetadata!!.issuedAt)
        val read = MultipazWalletEngine(store).getAllDocumentsWithDetails(locale = "en").single()
        assertTrue(
            read.issuanceState != WalletDocumentIssuanceState.Issued,
            "a document with no certified credentials must not read as Issued",
        )
    }
}
