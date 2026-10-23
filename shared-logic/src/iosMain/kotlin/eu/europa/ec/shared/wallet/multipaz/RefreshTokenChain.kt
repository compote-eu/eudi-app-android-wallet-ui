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

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.multipaz.util.Logger

/**
 * One refresh token per login, however many documents that login issued.
 *
 * One authorization issues several documents ([IosVciAuthorizationSession]), and they all keep that
 * login's DPoP key and refresh token. A server that rotates refresh tokens spends the one presented and
 * hands back another; Plaut's Keycloak also treats a spent token presented again as theft and ends the
 * whole session (measured 2026-10-22). So the documents of one login must hold the token as one value:
 * - every use of a stored refresh token happens inside [withLock], so two documents never present the
 *   same token at once;
 * - whatever comes back is written to every document that held the presented token
 *   ([handOnRefreshToken]) before anyone can read it again.
 * A spent token is then never presented. This holds whether the server keeps its tokens, rotates them, or
 * rotates them and revokes on reuse.
 *
 * ⛔ The documents used to be moved onto DPoP keys of their own after issuance, through a refresh-token
 * request each. On a rotating server that request spent the shared token, and every document but the
 * rebound one was left with a token that killed the session when presented. The shared key is safe to
 * delete only with the last document that names it; see [releaseSharedDpopKey].
 */
internal object RefreshTokenChain {

    /** One for the process, which holds the one store: refreshes are rare, so serializing them all costs nothing. */
    private val lock = Mutex()

    /** Runs [block], in which a stored refresh token may be read and presented, after any other such use. */
    suspend fun <T> withLock(block: suspend () -> T): T = lock.withLock { block() }
}

/**
 * Writes [live] on every document whose stored authorization holds [spent], and returns how many.
 *
 * Matched by the token, not by document: a login's documents hold the same one, and that is what makes
 * them its documents. A document already holding [live] — multipaz writes a rotated token back onto the
 * document it refreshed — is left as it is.
 */
internal suspend fun MultipazWalletStore.handOnRefreshToken(spent: String, live: String): Int {
    if (spent == live) return 0
    var handedOn = 0
    for (document in documentStore.listDocuments()) {
        val authorization = document.authorizationData ?: continue
        if (authorization.openID4VciRefreshToken() != spent) continue
        val updated = authorization.withRefreshToken(live)
        if (updated == null) {
            Logger.w(TAG, "${document.identifier}: the rotated refresh token could not be stored")
            continue
        }
        document.edit { authorizationData = updated }
        handedOn++
    }
    Logger.i(TAG, "the authorization server rotated the refresh token; handed it on to $handedOn document(s)")
    return handedOn
}

/**
 * Before [documentId] is deleted: gives up its claim on the DPoP key if another document still names it.
 *
 * multipaz deletes the key a deleted document's authorization names. A login's documents share that key,
 * so deleting one would take it from the others — and they need it to refresh, and to collect a deferred
 * credential with the login's access token, which is bound to it. The last document to name the key keeps
 * its claim, and the key goes with it.
 */
internal suspend fun MultipazWalletStore.releaseSharedDpopKey(documentId: String) {
    val document = documentStore.lookupDocument(documentId) ?: return
    val authorization = document.authorizationData ?: return
    val alias = authorization.openID4VciDpopKeyAlias() ?: return
    val stillNamed = documentStore.listDocuments().any { other ->
        other.identifier != documentId && (
            other.authorizationData?.openID4VciDpopKeyAlias() == alias ||
                other.eudiMetadata?.deferredResume?.dpopKeyAlias == alias
            )
    }
    if (!stillNamed) return
    val released = authorization.withoutDpopKey()
    if (released == null) {
        Logger.w(TAG, "$documentId: could not release the shared DPoP key; deleting it would take it from its siblings")
        return
    }
    document.edit { authorizationData = released }
    Logger.i(TAG, "$documentId: left the shared DPoP key to the documents that still use it")
}

private const val TAG = "RefreshTokenChain"
