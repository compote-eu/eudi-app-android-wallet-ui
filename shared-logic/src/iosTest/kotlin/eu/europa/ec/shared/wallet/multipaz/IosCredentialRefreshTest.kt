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

// Re-issuance, up to the point where it would talk to an issuer.
//
// The refresh itself needs a live OpenID4VCI issuer and a document this wallet actually provisioned, so
// the network half belongs to a probe run rather than here. What *is* here is the half that decides
// whether a refresh happens at all, and it is the half a user meets: a document that has no stored
// authorization, or whose issuer this build no longer knows, must say so rather than opening a browser
// or failing obscurely. All three refusals are decided from the store's contents, before anything is
// sent.
package eu.europa.ec.shared.wallet.multipaz

import eu.europa.ec.shared.wallet.document.WalletCredentialPolicy
import eu.europa.ec.shared.wallet.multipaz.harness.MDOC_PID_DOC_TYPE
import eu.europa.ec.shared.wallet.multipaz.harness.samplePidElements
import eu.europa.ec.shared.wallet.multipaz.harness.sampleIssuerMetadata
import eu.europa.ec.shared.wallet.multipaz.harness.seedMdocDocument
import kotlinx.coroutines.test.runTest
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class IosCredentialRefreshTest {

    private val issuer = IosVciIssuer(
        issuerUrl = "https://fixture.issuer.invalid",
        clientId = "eudiw-abca",
        redirectUri = "eu.europa.ec.euidi://authorization",
        order = 0,
    )

    private suspend fun walletWith(
        issuerMetadataFor: String? = issuer.issuerUrl,
    ): Pair<MultipazWalletStore, String> {
        val storage = EphemeralStorage()
        val store = MultipazWalletStore.build(
            storage = storage,
            secureAreas = listOf(SoftwareSecureArea.create(storage)),
        )
        val documentId = store.seedMdocDocument(
            docType = MDOC_PID_DOC_TYPE,
            displayName = "PID",
            namespace = MDOC_PID_DOC_TYPE,
            elements = samplePidElements(),
            policy = WalletCredentialPolicy.RotatingBatch(numberOfCredentials = 1),
            issuerMetadata = issuerMetadataFor?.let {
                sampleIssuerMetadata(MDOC_PID_DOC_TYPE).copy(credentialIssuerIdentifier = it)
            },
        )
        return store to documentId
    }

    private fun refresherOver(store: MultipazWalletStore) = IosCredentialIssuer(
        walletEngine = IosWalletEngine(),
        walletStore = { store },
        issuers = listOf(issuer),
    )

    @Test
    fun a_document_the_wallet_no_longer_holds_is_refused_by_name() = runTest {
        val (store, _) = walletWith()

        val progress = refresherOver(store).refreshCredentials("no-such-document")

        // Reachable in practice: the details screen keeps the id it was opened with, and the document
        // can be deleted from another tab in the meantime.
        val failure = assertIs<IosIssuanceProgress.Failure>(progress)
        assertEquals(IosCredentialIssuer.NO_SUCH_DOCUMENT, failure.message)
    }

    @Test
    fun a_document_with_no_stored_authorization_is_refused_before_any_request() = runTest {
        // Exactly the state of a seeded fixture, and of anything provisioned by a build from before
        // multipaz kept authorization data on the document.
        val (store, documentId) = walletWith()

        val progress = refresherOver(store).refreshCredentials(documentId)

        val failure = assertIs<IosIssuanceProgress.Failure>(progress)
        assertEquals(IosCredentialIssuer.NO_STORED_AUTHORIZATION, failure.message)
        // The message has to tell the user what to do instead, because nothing else will.
        assertEquals(true, failure.message.contains("Add it again"))
    }

    @Test
    fun a_document_from_an_issuer_this_build_does_not_know_is_refused() = runTest {
        val (store, documentId) = walletWith(issuerMetadataFor = "https://some-other.issuer.invalid")

        val progress = refresherOver(store).refreshCredentials(documentId)

        // Checked before the authorization data, even though a missing authorization is the commoner
        // absence: this message is a dead end, and the other one tells the user to add the document
        // again — which is only advice if the wallet can still reach that issuer.
        val failure = assertIs<IosIssuanceProgress.Failure>(progress)
        assertEquals(IosCredentialIssuer.UNKNOWN_ISSUER, failure.message)
    }

    @Test
    fun a_document_with_no_issuer_metadata_at_all_is_refused_rather_than_assumed() = runTest {
        val (store, documentId) = walletWith(issuerMetadataFor = null)

        val progress = refresherOver(store).refreshCredentials(documentId)

        // Not "the only configured issuer": a document that never recorded where it came from is not
        // evidence that it came from here.
        val failure = assertIs<IosIssuanceProgress.Failure>(progress)
        assertEquals(IosCredentialIssuer.UNKNOWN_ISSUER, failure.message)
    }
}
