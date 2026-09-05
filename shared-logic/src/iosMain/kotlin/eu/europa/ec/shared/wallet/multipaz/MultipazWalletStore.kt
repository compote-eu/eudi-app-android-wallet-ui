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

import eu.europa.ec.shared.wallet.platform.IosAppGroup
import eu.europa.ec.shared.wallet.platform.IosKeychainAccessGroup
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.multipaz.document.Document
import org.multipaz.document.DocumentStore
import org.multipaz.document.DocumentUtil
import org.multipaz.document.buildDocumentStore
import org.multipaz.eventlogger.SimpleEventLogger
import org.multipaz.util.Logger
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.SecureEnclaveSecureArea
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.Storage
import org.multipaz.storage.StorageTable
import org.multipaz.storage.StorageTableSpec
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSError
import platform.Foundation.NSFileProtectionComplete
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
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
    /**
     * Where the wallet's own tables live — bookmarks, revoked flags, and multipaz's transaction log.
     *
     * Deliberately **not** the store the documents are in. Under Option D the documents moved to the
     * Keychain and this did not: app data gains nothing from the move, and the transaction log is the
     * one caller in all of multipaz that paginates, which is the single thing a Keychain backend has
     * to fake. Keeping it on SQLite keeps that caller on a backend with a real cursor.
     */
    private val appDataStorage: Storage,
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
            storage = appDataStorage,
            partitionId = documentManagerId,
            // multipaz's own default, made explicit because it is a data-retention decision rather
            // than a tuning knob: entries older than this are dropped from the History tab.
            expireAfter = EVENT_RETENTION,
        ).also { cachedEventLogger = it }
    }

    /** Bookmarks, keyed by document id, with an empty value — presence *is* the bookmark. */
    suspend fun bookmarksTable(): StorageTable = appDataStorage.getTable(BookmarksTableSpec)

    /**
     * Documents found revoked, keyed by document id, with an empty value — presence *is* the flag.
     *
     * This is the iOS counterpart of Android's Room `RevokedDocumentDao`, and it exists for the same
     * reason: a status-list check needs the network, so the answer is cached and the UI reads the
     * cache. Same consequence too — a document stays flagged until a later refresh clears it, which
     * is what makes "revoked" survive going offline.
     */
    suspend fun revokedDocumentsTable(): StorageTable =
        appDataStorage.getTable(RevokedDocumentsTableSpec)

    companion object {

        /**
         * Where the wallet's database lives: `<app group>/wallet/wallet.db`, falling back to
         * `Library/Application Support/wallet/wallet.db`.
         *
         * ## Why the app group
         *
         * The document-provider extension that answers Digital Credentials requests is a **separate
         * process with its own container**, so a store inside the app's own container is invisible to
         * it. An app group is the only place both can read, which makes this a prerequisite for the
         * responder rather than a preference.
         *
         * The group is `group.<the app's bundle id>`, so dev and demo get their own — matching the
         * reference iOS wallet's `group.$(PRODUCT_BUNDLE_IDENTIFIER)`, and matching the fact that the
         * two flavours already hold separate wallets under separate bundle ids.
         *
         * ⚠️ **The app's** bundle id, not the running process's — see [IosAppGroup], which is where
         * that distinction is resolved and why it matters.
         *
         * ## Why a fallback rather than a hard failure
         *
         * `com.apple.security.application-groups` is not a restricted entitlement, and it resolves even
         * on this fork's ad-hoc-signed simulator builds — verified, not assumed. But a signing setup
         * that has not registered the group would return null, and the same reasoning the directory
         * fallback below already follows applies with more force: a wallet that refuses to start is
         * worse than one that starts. The degradation is logged rather than silent, because its
         * consequence is specific — the app keeps working and the extension finds an empty wallet.
         *
         * ⚠️ **No migration, deliberately.** Nothing reads the old location afterwards and nothing
         * moves it. This fork has never been distributed, so there is no installed wallet whose data
         * could be stranded; clearing an existing simulator or device install is an uninstall, not a
         * code path. **Shipping to real users would make a migration necessary** — it is absent because
         * it is not needed yet, not because it was overlooked.
         */
        /**
         * The Keychain service namespace the document store writes under.
         *
         * 🪤 **Derived from the app-group identifier, never from the running process.** Both targets
         * publish that identifier into their Info.plist from one expression, so the app and the
         * document-provider extension agree on it; `NSBundle.mainBundle.bundleIdentifier` does not —
         * it is `…dev.provider` inside the extension, which is the defect `fd785674` fixed one layer
         * down. A build with no app group falls back to a constant, which keeps a single-process
         * wallet working and only costs flavours the ability to hold separate wallets on one device.
         */
        private fun keychainServicePrefix(): String {
            val group = IosAppGroup.identifier()
            if (group == null) {
                Logger.w(
                    "MultipazWalletStore",
                    "no app-group identifier; the document Keychain namespace falls back to a " +
                        "constant, so dev and demo builds would share one wallet on a device",
                )
            }
            return "${group ?: FALLBACK_KEYCHAIN_PREFIX}.documents"
        }

        /**
         * Deletes every document and every piece of key metadata this wallet has in the Keychain.
         *
         * Exposed here rather than on [KeychainWalletStorage] because the service namespace is this
         * object's to know — and because the one caller,
         * `clearSecretsLeftByAPreviousInstall`, must not have to construct a store to empty one.
         *
         * @return how many items were removed; a second call returning zero is the proof it took.
         */
        @OptIn(ExperimentalForeignApi::class)
        fun discardEverythingInTheKeychain(): Int = KeychainWalletStorage.deleteEverythingUnder(
            servicePrefix = keychainServicePrefix(),
            accessGroup = IosKeychainAccessGroup.identifier(),
        )

        @OptIn(ExperimentalForeignApi::class)
        private fun storeFileUrl(): NSURL {
            val manager = NSFileManager.defaultManager

            val appGroup = IosAppGroup.containerUrl()
            if (appGroup == null) {
                Logger.w(
                    "MultipazWalletStore",
                    "no app-group container; the wallet stays in this app's own container and the " +
                        "document-provider extension will not see its documents",
                )
            }

            val base = appGroup ?: manager.URLForDirectory(
                directory = NSApplicationSupportDirectory,
                inDomain = NSUserDomainMask,
                appropriateForURL = null,
                create = true,
                error = null,
            ) ?: manager.URLForDirectory(
                directory = NSDocumentDirectory,
                inDomain = NSUserDomainMask,
                appropriateForURL = null,
                create = true,
                error = null,
            ) ?: error("iOS reported no directory the wallet database could live in")

            val directory = base.URLByAppendingPathComponent(STORE_DIRECTORY, isDirectory = true)!!
            manager.createDirectoryAtURL(
                url = directory,
                withIntermediateDirectories = true,
                // On the directory so a file created inside it inherits the class — which is the only
                // protection the database gets on a first run, since the file is created by the SQLite
                // driver when [WalletSqliteStorage] opens its connection, after this function returns.
                attributes = mapOf(NSFileProtectionKey to NSFileProtectionComplete),
                error = null,
            )
            // Backup exclusion belongs on the directory, not the database file. It is a per-item
            // resource value that new siblings do not inherit, and under WAL the database has two of
            // them — `-wal` holds rows that have not been checkpointed yet, so excluding only
            // `wallet.db` would let recent credential data reach a backup while the database itself is
            // kept out of one. Directory exclusion cascades to contents; `657a8077` established that,
            // when multipaz's own helper set it there and silently excluded `Platform.storage` too.
            directory.setResourceValue(
                value = true,
                forKey = NSURLIsExcludedFromBackupKey,
                error = null,
            )
            // And the protection class again unconditionally, because `createDirectoryAtURL` applies
            // `attributes` only to a directory it actually creates — on an existing install the class
            // would otherwise never reach the directory at all, and the `-wal`/`-shm` sidecars depend
            // on inheriting it.
            //
            // 🚩 This is not cosmetic. SQLite writes a `-journal` beside the database, and multipaz
            // creates it, not us: with the directory unprotected that sidecar inherits iOS's *default*
            // class, leaving recently written rows readable while the device is locked next to a
            // database that is not. Measured on a device — the first version of this change left
            // `directory=unset` and only the database file protected.
            manager.setAttributes(
                mapOf(NSFileProtectionKey to NSFileProtectionComplete),
                ofItemAtPath = directory.path!!,
                error = null,
            )
            val file = directory.URLByAppendingPathComponent(STORE_FILE, isDirectory = false)!!
            // Also set explicitly, because inheritance only applies to files created *after* the
            // attribute — an install that already has a database predates it. On a first run the file
            // does not exist yet (the driver creates it when the connection opens), so this fails
            // exactly once, which is why the directory carries the class as well.
            //
            // 🚩 The error is read rather than discarded, and the class is read *back*: a protection
            // class that silently failed to apply looks identical to one that worked, and the whole
            // point of removing the background task was to be able to rely on this.
            memScoped {
                val failure = alloc<ObjCObjectVar<NSError?>>()
                val applied = manager.setAttributes(
                    mapOf(NSFileProtectionKey to NSFileProtectionComplete),
                    ofItemAtPath = file.path!!,
                    error = failure.ptr,
                )
                if (!applied && manager.fileExistsAtPath(file.path!!)) {
                    Logger.w(
                        "MultipazWalletStore",
                        "the wallet database exists but would not take a protection class: " +
                            (failure.value?.localizedDescription ?: "no reason given"),
                    )
                }
            }
            Logger.i(
                "MultipazWalletStore",
                "wallet database protection: file=" +
                    (manager.attributesOfItemAtPath(file.path!!, error = null)
                        ?.get(NSFileProtectionKey) ?: "absent (not created yet)") +
                    " directory=" +
                    (manager.attributesOfItemAtPath(directory.path!!, error = null)
                        ?.get(NSFileProtectionKey) ?: "unset"),
            )
            return file
        }

        /**
         * Scopes documents (and their credentials' multipaz `domain`) to this wallet, the way
         * `DocumentManagerImpl.identifier` does on Android. Changing it orphans existing documents,
         * so it is a stored-data contract, not a label.
         */
        const val DEFAULT_DOCUMENT_MANAGER_ID = "eudi-wallet-ios"

        /** Under Application Support, so the wallet's files are not loose among the app's. */
        private const val STORE_DIRECTORY = "wallet"

        /** Our own name: nothing reads the multipaz-chosen `storageNoBackup.db` any more. */
        private const val STORE_FILE = "wallet.db"

        /** Only reached when the Info.plist key is missing, which means a mis-generated project. */
        private const val FALLBACK_KEYCHAIN_PREFIX = "eu.europa.ec.eudi.wallet"

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
         * Opens the wallet's persistent stores on the device — **two of them since Option D.**
         *
         * ⚠️ **The documents are no longer in this database.** They are Keychain items; see
         * [KeychainWalletStorage]. What the file below holds is the wallet's *app data* — bookmarks,
         * revoked-document flags and multipaz's transaction log — which is why everything the rest of
         * this comment says about the file is still true and no longer says anything about where a
         * credential lives.
         *
         * **Non-backed-up**, matching Android, where the credential database lives in `no_backup/`:
         * credentials are bound to this device's Secure Enclave, so restoring them onto another device
         * would produce documents that can never be presented. That reasoning now applies twice over
         * — a `ThisDeviceOnly` Keychain item cannot be restored onto another device at all.
         *
         * ## Why not `Platform.nonBackedUpStorage`
         *
         * It was that until 2026-08-28. multipaz's own accessor is a `SqliteStorage` over
         * `NSDocumentDirectory`, and two of its choices are worth not inheriting:
         *
         *  - **the file sits in `Documents`**, the directory iOS will expose to the Files app the moment
         *    a build sets `UIFileSharingEnabled` or `LSSupportsOpeningDocumentsInPlace`. Neither is set
         *    in `iosApp/project.yml` today, so nothing is exposed — but that makes the wallet database's
         *    privacy depend on an unrelated plist key staying absent. Application Support is the
         *    directory Apple documents for exactly this, and is where the official iOS wallet keeps its
         *    own SwiftData store.
         *  - **it excludes the wrong thing from backup.** `Platform`'s helper sets
         *    `NSURLIsExcludedFromBackupKey` on the *directory*, not the file, so asking for
         *    non-backed-up storage silently also excludes `Platform.storage` — the one meant to be
         *    backed up. Our exclusion worked by accident. [WalletSqliteStorage] sets it on the file.
         *
         * ⚠️ **This is placement and backup hygiene, not encryption.** The file is still plain SQLite.
         * There is no encrypted `Storage` in multipaz 0.99.0 — `SqliteStorage`, `IosStorage`,
         * `EphemeralStorage` and `WebStorage` are all plaintext — so at-rest protection for *app data*
         * rests entirely on the file's data-protection class, which is `NSFileProtectionComplete` as of
         * 2026-09-04 rather than the `…UntilFirstUserAuthentication` default (see [storeFileUrl]).
         *
         * 📌 **The documents do better than that now, which is the whole point of Option D.** A
         * Keychain item at `WhenPasscodeSetThisDeviceOnly` cannot exist on a device with no passcode,
         * where a file's protection class quietly protects nothing — and it is device-bound by
         * construction rather than by a per-item backup flag that new siblings do not inherit. On
         * documents this now matches the official iOS wallet exactly; on these app-data tables we
         * remain behind Android, which keeps its equivalent in SQLCipher.
         *
         * ⛔ **`NSFileProtectionCompleteUnlessOpen` is MEASURED AND REJECTED — do not re-propose it.**
         * It was tested on a locked iPhone SE (iOS 26.6.1) on 2026-09-04: a *fresh* `open()` while the
         * device is locked gives `EPERM` under `CompleteUnlessOpen` exactly as it does under
         * `Complete`, because the class only keeps an *already open* file readable.
         *
         * 📌 **Encrypting this file ("Option C") is still open**, and is now only about the app-data
         * tables — the argument for it shrank when the documents left. See the storage scoping memo.
         */
        // [KeychainWalletStorage]'s protection class is a `CFTypeRef` default, so constructing one
        // asks the caller for the same opt-in the cinterop types carry.
        @OptIn(ExperimentalForeignApi::class)
        suspend fun open(
            documentManagerId: String = DEFAULT_DOCUMENT_MANAGER_ID,
            /**
             * The store the documents go in — a seam, and one Option D forced open.
             *
             * 🪤 **A Kotlin/Native test binary is not an app and has no keychain at all**: every
             * `SecItem` call returns `errSecNotAvailable`. So a test that wants to exercise anything
             * else about this function — where the *database* file lands, say — has to supply
             * something else here. That is the standing cost of keeping documents in the Keychain,
             * and it is the reason this parameter exists rather than a preference for injectability.
             */
            documentStorage: Storage = KeychainWalletStorage(
                servicePrefix = keychainServicePrefix(),
            ),
        ): MultipazWalletStore {
            // Not multipaz's `IosStorage`: the same connection, plus the `busy_timeout` it omits. Two
            // readers race here at every launch and the loser used to fail outright — see
            // [WalletSqliteStorage].
            val appDataStorage = WalletSqliteStorage(storageFileUrl = storeFileUrl())

            // The secure areas persist their key metadata alongside the documents it belongs to,
            // which is why they are created against the document store and not the one holding
            // bookmarks.
            // The real key store. It works on the simulator too — multipaz drops the
            // user-authentication flags there rather than failing.
            val secureEnclave = SecureEnclaveSecureArea.create(documentStorage)
            return build(
                storage = documentStorage,
                appDataStorage = appDataStorage,
                secureAreas = listOf(
                    secureEnclave,
                    // Also registered because a credential records which secure area holds its key;
                    // without this, documents whose keys are software-backed cannot be loaded.
                    SoftwareSecureArea.create(documentStorage),
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
            /**
             * Defaults to [storage] so that a test supplying one ephemeral store still gets a
             * coherent wallet — and so that the split introduced by Option D did not have to be
             * repeated across ten test files that do not care about it.
             */
            appDataStorage: Storage = storage,
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
                appDataStorage = appDataStorage,
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
