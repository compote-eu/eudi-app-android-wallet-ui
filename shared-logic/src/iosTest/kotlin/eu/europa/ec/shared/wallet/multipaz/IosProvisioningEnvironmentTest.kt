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

// That `IosProvisioningEnvironment` is still everything `ProvisioningModel.launch` asks for.
//
// multipaz builds this context privately — `createCoroutineContext` is `private` and its
// `ProvisioningEnvironment` is `internal` — so ours is a reimplementation against the public
// `BackendEnvironment` seam, and it is what multi-credential issuance runs on. ⛔ The risk this guards is
// a multipaz upgrade asking the environment for a fifth interface: ours would answer null, and
// "PID Combined" would fail on a device with a confusing error rather than here.
//
// 📌 A second case lived here briefly, driving `launch` with an existing document to settle
// multipaz#1950 (it works: the same document comes back and no duplicate is created). It was deleted
// once that was reported — nothing here uses that path, so it pinned a library behaviour we do not
// depend on. See [[multipaz-single-configuration-provisioning]].
package eu.europa.ec.shared.wallet.multipaz

import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.ByteString
import org.multipaz.crypto.Algorithm
import org.multipaz.document.Document
import org.multipaz.provisioning.AuthorizationChallenge
import org.multipaz.provisioning.AuthorizationResponse
import org.multipaz.provisioning.CredentialCertification
import org.multipaz.provisioning.CredentialFormat
import org.multipaz.provisioning.Credentials
import org.multipaz.provisioning.Display
import org.multipaz.provisioning.KeyBindingInfo
import org.multipaz.provisioning.KeyBindingType
import org.multipaz.provisioning.CredentialMetadata
import org.multipaz.provisioning.ProvisioningClient
import org.multipaz.provisioning.ProvisioningMetadata
import org.multipaz.provisioning.ProvisioningModel
import org.multipaz.rpc.handler.RpcAuthClientSession
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IosProvisioningEnvironmentTest {

    private suspend fun store(storage: Storage = EphemeralStorage()): MultipazWalletStore =
        MultipazWalletStore.build(
            storage = storage,
            secureAreas = listOf(SoftwareSecureArea.create(storage)),
        )

    private val credentialMetadata = CredentialMetadata(
        display = Display(text = "PID", logo = null),
        format = CredentialFormat.Mdoc("eu.europa.ec.eudi.pid.1"),
        keyBindingType = KeyBindingType.Attestation(algorithm = Algorithm.ESP256),
        maxBatchSize = 2,
    )

    private val metadata = ProvisioningMetadata(
        url = "https://issuer.test",
        display = Display(text = "Issuer", logo = null),
        credentials = mapOf("pid_mdoc" to credentialMetadata),
    )

    /**
     * A client that answers everything without talking to an issuer.
     *
     * The subject here is `launch` itself — whether an outside caller can build its context and point it
     * at a document — so the protocol is stubbed out entirely.
     */
    private class StubProvisioningClient(
        private val metadata: ProvisioningMetadata,
    ) : ProvisioningClient {
        var sawKeyBinding: KeyBindingInfo? = null
            private set

        override suspend fun getMetadata(): ProvisioningMetadata = metadata
        override suspend fun getAuthorizationChallenges(): List<AuthorizationChallenge> = emptyList()
        override suspend fun authorize(response: AuthorizationResponse) = Unit
        override suspend fun getAuthorizationData(): ByteString? = null
        override suspend fun getKeyBindingChallenge(): String = "c-nonce"

        override suspend fun obtainCredentials(keyInfo: KeyBindingInfo): Credentials {
            sawKeyBinding = keyInfo
            // 🪤 Not an empty list: multipaz's event logger does `credentialData.first()`
            // unconditionally, so a round that certifies nothing dies with `NoSuchElementException`
            // before the document is returned. One certification naming no real pending credential is
            // enough — the certification loop logs "Credential '…' is not found" and moves on, which
            // keeps this test about `launch` rather than about certifying mdoc bytes.
            return Credentials(
                certifications = listOf(
                    CredentialCertification("not-a-pending-credential", ByteString(byteArrayOf(0))),
                ),
                display = null,
            )
        }
    }

    /**
     * The context multipaz builds privately, built here from public API only.
     *
     * This is the whole claim: `BackendEnvironment` is public, and the four interfaces it must vend are
     * the same four objects [ProvisioningModel] is constructed with.
     */
    private fun environment(walletStore: MultipazWalletStore) = IosProvisioningEnvironment(
        httpClient = HttpClient(),
        secureArea = walletStore.keySecureArea,
        clientPreferences = org.multipaz.provisioning.openid4vci.OpenID4VCIClientPreferences(
            clientId = "test-client",
            redirectUrl = "eu.europa.ec.euidi://authorization",
            locales = listOf("en"),
            signingAlgorithms = listOf(Algorithm.ESP256),
        ),
        backend = object : org.multipaz.provisioning.openid4vci.OpenID4VCIBackend {
            override suspend fun getClientId(): String = "test-client"
            override suspend fun createJwtClientAssertion(authorizationServerIdentifier: String) =
                throw UnsupportedOperationException("not reached")

            override suspend fun createJwtWalletAttestation(
                keyAttestation: org.multipaz.securearea.KeyAttestation,
            ): String = throw UnsupportedOperationException("not reached")

            override suspend fun createJwtKeyAttestation(
                credentialKeyAttestations: List<org.multipaz.provisioning.CredentialKeyAttestation>,
                challenge: String,
                userAuthentication: List<String>?,
                keyStorage: List<String>?,
            ): String = "key-attestation"
        },
    )

    private suspend fun model(walletStore: MultipazWalletStore) = ProvisioningModel(
        documentProvisioningHandler = IosDocumentProvisioningHandler(walletStore, batchSize = 2),
        httpClient = HttpClient(),
        promptModel = org.multipaz.util.Platform.promptModel,
        authorizationSecureArea = walletStore.keySecureArea,
        eventLogger = walletStore.eventLogger(),
    )

    @Test
    fun the_launch_context_can_be_built_from_public_api() = runTest {
        val walletStore = store()
        val client = StubProvisioningClient(metadata)

        val document: Document = withContext(
            Dispatchers.Default + environment(walletStore) + RpcAuthClientSession()
        ) {
            model(walletStore).launch(
                coroutineContext = coroutineContext,
                document = null,
            ) { client }.await()
        }

        // Reached the client and produced a document, so the context was accepted.
        assertTrue(document.identifier.isNotEmpty())
        assertEquals(KeyBindingType.Attestation::class, credentialMetadata.keyBindingType::class)
    }
}
