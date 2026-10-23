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

// The documents of one login share its DPoP key and its refresh token. A rotated token must reach every one
// of them, and deleting one must not take the key from the rest.
package eu.europa.ec.shared.wallet.multipaz

import eu.europa.ec.shared.wallet.document.WalletCredentialPolicy
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.buildCborMap
import org.multipaz.document.Document
import org.multipaz.securearea.software.SoftwareCreateKeySettings
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

class RefreshTokenChainTest {

    private val loginKey = "login-dpop-key"

    private suspend fun store(): MultipazWalletStore {
        val storage = EphemeralStorage()
        return MultipazWalletStore.build(storage = storage, secureAreas = listOf(SoftwareSecureArea.create(storage)))
    }

    /** A document holding [refreshToken] and naming [dpopKeyAlias], as one login's documents are stored. */
    private suspend fun MultipazWalletStore.documentOfLogin(
        refreshToken: String,
        dpopKeyAlias: String? = loginKey,
        resume: DeferredResume? = null,
    ): Document {
        val document = documentStore.createDocument(
            displayName = "PID",
            metadata = EudiDocumentMetadata.create(
                documentManagerId = documentManagerId,
                format = StoredDocumentFormat.MsoMdoc("eu.europa.ec.eudi.pid.1"),
                credentialPolicy = WalletCredentialPolicy.OnceOnly(numberOfCredentials = 1),
            ).also { metadata -> resume?.let { metadata.park("txn", it) } },
        )
        // As [IosVciAuthorizationSession.authorizationData] writes it: multipaz's cleanup parses it, and
        // without the issuer and configuration it gives up and deletes no key at all.
        val authorization = buildCborMap {
            put("type", Tstr("openid4vci"))
            put("issuerUri", Tstr("https://issuer.test"))
            put("configurationId", Tstr("pid_mdoc"))
            put("secureAreaId", Tstr(keySecureArea.identifier))
            dpopKeyAlias?.let { put("dpopKeyAlias", Tstr(it)) }
            put("refreshToken", Tstr(refreshToken))
        }
        document.edit { authorizationData = ByteString(*Cbor.encode(authorization)) }
        return document
    }

    private suspend fun MultipazWalletStore.refreshTokenOf(document: Document): String? =
        documentStore.lookupDocument(document.identifier)?.authorizationData?.openID4VciRefreshToken()

    private suspend fun MultipazWalletStore.keyExists(alias: String): Boolean =
        runCatching { keySecureArea.getKeyInfo(alias) }.isSuccess

    @Test
    fun a_rotated_token_reaches_every_document_that_held_the_spent_one() = runTest {
        val store = store()
        val first = store.documentOfLogin("rt-0")
        val second = store.documentOfLogin("rt-0")
        val third = store.documentOfLogin("rt-0")
        val otherLogin = store.documentOfLogin("rt-other", dpopKeyAlias = "other-key")

        val handedOn = store.handOnRefreshToken(spent = "rt-0", live = "rt-1")

        assertEquals(3, handedOn)
        listOf(first, second, third).forEach { assertEquals("rt-1", store.refreshTokenOf(it)) }
        assertEquals("rt-other", store.refreshTokenOf(otherLogin), "another login's token is not this one")
    }

    /** multipaz writes the rotated token back onto the document it refreshed; that one is left alone. */
    @Test
    fun a_document_already_holding_the_new_token_is_not_counted_again() = runTest {
        val store = store()
        store.documentOfLogin("rt-1")
        val sibling = store.documentOfLogin("rt-0")

        assertEquals(1, store.handOnRefreshToken(spent = "rt-0", live = "rt-1"))
        assertEquals("rt-1", store.refreshTokenOf(sibling))
    }

    @Test
    fun deleting_one_document_of_a_login_leaves_the_others_their_key() = runTest {
        val store = store()
        store.keySecureArea.createKey(loginKey, SoftwareCreateKeySettings.Builder().build())
        val deleted = store.documentOfLogin("rt-0")
        val kept = store.documentOfLogin("rt-0")
        val engine = MultipazWalletEngine(store)

        assertTrue(engine.deleteDocument(deleted.identifier).isSuccess)

        assertTrue(store.keyExists(loginKey), "the sibling still needs it to refresh")
        assertEquals(loginKey, store.documentStore.lookupDocument(kept.identifier)?.authorizationData?.openID4VciDpopKeyAlias())
    }

    @Test
    fun deleting_the_last_document_of_a_login_takes_its_key_with_it() = runTest {
        val store = store()
        store.keySecureArea.createKey(loginKey, SoftwareCreateKeySettings.Builder().build())
        val first = store.documentOfLogin("rt-0")
        val last = store.documentOfLogin("rt-0")
        val engine = MultipazWalletEngine(store)

        assertTrue(engine.deleteDocument(first.identifier).isSuccess)
        assertTrue(engine.deleteDocument(last.identifier).isSuccess)

        assertFailsWith<Throwable> { store.keySecureArea.getKeyInfo(loginKey) }
    }

    /** A parked document collects with the login's access token, which is bound to the same key. */
    @Test
    fun a_parked_document_waiting_on_the_logins_access_token_keeps_the_key_too() = runTest {
        val store = store()
        store.keySecureArea.createKey(loginKey, SoftwareCreateKeySettings.Builder().build())
        val deleted = store.documentOfLogin("rt-0")
        store.documentOfLogin(
            "rt-0",
            dpopKeyAlias = null,
            resume = DeferredResume(accessToken = "at", dpopKeyAlias = loginKey, expiresAt = Clock.System.now() + 5.minutes),
        )

        assertTrue(MultipazWalletEngine(store).deleteDocument(deleted.identifier).isSuccess)

        assertTrue(store.keyExists(loginKey))
    }

    @Test
    fun a_document_whose_key_nobody_else_names_keeps_its_claim() = runTest {
        val store = store()
        val alone = store.documentOfLogin("rt-0")
        store.documentOfLogin("rt-other", dpopKeyAlias = "other-key")

        store.releaseSharedDpopKey(alone.identifier)

        assertEquals(loginKey, assertNotNull(store.documentStore.lookupDocument(alone.identifier)).authorizationData?.openID4VciDpopKeyAlias())
    }

    @Test
    fun a_document_whose_key_a_sibling_names_gives_up_its_claim() = runTest {
        val store = store()
        val leaving = store.documentOfLogin("rt-0")
        store.documentOfLogin("rt-0")

        store.releaseSharedDpopKey(leaving.identifier)

        val stored = assertNotNull(store.documentStore.lookupDocument(leaving.identifier)).authorizationData
        assertNull(stored?.openID4VciDpopKeyAlias())
        assertEquals("rt-0", stored?.openID4VciRefreshToken(), "only the claim on the key is given up")
    }
}
