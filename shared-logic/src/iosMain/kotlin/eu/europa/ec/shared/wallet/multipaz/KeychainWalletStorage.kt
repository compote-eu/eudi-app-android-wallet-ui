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

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import eu.europa.ec.shared.wallet.platform.IosDevicePasscode
import eu.europa.ec.shared.wallet.platform.IosKeychainAccessGroup
import kotlinx.io.bytestring.ByteString
import org.multipaz.storage.KeyExistsStorageException
import org.multipaz.storage.NoRecordStorageException
import org.multipaz.storage.StorageTableSpec
import org.multipaz.storage.base.BaseStorage
import org.multipaz.storage.base.BaseStorageTable
import org.multipaz.util.Logger
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSArray
import platform.Foundation.NSData
import platform.Foundation.NSDictionary
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly
import platform.Security.kSecAttrAccessGroup
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitAll
import platform.Security.kSecReturnAttributes
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The wallet's documents and credentials, kept as iOS Keychain items rather than rows in a file.
 *
 * This is "Option D" of the storage scoping memo, and it is the arrangement the official EUDI iOS
 * wallet uses. It replaces [WalletSqliteStorage] **for the document store only** — bookmarks, the
 * revoked-document flags and the transaction log stay in SQLite, because they are app data and gain
 * nothing from moving.
 *
 * ## What it buys
 *
 * The item class is `WhenPasscodeSetThisDeviceOnly`, which is the point of the exercise: it **cannot
 * be created on a device with no passcode**, where a file's `NSFileProtectionComplete` quietly
 * protects nothing. It is also `ThisDeviceOnly` intrinsically, where a file's exclusion from backup is
 * a per-item flag that new siblings do not inherit — a distinction that already cost this wallet a
 * correction when WAL split its database into three files.
 *
 * ## What the contract needed, measured rather than assumed
 *
 * Two objections carried against this design for months did not survive being read from multipaz
 * 0.99.0, and this class is where that shows:
 *
 *  - **No transactions are required**, because [org.multipaz.storage.StorageTable] has none to
 *    implement — eight methods, no batch, no savepoint. A document and its credentials are written by
 *    independent calls on *any* backend, SQLite included.
 *  - **No resumable iteration is required.** `afterKey`/`limit` have exactly one caller in all of
 *    multipaz — `SimpleEventLogger.getEvents()` — which stays on SQLite here. They are implemented
 *    anyway, because the contract declares them and a future caller would be entitled to use them.
 *
 * The whole contract was then exercised against a real Keychain in the app: 28 checks, 0 failures,
 * values up to 4 MiB round-tripping.
 *
 * ## Item layout
 *
 * One generic-password item per record:
 *
 *     kSecAttrService      "<servicePrefix>.<table name, lowercased>"
 *     kSecAttrAccount      base64url(partitionId ?: "") + "." + base64url(key)
 *     kSecAttrAccessGroup  the shared group, so the extension can read what the app wrote
 *     kSecValueData        8-byte big-endian expiration (epoch ms) ++ payload
 *
 * Uniqueness for a generic password is `(service, account)`, which is why the partition is folded
 * into the account rather than kept beside it: two partitions holding the same key would otherwise
 * collide on a single item. `.` is safe as a separator because it is not in the base64url alphabet.
 *
 * Expiration rides *inside* the value rather than in an attribute, so a record and its lifetime are
 * written and replaced by one `SecItem` call — the same soft-expiry semantics multipaz's own
 * backends use, where an expired record is invisible but present until purged.
 *
 * ## 🪤 Two things that bite
 *
 *  - **A Kotlin/Native test binary has no keychain at all** — every `SecItem` call returns
 *    `errSecNotAvailable`, because a test binary is not an app. Nothing here can be unit-tested
 *    directly; `MultipazWalletStore.build` therefore keeps taking a [org.multipaz.storage.Storage],
 *    so the suite runs against ephemeral storage exactly as before.
 *  - **Keychain items outlive an uninstall**, where the app-group container does not. That is why
 *    `clearSecretsLeftByAPreviousInstall` also clears this store — without it, deleting the wallet
 *    would leave its documents on the device.
 */
@OptIn(ExperimentalForeignApi::class)
class KeychainWalletStorage(
    private val servicePrefix: String,
    clock: Clock = Clock.System,
    private val accessible: CFTypeRef? = kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
    private val accessGroup: String? = IosKeychainAccessGroup.identifier(),
) : BaseStorage(clock) {

    override suspend fun createTable(tableSpec: StorageTableSpec): BaseStorageTable {
        // Mirrors EphemeralStorage: a table that cannot expire must not observe time passing,
        // or records would silently vanish from tables that never opted into expiration.
        val clockToUse = if (tableSpec.supportExpiration) clock else StoppedClock
        return KeychainWalletStorageTable(
            storage = this,
            spec = tableSpec,
            clock = clockToUse,
            service = serviceFor(tableSpec),
            accessible = accessible,
            accessGroup = accessGroup,
        )
    }

    private fun serviceFor(spec: StorageTableSpec): String =
        "$servicePrefix.${spec.name.lowercase()}"

    companion object {

        /**
         * Whether this device will actually let the wallet store a document.
         *
         * Writes a canary item at the production protection class and deletes it again. 📌 **This
         * asks the real question rather than a proxy for it**, which matters because the two come
         * apart in the one place the wallet is developed: **the simulator has no passcode, yet
         * accepts `WhenPasscodeSetThisDeviceOnly` happily** — measured 2026-09-05, `errSecSuccess`
         * alongside a passing control — because it enforces no data-protection class at all. Gating
         * on "is a passcode set" would therefore block the wallet on every simulator, on a device
         * where it demonstrably works.
         *
         * Cheap enough for launch: one `SecItemAdd` and one `SecItemDelete`, no I/O.
         */
        @OptIn(BetaInteropApi::class)
        fun canStoreDocuments(servicePrefix: String, accessGroup: String?): Boolean {
            val query = CFDictionaryCreateMutable(kCFAllocatorDefault, 6, null, null)
            val retained = mutableListOf<CFTypeRef>()
            fun keep(value: Any): CFTypeRef? = CFBridgingRetain(value)?.also { retained.add(it) }
            try {
                CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
                CFDictionarySetValue(query, kSecAttrService, keep("$servicePrefix.canary"))
                CFDictionarySetValue(query, kSecAttrAccount, keep("write-probe"))
                accessGroup?.let { CFDictionarySetValue(query, kSecAttrAccessGroup, keep(it)) }
                // Delete first: a canary left by an earlier launch would otherwise answer
                // errSecDuplicateItem, which is neither success nor the refusal being looked for.
                SecItemDelete(query)
                CFDictionarySetValue(query, kSecValueData, keep(byteArrayOf(0).toNSData()))
                CFDictionarySetValue(
                    query,
                    kSecAttrAccessible,
                    kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
                )
                val status = SecItemAdd(query, null)
                if (status == errSecSuccess) {
                    CFDictionarySetValue(query, kSecValueData, null)
                    SecItemDelete(query)
                    return true
                }
                Logger.w(TAG, "the Keychain refused a canary write at the production class: $status")
                return false
            } finally {
                retained.forEach { CFRelease(it) }
                CFRelease(query)
            }
        }

        /**
         * Deletes every item any table under [servicePrefix] holds, without opening the store.
         *
         * Used by `clearSecretsLeftByAPreviousInstall`, which runs before anything else at launch and
         * is deliberately not suspending — so it cannot go through [enumerateTables], which would
         * have to read the schema table first.
         *
         * 🪤 **The Keychain cannot match a service by prefix**, so this asks for every
         * generic-password item the process can see and filters in Kotlin. That is bounded by what
         * this app wrote and is only ever done once per install, but it is the reason this is not a
         * general-purpose delete: it reads attributes for items it will not touch.
         *
         * @return how many items were removed. Idempotent, so calling it a second time
         * returning zero is how a caller confirms the wipe actually took.
         */
        @OptIn(BetaInteropApi::class)
        fun deleteEverythingUnder(servicePrefix: String, accessGroup: String?): Int {
            val query = CFDictionaryCreateMutable(kCFAllocatorDefault, 4, null, null)
            val retained = mutableListOf<CFTypeRef>()
            fun keep(value: Any): CFTypeRef? = CFBridgingRetain(value)?.also { retained.add(it) }
            val services = mutableListOf<Pair<String, String>>()
            try {
                CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
                CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitAll)
                CFDictionarySetValue(query, kSecReturnAttributes, kCFBooleanTrue)
                accessGroup?.let { CFDictionarySetValue(query, kSecAttrAccessGroup, keep(it)) }
                memScoped {
                    val result = alloc<CFTypeRefVar>()
                    if (SecItemCopyMatching(query, result.ptr) != errSecSuccess) return@memScoped
                    val items = CFBridgingRelease(result.value) as? NSArray ?: return@memScoped
                    for (i in 0uL until items.count) {
                        val item = items.objectAtIndex(i) as? NSDictionary ?: continue
                        val service = item.objectForKey("svce") as? String ?: continue
                        val account = item.objectForKey("acct") as? String ?: continue
                        if (service.startsWith(servicePrefix)) services.add(service to account)
                    }
                }
            } finally {
                retained.forEach { CFRelease(it) }
                CFRelease(query)
            }

            var deleted = 0
            for ((service, account) in services) {
                val delete = CFDictionaryCreateMutable(kCFAllocatorDefault, 4, null, null)
                val held = mutableListOf<CFTypeRef>()
                fun hold(value: Any): CFTypeRef? = CFBridgingRetain(value)?.also { held.add(it) }
                try {
                    CFDictionarySetValue(delete, kSecClass, kSecClassGenericPassword)
                    CFDictionarySetValue(delete, kSecAttrService, hold(service))
                    CFDictionarySetValue(delete, kSecAttrAccount, hold(account))
                    accessGroup?.let { CFDictionarySetValue(delete, kSecAttrAccessGroup, hold(it)) }
                    if (SecItemDelete(delete) == errSecSuccess) deleted++
                } finally {
                    held.forEach { CFRelease(it) }
                    CFRelease(delete)
                }
            }
            return deleted
        }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class KeychainWalletStorageTable(
    override val storage: KeychainWalletStorage,
    spec: StorageTableSpec,
    private val clock: Clock,
    private val service: String,
    private val accessible: CFTypeRef?,
    private val accessGroup: String?,
) : BaseStorageTable(spec) {

    /**
     * Serialises this table's operations.
     *
     * The Keychain is a shared, out-of-process database, so this cannot make a check-then-write
     * atomic against another process — but it does make [insert] with a generated key safe against
     * concurrent callers inside this one, which is what the contract's own concurrency test asks
     * for. 🪤 The same caveat applies to SQLite here: nothing in the contract offers a transaction.
     */
    private val lock = Mutex()

    override suspend fun get(key: String, partitionId: String?): ByteString? {
        checkPartition(partitionId)
        return lock.withLock { readUnlocked(partitionId, key) }
    }

    private fun readUnlocked(partitionId: String?, key: String): ByteString? {
        val raw = copyData(account(partitionId, key)) ?: return null
        val record = Record.decode(raw)
        return if (record.expired(clock.now())) null else record.value
    }

    override suspend fun insert(
        key: String?,
        data: ByteString,
        partitionId: String?,
        expiration: Instant,
    ): String {
        checkPartition(partitionId)
        checkExpiration(expiration)
        if (key != null) {
            checkKey(key)
        }
        val encoded = Record(expiration, data).encode()
        return lock.withLock {
            if (key == null) {
                // Same shape as the reference implementations: 9 random bytes, retried on the
                // (vanishingly unlikely) collision, rather than a counter that would have to be
                // persisted somewhere of its own.
                while (true) {
                    val candidate = Random.Default.nextBytes(9).toBase64Url()
                    if (addItem(account(partitionId, candidate), encoded)) {
                        return@withLock candidate
                    }
                }
            }
            val account = account(partitionId, key)
            if (addItem(account, encoded)) {
                return@withLock key
            }
            // The item exists. An *expired* record is not a record: the contract lets an insert
            // take its place, so replace it rather than refusing.
            val existing = copyData(account)?.let { Record.decode(it) }
            if (existing != null && !existing.expired(clock.now())) {
                throw KeyExistsStorageException(
                    "Record with ${recordDescription(key, partitionId)} already exists",
                )
            }
            updateItem(account, encoded)
            key
        }
    }

    override suspend fun update(
        key: String,
        data: ByteString,
        partitionId: String?,
        expiration: Instant?,
    ) {
        checkPartition(partitionId)
        if (expiration != null) {
            checkExpiration(expiration)
        }
        lock.withLock {
            val account = account(partitionId, key)
            val existing = copyData(account)?.let { Record.decode(it) }
            if (existing == null || existing.expired(clock.now())) {
                throw NoRecordStorageException(
                    "No record with ${recordDescription(key, partitionId)}",
                )
            }
            // A null expiration means "leave the lifetime alone", so carry the stored one forward.
            updateItem(account, Record(expiration ?: existing.expiration, data).encode())
        }
    }

    override suspend fun delete(key: String, partitionId: String?): Boolean {
        checkPartition(partitionId)
        return lock.withLock {
            val account = account(partitionId, key)
            val existing = copyData(account)?.let { Record.decode(it) } ?: return@withLock false
            // An expired record reports as absent, and deleting it still reports false — but the
            // item goes, because leaving it would let a later purge report work that already ran.
            val visible = !existing.expired(clock.now())
            deleteItem(account)
            visible
        }
    }

    override suspend fun deleteAll() {
        lock.withLock {
            for (account in allAccounts()) {
                deleteItem(account)
            }
        }
    }

    override suspend fun deletePartition(partitionId: String) {
        checkPartition(partitionId)
        lock.withLock {
            for (account in allAccounts()) {
                if (decodePartition(account) == partitionId) {
                    deleteItem(account)
                }
            }
        }
    }

    override suspend fun enumerate(
        partitionId: String?,
        afterKey: String?,
        limit: Int,
    ): List<String> = enumerateImpl(partitionId, afterKey, limit).map { it.first }

    override suspend fun enumerateWithData(
        partitionId: String?,
        afterKey: String?,
        limit: Int,
    ): List<Pair<String, ByteString>> = enumerateImpl(partitionId, afterKey, limit)

    private suspend fun enumerateImpl(
        partitionId: String?,
        afterKey: String?,
        limit: Int,
    ): List<Pair<String, ByteString>> {
        checkPartition(partitionId)
        checkLimit(limit)
        if (limit == 0) {
            return listOf()
        }
        return lock.withLock {
            val now = clock.now()
            allItems()
                // The Keychain returns items in no defined order, so the ordering the contract
                // promises is imposed here. Cheap, because nothing that matters paginates: the
                // only caller of afterKey/limit in multipaz is the event log.
                .asSequence()
                .filter { (account, _) -> decodePartition(account) == partitionId }
                .map { (account, raw) -> decodeKey(account) to Record.decode(raw) }
                .filter { (key, record) ->
                    !record.expired(now) && (afterKey == null || key > afterKey)
                }
                .sortedBy { it.first }
                .take(limit)
                .map { (key, record) -> key to record.value }
                .toList()
        }
    }

    override suspend fun purgeExpired() {
        if (!spec.supportExpiration) {
            throw IllegalStateException("This table does not support expiration")
        }
        lock.withLock {
            val now = clock.now()
            for ((account, raw) in allItems()) {
                if (Record.decode(raw).expired(now)) {
                    deleteItem(account)
                }
            }
        }
    }

    // ---- account encoding -------------------------------------------------------------------
    //
    // Injective, and deliberately not order-preserving: enumerate sorts decoded keys anyway, so
    // the encoding only has to round-trip. base64url keeps the separator unambiguous.

    private fun account(partitionId: String?, key: String): String =
        "${partitionId.orEmpty().encodeToByteArray().toBase64Url()}." +
            key.encodeToByteArray().toBase64Url()

    private fun decodePartition(account: String): String? {
        val decoded = account.substringBefore('.').fromBase64Url().decodeToString()
        // A table without partitions stores the empty marker; it must read back as null, not "".
        return if (spec.supportPartitions) decoded else null
    }

    private fun decodeKey(account: String): String =
        account.substringAfter('.').fromBase64Url().decodeToString()

    // ---- SecItem plumbing -------------------------------------------------------------------

    private fun addItem(account: String, data: ByteArray): Boolean = withQuery { query ->
        CFDictionarySetValue(query, kSecAttrAccount, retain(account))
        CFDictionarySetValue(query, kSecValueData, retain(data.toNSData()))
        accessible?.let { CFDictionarySetValue(query, kSecAttrAccessible, it) }
        val status = SecItemAdd(query, null)
        if (status == errSecSuccess) return@withQuery true
        // errSecDuplicateItem is the expected "already there". Anything else is a real failure, and
        // the one worth naming is a device with no passcode: this class cannot exist without one, so
        // every write fails and the raw OSStatus says nothing a user could act on. The gate in
        // `IosAppRoot` should have caught it before the wallet ever rendered — if this throws, that
        // gate was bypassed or failed open, and the message has to carry the explanation itself.
        if (status != ERR_SEC_DUPLICATE_ITEM) {
            throw KeychainWriteRefused(status, passcodeSet = IosDevicePasscode.isSet())
        }
        false
    }

    private fun updateItem(account: String, data: ByteArray) {
        val attributes = CFDictionaryCreateMutable(kCFAllocatorDefault, 1, null, null)
        val retained = mutableListOf<CFTypeRef>()
        try {
            val value = CFBridgingRetain(data.toNSData())!!
            retained.add(value)
            CFDictionarySetValue(attributes, kSecValueData, value)
            val status = withQuery { query ->
                CFDictionarySetValue(query, kSecAttrAccount, retain(account))
                SecItemUpdate(query, attributes)
            }
            check(status == errSecSuccess) { "SecItemUpdate failed: $status" }
        } finally {
            retained.forEach { CFRelease(it) }
            CFRelease(attributes)
        }
    }

    private fun deleteItem(account: String) {
        val status = withQuery { query ->
            CFDictionarySetValue(query, kSecAttrAccount, retain(account))
            SecItemDelete(query)
        }
        check(status == errSecSuccess || status == errSecItemNotFound) {
            "SecItemDelete failed: $status"
        }
    }

    private fun copyData(account: String): ByteArray? = memScoped {
        withQuery { query ->
            CFDictionarySetValue(query, kSecAttrAccount, retain(account))
            CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            if (status != errSecSuccess) {
                check(status == errSecItemNotFound) { "SecItemCopyMatching failed: $status" }
                return@withQuery null
            }
            (CFBridgingRelease(result.value) as? NSData)?.toByteArray()
        }
    }

    /** Every `(account, value)` in this table, in whatever order the Keychain feels like. */
    private fun allItems(): List<Pair<String, ByteArray>> = memScoped {
        withQuery { query ->
            CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitAll)
            CFDictionarySetValue(query, kSecReturnAttributes, kCFBooleanTrue)
            CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            if (status != errSecSuccess) {
                check(status == errSecItemNotFound) { "SecItemCopyMatching(all) failed: $status" }
                return@withQuery emptyList()
            }
            @Suppress("UNCHECKED_CAST")
            val items = CFBridgingRelease(result.value) as? NSArray ?: return@withQuery emptyList()
            buildList {
                for (i in 0uL until items.count) {
                    val item = items.objectAtIndex(i) as? NSDictionary ?: continue
                    // "acct" and "v_Data" are the underlying string values of
                    // kSecAttrAccount and kSecValueData. A returned attribute dictionary is keyed
                    // by those, and the CFString constants cannot be used as NSDictionary keys
                    // here without bridging a constant we do not own.
                    val account = item.objectForKey("acct") as? String ?: continue
                    val data = (item.objectForKey("v_Data") as? NSData)?.toByteArray() ?: continue
                    add(account to data)
                }
            }
        }
    }

    private fun allAccounts(): List<String> = allItems().map { it.first }

    /**
     * Builds the `(class, service)` query every call shares, runs [block] against it, and releases
     * what the block retained. The dictionary is created with null callbacks — the Core Foundation
     * idiom the rest of this codebase already uses — so nothing is retained on our behalf and the
     * balancing release has to be ours.
     */
    private inline fun <T> withQuery(block: Retainer.(CFMutableDictionaryRef?) -> T): T {
        val query = CFDictionaryCreateMutable(kCFAllocatorDefault, 6, null, null)
        val retainer = Retainer()
        try {
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(query, kSecAttrService, retainer.retain(service))
            // Absent rather than empty when there is no shared group: passing null would be a
            // different query, and passing "" is not a group any item can belong to.
            accessGroup?.let { CFDictionarySetValue(query, kSecAttrAccessGroup, retainer.retain(it)) }
            return retainer.block(query)
        } finally {
            retainer.releaseAll()
            CFRelease(query)
        }
    }

    class Retainer {
        private val retained = mutableListOf<CFTypeRef>()

        fun retain(value: Any): CFTypeRef? =
            CFBridgingRetain(value)?.also { retained.add(it) }

        fun releaseAll() {
            retained.forEach { CFRelease(it) }
            retained.clear()
        }
    }

    /**
     * A stored record: its expiration, then its bytes.
     *
     * Expiration is stored rather than derived so that a record replaced by [update] keeps the
     * lifetime it was given, and so that reading one costs no second Keychain round trip.
     */
    private class Record(val expiration: Instant, val value: ByteString) {

        fun expired(now: Instant): Boolean = expiration < now

        fun encode(): ByteArray {
            val bytes = value.toByteArray()
            val out = ByteArray(Long.SIZE_BYTES + bytes.size)
            var millis = expiration.toEpochMilliseconds()
            for (i in Long.SIZE_BYTES - 1 downTo 0) {
                out[i] = (millis and 0xFF).toByte()
                millis = millis shr 8
            }
            bytes.copyInto(out, Long.SIZE_BYTES)
            return out
        }

        companion object {
            fun decode(raw: ByteArray): Record {
                check(raw.size >= Long.SIZE_BYTES) { "truncated record: ${raw.size} bytes" }
                var millis = 0L
                for (i in 0 until Long.SIZE_BYTES) {
                    millis = (millis shl 8) or (raw[i].toLong() and 0xFF)
                }
                return Record(
                    Instant.fromEpochMilliseconds(millis),
                    ByteString(raw.copyOfRange(Long.SIZE_BYTES, raw.size)),
                )
            }
        }
    }

    private companion object {
        /** `errSecDuplicateItem`, which the Kotlin/Native Security bindings do not expose. */
        const val ERR_SEC_DUPLICATE_ITEM = -25299
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) {
        NSData()
    } else {
        usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = size.toULong()) }
    }

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val bytes = ByteArray(size)
    if (size > 0) {
        bytes.usePinned { pinned ->
            platform.posix.memcpy(pinned.addressOf(0), this.bytes, length)
        }
    }
    return bytes
}

/**
 * The Keychain refused to store a document, with the one cause worth distinguishing already checked.
 *
 * 🪤 **The `OSStatus` for "no passcode" is not a documented single value** and could not be measured
 * here — the simulator enforces no protection class at all, and the test device is managed and will
 * not give up its passcode. So the cause is not inferred from [status]; it is asked separately, of
 * `LAContext`, and stated. That is the difference between a message a person can act on and a number
 * they have to search for.
 */
class KeychainWriteRefused(
    val status: Int,
    val passcodeSet: Boolean,
) : IllegalStateException(
    if (passcodeSet) {
        "The Keychain refused to store a wallet document (OSStatus $status). A passcode is set, so " +
            "this is not the passcode-required class being unsatisfiable."
    } else {
        "The Keychain refused to store a wallet document (OSStatus $status) because this device has " +
            "no passcode: the wallet's documents use a protection class that cannot exist without " +
            "one. Set a device passcode and reopen the wallet."
    },
)

private const val TAG = "KeychainWalletStorage"
