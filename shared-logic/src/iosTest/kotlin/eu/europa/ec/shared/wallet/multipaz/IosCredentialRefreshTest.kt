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
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.Tstr
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

    // ---- dropping the expired wallet attestation multipaz would otherwise replay -----------------
    //
    // The one thing that had to be true for a refresh to ever succeed, and the reason none had. This
    // wallet provider's attestations live 300 seconds; multipaz keeps the one from issuance and reuses
    // it forever, so every refresh more than five minutes later authenticated with a dead credential.
    // These cover the transform itself, since a live refresh belongs to a probe run.

    /** The CBOR map multipaz writes for `OpenID4VCIAuthorizationData`, as far as this cares. */
    private fun storedAuthorization(
        walletAttestation: String? = "an.attestation.jwt",
        walletAttestationKeyAlias: String? = "attestation-key",
        dpopKeyAlias: String? = "dpop-key",
        refreshToken: String? = "a-refresh-token",
    ): ByteString {
        val builder = CborMap.builder()
        builder.put("issuerUri", Tstr("https://fixture.issuer.invalid"))
        builder.put("configurationId", Tstr("pid_mdoc"))
        builder.put("secureAreaId", Tstr("software"))
        refreshToken?.let { builder.put("refreshToken", Tstr(it)) }
        dpopKeyAlias?.let { builder.put("dpopKeyAlias", Tstr(it)) }
        walletAttestationKeyAlias?.let { builder.put("walletAttestationKeyAlias", Tstr(it)) }
        walletAttestation?.let { builder.put("walletAttestation", Tstr(it)) }
        return ByteString(*Cbor.encode(builder.end().build()))
    }

    private fun keysOf(authorization: ByteString): Set<String> =
        (Cbor.decode(authorization.toByteArray()) as CborMap)
            .items.keys.map { (it as Tstr).value }.toSet()

    @Test
    fun the_stored_wallet_attestation_and_its_key_are_dropped() = runTest {
        val stripped = withoutStoredWalletAttestation(storedAuthorization())

        // Absent means null in this map, which is what makes multipaz mint a fresh attestation.
        val keys = keysOf(stripped)
        assertEquals(false, keys.contains("walletAttestation"))
        assertEquals(false, keys.contains("walletAttestationKeyAlias"))
    }

    @Test
    fun the_refresh_token_and_the_dpop_key_are_kept() = runTest {
        val stripped = withoutStoredWalletAttestation(storedAuthorization())

        // The DPoP key is what the refresh token is bound to: dropping it would turn this failure into
        // `invalid_grant`, which is a worse place to be than where we started.
        val keys = keysOf(stripped)
        assertEquals(true, keys.contains("dpopKeyAlias"))
        assertEquals(true, keys.contains("refreshToken"))
        assertEquals(setOf("issuerUri", "configurationId", "secureAreaId"), keys - setOf("dpopKeyAlias", "refreshToken"))
    }

    @Test
    fun authorization_that_stored_no_attestation_is_unchanged() = runTest {
        val original = storedAuthorization(walletAttestation = null, walletAttestationKeyAlias = null)

        assertEquals(keysOf(original), keysOf(withoutStoredWalletAttestation(original)))
    }

    // ---- which failure the user is told about ----------------------------------------------------

    @Test
    fun an_expired_refresh_token_says_to_add_the_document_again() = runTest {
        val (store, _) = walletWith()
        val refusal = TokenRefusalNotice().also { it.error = "invalid_grant" }

        val message = refresherOver(store).refreshFailureMessage(
            refusal = refusal,
            deferred = DeferredIssuanceNotice(),
            cause = IllegalStateException("Refresh token (seed credential) rejected by the issuer"),
        )

        // The failure a working wallet meets most often: this stack's refresh tokens live 30 minutes,
        // so a document left alone for longer has nothing left to authorize with. multipaz's own
        // sentence is true and unusable; this one says what to do.
        assertEquals(IosCredentialIssuer.AUTHORIZATION_EXPIRED, message)
        assertEquals(true, message.contains("Add it again"))
    }

    @Test
    fun any_other_refusal_keeps_the_issuers_own_words() = runTest {
        val (store, _) = walletWith()
        val refusal = TokenRefusalNotice().also { it.error = "invalid_client" }

        val message = refresherOver(store).refreshFailureMessage(
            refusal = refusal,
            deferred = DeferredIssuanceNotice(),
            cause = IllegalStateException("something the issuer said"),
        )

        // Only `invalid_grant` gets a translation; everything else is passed on rather than guessed at.
        assertEquals("something the issuer said", message)
    }

    @Test
    fun a_refusal_with_nothing_to_go_on_falls_back_to_the_generic_message() = runTest {
        val (store, _) = walletWith()

        val message = refresherOver(store).refreshFailureMessage(
            refusal = TokenRefusalNotice(),
            deferred = DeferredIssuanceNotice(),
            cause = IllegalStateException(),
        )

        assertEquals(IosCredentialIssuer.REFRESH_FAILED, message)
    }

    @Test
    fun something_that_is_not_the_map_multipaz_writes_is_passed_through() = runTest {
        // A schema change upstream, or a document from a build that stored something else. Guessing at
        // it would be worse than handing multipaz back exactly what it stored.
        val opaque = ByteString(1, 2, 3)

        assertEquals(opaque, withoutStoredWalletAttestation(opaque))
    }
}
