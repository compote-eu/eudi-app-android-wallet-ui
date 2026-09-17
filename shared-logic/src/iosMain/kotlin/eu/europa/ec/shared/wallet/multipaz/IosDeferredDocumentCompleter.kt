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

import eu.europa.ec.shared.wallet.config.iosWalletConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.encodeToByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.Tstr
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.document.Document
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.util.Logger
import org.multipaz.util.fromBase64Url

/**
 * Finishes a document the issuer promised to mint later.
 *
 * The collecting half is [IosDeferredCredentialCollector]; this is the wallet half — which document is
 * waiting, which key signs for it, and what to do with what comes back.
 *
 * ## Why the credentials are *certified* rather than created
 *
 * multipaz creates the credential keys **before** the credential request and leaves them on the
 * document as pending (uncertified) credentials — `ProvisioningModel` calls
 * `getPendingKeyBoundCredentials` and only afterwards `obtainCredentials`, certifying each one with the
 * issuer's answer. When the issuer defers, that request throws and **the pending credentials stay on
 * the document**. That is the whole reason deferred issuance is possible here at all: the credential
 * the issuer eventually mints is bound to those very keys, and creating new ones would make it
 * unusable. So this certifies what is already there and never creates a key.
 *
 * 🪤 It follows that a deferred document is only collectable while its pending credentials survive.
 * Deleting the document, or anything that drops them, ends the possibility — there is no way to ask the
 * issuer to re-mint against a new key without starting issuance again.
 */
internal class IosDeferredDocumentCompleter(
    /**
     * The open store. Taken directly rather than through [IosWalletEngine] because issuance must write
     * into *this* store — a second one over the same storage has its own cache — and because a class
     * that needs only the store should say so.
     */
    private val store: MultipazWalletStore,
    private val httpClient: HttpClient,
    private val walletProviderBaseUrl: String,
    private val issuers: List<IosVciIssuer> = IosIssuerCatalog.issuers,
) {

    /**
     * Asks the issuer for [document]'s credential and, if it is ready, finishes the document.
     *
     * Returns what happened, so the caller can tell "ask again later" from "this will never arrive" —
     * a distinction the documents screen needs, since one keeps the document and the other should stop
     * showing a spinner.
     */
    suspend fun complete(document: Document): DeferredCollection {
        val metadata = document.eudiMetadata
            ?: return DeferredCollection.Failed("the document carries no wallet metadata")
        val transactionId = metadata.deferredTransactionId
            ?: return DeferredCollection.Failed("the document is not waiting on a deferred issuance")

        val issuerUrl = metadata.issuerMetadata?.credentialIssuerIdentifier
            ?: return DeferredCollection.Failed("the document does not say which issuer deferred it")
        val issuer = issuers.firstOrNull { it.issuerUrl == issuerUrl }
            ?: return DeferredCollection.Unsupported("this build does not know the issuer $issuerUrl")

        val authorization = document.authorizationData
            ?: return DeferredCollection.AuthorizationExpired
        val stored = authorization.openID4VciAuthorization()
            ?: return DeferredCollection.Failed("the stored authorization could not be read")

        val dpopKey = runCatching {
            AsymmetricKey.anonymous(store.keySecureArea, stored.dpopKeyAlias)
        }.getOrElse {
            // The alias is in the CBOR but the key is gone — a wiped secure area, or a store restored
            // without it. Nothing can be signed, so nothing can be collected.
            Logger.w(TAG, "the DPoP key ${stored.dpopKeyAlias} is no longer in the secure area")
            return DeferredCollection.AuthorizationExpired
        }
        // Thrown away afterwards: a wallet attestation says *which client this is*, nothing about the
        // document, so it does not need to be the key anything else used.
        val attestationAlias = "$ATTESTATION_KEY_PREFIX${document.identifier}"
        val attestationKey = store.keySecureArea.let { secureArea ->
            runCatching { AsymmetricKey.anonymous(secureArea, attestationAlias) }.getOrElse {
                secureArea.createKey(attestationAlias, CreateKeySettings())
                AsymmetricKey.anonymous(secureArea, attestationAlias)
            }
        }

        val collector = IosDeferredCredentialCollector(
            httpClient = httpClient,
            walletProviderBaseUrl = walletProviderBaseUrl,
            clientId = issuer.clientId,
        )
        val collected = collector.collect(
            issuerUrl = issuerUrl,
            transactionId = transactionId,
            refreshToken = stored.refreshToken,
            dpopKey = dpopKey,
            attestationKey = attestationKey,
        )

        when (collected) {
            is DeferredCollection.Issued -> store(document, collected.credentials)
            // Defensive, not observed: the dev issuer echoes the SAME handle (measured 2026-09-16),
            // but the spec allows an issuer to hand back a different one, and a stale handle would fail
            // every later retry with `invalid_transaction_id`. Only writes when it actually changed.
            is DeferredCollection.StillPending -> collected.transactionId
                ?.takeIf { it != transactionId }
                ?.let { rotated ->
                    metadata.park(rotated)
                    document.edit { this.metadata = metadata }
                    Logger.i(TAG, "the issuer rotated the handle for ${document.identifier}")
                }

            else -> Unit
        }
        return collected
    }

    /**
     * Certifies the waiting credentials and marks the document issued.
     *
     * The encoding is multipaz's own, from `OpenID4VCIProvisioningClient`: an mdoc arrives base64url
     * encoded and is stored decoded, an SD-JWT is stored as its compact text. Getting this wrong would
     * store bytes that parse as nothing, and the document would look issued while showing no claims.
     */
    private suspend fun store(document: Document, credentials: List<String>) {
        val pending = document.getPendingCredentials()
        if (pending.isEmpty()) {
            Logger.w(TAG, "the issuer sent credentials but the document has none waiting for them")
            return
        }
        val isMdoc = document.eudiMetadata?.format is StoredDocumentFormat.MsoMdoc
        // Zipped: an issuer may return fewer than were asked for, and certifying a credential with
        // another one's data would be worse than leaving it pending.
        pending.zip(credentials).forEach { (credential, value) ->
            val issuerData = if (isMdoc) {
                ByteString(*value.fromBase64Url())
            } else {
                value.encodeToByteString()
            }
            runCatching { credential.certify(issuerData) }.onFailure {
                Logger.w(TAG, "could not certify a credential: ${it::class.simpleName}: ${it.message}")
            }
        }

        val metadata = document.eudiMetadata ?: return
        metadata.issue()
        metadata.completeDeferred()
        // `edit` is what persists the mutated metadata, the same contract `issue()` has always had.
        document.edit { this.metadata = metadata }
        Logger.i(TAG, "deferred issuance completed for ${document.identifier}")
    }

    private companion object {
        const val TAG = "IosDeferredCompleter"
        const val ATTESTATION_KEY_PREFIX = "deferred-attestation-"
    }
}

/**
 * The two members of multipaz's `OpenID4VCIAuthorizationData` this needs.
 *
 * That class is `internal` to multipaz, but it is `@CborSerializable` and written to disk, so the map
 * is readable — the same move [withoutStoredWalletAttestation] already makes to drop a stale wallet
 * attestation. ⛔ Read-only here: rewriting it would risk the schema hash multipaz checks.
 */
internal data class StoredOpenID4VciAuthorization(
    val dpopKeyAlias: String,
    val refreshToken: String,
)

internal fun ByteString.openID4VciAuthorization(): StoredOpenID4VciAuthorization? {
    val map = runCatching { Cbor.decode(toByteArray()) }.getOrNull() as? CborMap ?: return null
    val alias = (map.items[Tstr(DPOP_KEY_ALIAS_KEY)] as? Tstr)?.value
    val refresh = (map.items[Tstr(REFRESH_TOKEN_KEY)] as? Tstr)?.value
    if (alias.isNullOrBlank() || refresh.isNullOrBlank()) return null
    return StoredOpenID4VciAuthorization(dpopKeyAlias = alias, refreshToken = refresh)
}

private const val DPOP_KEY_ALIAS_KEY = "dpopKeyAlias"
private const val REFRESH_TOKEN_KEY = "refreshToken"

/**
 * Collects the deferred credential for [documentId], if that document is waiting on one.
 *
 * The seam `:shared-ui` uses: the store and the completer are both `internal` to this module, and the
 * engine's `store()` deliberately is too, so the wiring belongs here rather than in the UI module's DI.
 */
suspend fun IosWalletEngine.collectDeferredDocument(documentId: String): DeferredCollection {
    val store = store()
    val document = store.documentStore.lookupDocument(documentId)
        ?: return DeferredCollection.Failed("no such document")
    return IosDeferredDocumentCompleter(
        store = store,
        httpClient = HttpClient(Darwin.create()),
        walletProviderBaseUrl = iosWalletConfig.walletProviderUrl,
    ).complete(document)
}

/**
 * The deferred handle currently stored for [documentId], or null if it is not waiting on one.
 *
 * Only a probe needs this — it is how a live run shows that the issuer **rotated** the handle between
 * polls, which is behaviour no unit test would have thought to expect.
 */
suspend fun IosWalletEngine.deferredHandleOf(documentId: String): String? =
    store().documentStore.lookupDocument(documentId)?.eudiMetadata?.deferredTransactionId

/**
 * Every document waiting on a deferred issuance, as `documentId -> formatType`.
 *
 * The documents screen selects what to sweep by issuance state, and that rule cannot see a **refresh**
 * that was deferred: such a document keeps its existing credentials and stays `Issued`, so it is never
 * offered for sweeping even though the issuer is minting for it. This is the authoritative answer, and
 * it is why `IosDocumentsPlatformBridge` unions it with whatever the screen passed in.
 */
suspend fun IosWalletEngine.documentsAwaitingDeferredIssuance(): Map<String, String> {
    val store = store()
    return store.documentStore.listDocuments().mapNotNull { document ->
        val metadata = document.eudiMetadata ?: return@mapNotNull null
        if (metadata.deferredTransactionId == null) return@mapNotNull null
        document.identifier to metadata.format.identifier
    }.toMap()
}
