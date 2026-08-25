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
import org.multipaz.document.Document
import org.multipaz.document.DocumentStore
import org.multipaz.document.DocumentUtil
import org.multipaz.document.buildDocumentStore
import org.multipaz.eventlogger.SimpleEventLogger
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.SecureEnclaveSecureArea
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.Storage
import org.multipaz.storage.StorageTable
import org.multipaz.storage.StorageTableSpec
import org.multipaz.util.Platform
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * The iOS wallet's multipaz storage layer: the [DocumentStore] its documents live in, plus the small
 * amount of app state that sits beside them (bookmarks). This is the iOS analogue of what
 * `DocumentManagerImpl` sets up on Android, minus everything to do with issuance.
 *
 * Kept out of the public API — [MultipazWalletEngine] is the boundary, and `WalletEngine` the
 * contract; nothing above this package should name a multipaz type.
 */
internal class MultipazWalletStore(
    val documentStore: DocumentStore,
    val documentManagerId: String,
    /** The secure area new document keys are created in — the Secure Enclave on a real wallet. */
    val keySecureArea: SecureArea,
    private val storage: Storage,
) {

    private val eventLoggerLock = Mutex()
    private var cachedEventLogger: SimpleEventLogger? = null

    /**
     * The wallet's transaction log.
     *
     * multipaz writes to it *itself*: `Iso18013Presentment` and `uriSchemePresentment` both call
     * `source.eventLogger?.addEventAsync(...)` once a response has gone out, and `ProvisioningModel`
     * logs a provisioning event when a document is issued. So the whole write side is this object
     * being handed to them rather than the null they were getting — which is also why only
     * *successful* exchanges appear, exactly as on Android.
     *
     * One instance per store, since a second would keep its own initialization state over the same
     * table for no benefit.
     */
    suspend fun eventLogger(): SimpleEventLogger = eventLoggerLock.withLock {
        cachedEventLogger ?: SimpleEventLogger(
            storage = storage,
            partitionId = documentManagerId,
            // multipaz's own default, made explicit because it is a data-retention decision rather
            // than a tuning knob: entries older than this are dropped from the History tab.
            expireAfter = EVENT_RETENTION,
        ).also { cachedEventLogger = it }
    }

    /** Bookmarks, keyed by document id, with an empty value — presence *is* the bookmark. */
    suspend fun bookmarksTable(): StorageTable = storage.getTable(BookmarksTableSpec)

    /**
     * Documents found revoked, keyed by document id, with an empty value — presence *is* the flag.
     *
     * This is the iOS counterpart of Android's Room `RevokedDocumentDao`, and it exists for the same
     * reason: a status-list check needs the network, so the answer is cached and the UI reads the
     * cache. Same consequence too — a document stays flagged until a later refresh clears it, which
     * is what makes "revoked" survive going offline.
     */
    suspend fun revokedDocumentsTable(): StorageTable = storage.getTable(RevokedDocumentsTableSpec)

    companion object {

        /**
         * Scopes documents (and their credentials' multipaz `domain`) to this wallet, the way
         * `DocumentManagerImpl.identifier` does on Android. Changing it orphans existing documents,
         * so it is a stored-data contract, not a label.
         */
        const val DEFAULT_DOCUMENT_MANAGER_ID = "eudi-wallet-ios"

        /** How long a transaction stays in the log. multipaz's default, kept deliberately. */
        val EVENT_RETENTION = 60.days

        private val BookmarksTableSpec = StorageTableSpec(
            name = "EudiDocumentBookmarks",
            supportPartitions = false,
            supportExpiration = false,
        )

        /**
         * No expiration, deliberately: a revoked document must not quietly become un-flagged because
         * a row aged out. Rows are removed only when a refresh sees the credential valid again,
         * which is exactly how the Android DAO is maintained.
         */
        private val RevokedDocumentsTableSpec = StorageTableSpec(
            name = "EudiRevokedDocuments",
            supportPartitions = false,
            supportExpiration = false,
        )

        /**
         * Opens the wallet's persistent store on the device.
         *
         * Uses multipaz's **non-backed-up** storage, matching Android, where the credential database
         * lives in `no_backup/`: credentials are bound to this device's Secure Enclave, so restoring
         * them onto another device would produce documents that can never be presented.
         */
        suspend fun open(
            documentManagerId: String = DEFAULT_DOCUMENT_MANAGER_ID,
        ): MultipazWalletStore {
            val storage = Platform.nonBackedUpStorage
            // The real key store. It works on the simulator too — multipaz drops the
            // user-authentication flags there rather than failing.
            val secureEnclave = SecureEnclaveSecureArea.create(storage)
            return build(
                storage = storage,
                secureAreas = listOf(
                    secureEnclave,
                    // Also registered because a credential records which secure area holds its key;
                    // without this, documents whose keys are software-backed cannot be loaded.
                    SoftwareSecureArea.create(storage),
                ),
                keySecureArea = secureEnclave,
                documentManagerId = documentManagerId,
            )
        }

        /**
         * Builds a store over the supplied [storage] and [secureAreas] — the seam tests use to run
         * against ephemeral storage and a software secure area.
         */
        suspend fun build(
            storage: Storage,
            secureAreas: List<SecureArea>,
            keySecureArea: SecureArea = secureAreas.first(),
            documentManagerId: String = DEFAULT_DOCUMENT_MANAGER_ID,
        ): MultipazWalletStore {
            val secureAreaRepository = SecureAreaRepository.Builder()
                .apply { secureAreas.forEach { add(it) } }
                .build()

            val documentStore = buildDocumentStore(
                storage = storage,
                secureAreaRepository = secureAreaRepository,
            ) {
                setDocumentMetadataFactory(EudiDocumentMetadata::restore)
            }

            return MultipazWalletStore(
                documentStore = documentStore,
                documentManagerId = documentManagerId,
                keySecureArea = keySecureArea,
                storage = storage,
            )
        }
    }
}

/**
 * How many credentials a refresh would replace for [document], without creating or fetching anything.
 *
 * multipaz's own count, from the same [org.multipaz.provisioning.DocumentProvisioningSettings] the
 * provisioning handler uses: a credential is replaced when it has been used up or is close to expiring.
 * Asking first is what keeps a pointless refresh from burning the document's rotating refresh token —
 * see the note at the call site in `IosCredentialIssuer.refreshCredentials`.
 */
internal suspend fun MultipazWalletStore.credentialsNeededFor(document: Document): Int {
    // The document's own policy, so this answers the question multipaz will ask when it actually
    // provisions — the issuer's batch size and threshold, not the wallet's defaults. Two copies that
    // drifted would answer for a wallet that does not exist.
    val settings = document.eudiMetadata?.credentialPolicy
        ?.let { IosDocumentProvisioningHandler.settingsForPolicy(store = this, policy = it) }
        ?: IosDocumentProvisioningHandler.settingsFor(store = this)
    val domain = when (document.eudiMetadata?.format) {
        is StoredDocumentFormat.SdJwtVc -> settings.sdJwtNoUserAuthDomain
        else -> settings.mdocNoUserAuthDomain
    }
    return DocumentUtil.managedCredentialHelper(
        document = document,
        domain = domain,
        createCredential = null,
        now = Clock.System.now(),
        numCredentials = settings.keyBoundCredentialNumPerDomain,
        maxUsesPerCredential = settings.keyBoundCredentialMaxUses,
        minValidTime = settings.minValidTime,
        dryRun = true,
    )
}
