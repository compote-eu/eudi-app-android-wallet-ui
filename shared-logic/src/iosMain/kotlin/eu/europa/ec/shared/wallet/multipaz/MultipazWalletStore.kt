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

import org.multipaz.document.DocumentStore
import org.multipaz.document.buildDocumentStore
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.SecureEnclaveSecureArea
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.Storage
import org.multipaz.storage.StorageTable
import org.multipaz.storage.StorageTableSpec
import org.multipaz.util.Platform

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

    /** Bookmarks, keyed by document id, with an empty value — presence *is* the bookmark. */
    suspend fun bookmarksTable(): StorageTable = storage.getTable(BookmarksTableSpec)

    companion object {

        /**
         * Scopes documents (and their credentials' multipaz `domain`) to this wallet, the way
         * `DocumentManagerImpl.identifier` does on Android. Changing it orphans existing documents,
         * so it is a stored-data contract, not a label.
         */
        const val DEFAULT_DOCUMENT_MANAGER_ID = "eudi-wallet-ios"

        private val BookmarksTableSpec = StorageTableSpec(
            name = "EudiDocumentBookmarks",
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
