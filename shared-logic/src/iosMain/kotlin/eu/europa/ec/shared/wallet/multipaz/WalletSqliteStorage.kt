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

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.NativeSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import org.multipaz.storage.sqlite.SqliteStorage
import org.multipaz.util.Logger
import platform.Foundation.NSURL

/**
 * multipaz's `IosStorage`, plus the one pragma it does not set.
 *
 * `IosStorage.getConnection` is `NativeSQLiteDriver().open(path)` and nothing else — no
 * `busy_timeout`, no `journal_mode`. SQLite's default busy timeout is **0**, so a connection that
 * finds the database locked by another one fails **immediately** with `SQLITE_BUSY`
 * (`Error code: 5, message: database is locked`) rather than retrying for a few milliseconds.
 *
 * 🐞 **That is not theoretical: it was observed on a device, in 2 of 5 runs.** Contention is between
 * *connections*, not threads — multipaz pins each storage instance to its own single thread ("Native
 * sqlite crashes when used with Dispatchers.IO"), so a second `MultipazWalletStore.open()` is a second
 * connection and a second contender. The app makes two of them at launch without meaning to:
 * `reconcileDocumentRegistrations()` and `refreshWalletOnLaunch()` both read the store immediately,
 * concurrently, and whichever loses gets nothing.
 *
 * ⚠️ **The consequence is silent.** Registration only prints, and revocation failure is swallowed by
 * design, so a lost race means a document quietly missing from the system credential picker, or a
 * revocation check quietly skipped.
 *
 * **[BUSY_TIMEOUT_MS] is the whole fix**: wait rather than fail. It is deliberately far longer than the
 * contention it exists for — a launch sweep, milliseconds — so that a real deadlock still surfaces as
 * an error instead of hanging forever.
 *
 * 🚩 **WAL brings two sidecars, and both are handled elsewhere on purpose.** `-wal` and `-shm` need the
 * same treatment as the database itself, and the two mechanisms differ:
 *
 *  - **Protection class** comes free by inheritance — a file created in the store directory picks up
 *    `NSFileProtectionComplete` from it, measured on a device (created with no class of its own, and
 *    refused with `EPERM` while locked). `MultipazWalletStore.storeFileUrl` sets the directory's class
 *    unconditionally for exactly this reason.
 *  - **Backup exclusion does NOT inherit.** `NSURLIsExcludedFromBackupKey` is per-item, so it moved from
 *    the database file to the directory in the same change. Excluding only `wallet.db` would have left a
 *    `-wal` full of un-checkpointed rows eligible for backup.
 *
 * 📌 **Measured on a device 2026-09-04, and one file is NOT sealed**:
 *
 *     wallet.db      class=NSFileProtectionComplete                         locked -> EPERM
 *     wallet.db-wal  class=NSFileProtectionComplete                         locked -> EPERM
 *     wallet.db-shm  class=…CompleteUntilFirstUserAuthentication            locked -> OPENS
 *
 * The `-shm` does not inherit the directory's class and is **left that way deliberately**. It holds the
 * shared-memory *index* of WAL frames — page numbers, commit markers, checksums — while the rows live in
 * the `-wal`, which is sealed; so what stays readable while locked is closer to traffic analysis than to
 * content. Apple's SQLite picks the weaker class for it because processes coordinate through that file,
 * and forcing `Complete` risks breaking WAL across a lock/unlock — a functional bug traded for a
 * metadata exposure. ⛔ Do not "fix" it without measuring what it breaks.
 *
 * ⚠️ **Known WAL risk, not yet observed here**: a suspended process holding a read snapshot can prevent
 * checkpointing, so the `-wal` grows. With the app and the document-provider extension both able to open
 * this store, iOS suspending one at the wrong moment is the way to hit it. Watch the file size if the
 * store ever bloats.
 *
 * Everything else mirrors `IosStorage` exactly, including the backup exclusion and the single-thread
 * context, because the point is to change one pragma and nothing else.
 */
@OptIn(ExperimentalForeignApi::class, DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
internal class WalletSqliteStorage(
    storageFileUrl: NSURL,
) : SqliteStorage(
    connection = openConnection(storageFileUrl),
    coroutineContext = newSingleThreadContext("WalletDB"),
) {
    internal companion object {

        /** Long enough to outlast a launch sweep; short enough that a true deadlock still reports. */
        internal const val BUSY_TIMEOUT_MS = 5_000

        /**
         * Applied in order, and both matter.
         *
         * `journal_mode=WAL` is what actually removes the contention: under the default rollback
         * journal a writer takes an exclusive lock and readers get `SQLITE_BUSY`, while WAL lets a
         * reader continue against the last committed snapshot while a writer appends. Both launch
         * sweeps write, so this is on the exact path that failed.
         *
         * `busy_timeout` stays anyway. WAL still serialises *writers*, so two of them can contend, and
         * a timeout is what makes the second wait rather than fail.
         */
        internal val PRAGMAS = listOf(
            "journal_mode" to "WAL",
            "busy_timeout" to "$BUSY_TIMEOUT_MS",
        )

        private const val TAG = "WalletSqliteStorage"

        internal fun openConnection(storageFileUrl: NSURL): SQLiteConnection {
            // ⛔ Backup exclusion is NOT set here any more — it is set on the *directory* by
            // `MultipazWalletStore.storeFileUrl`, because `NSURLIsExcludedFromBackupKey` is a per-item
            // resource value that new siblings do not inherit. Under WAL the database has two of them
            // (`-wal`, `-shm`), and a `-wal` carries rows that have not been checkpointed yet — so
            // excluding only `wallet.db` would have let recently written credential data reach a backup
            // while the database itself was kept out of one. Directory exclusion cascades; `657a8077`
            // established that the hard way, when multipaz's own helper set it there and silently
            // excluded `Platform.storage` too.
            val connection = NativeSQLiteDriver().open(storageFileUrl.path!!)
            // Reported rather than assumed: a pragma that failed to take would leave the original
            // defect in place while looking exactly like the fix.
            for ((pragma, value) in PRAGMAS) {
                runCatching { connection.execSQL("PRAGMA $pragma = $value") }
                    .onFailure { Logger.w(TAG, "PRAGMA $pragma was refused: ${it.message}") }
            }
            // `journal_mode` is the one pragma that answers, and a refusal is silent: SQLite returns the
            // mode it actually used rather than failing, so asking is the only way to know.
            val mode = runCatching {
                connection.prepare("PRAGMA journal_mode").use { statement ->
                    if (statement.step()) statement.getText(0) else null
                }
            }.getOrNull()
            if (mode?.lowercase() != "wal") {
                Logger.w(TAG, "journal_mode is '$mode', not WAL — the launch race is only softened, not removed")
            } else {
                Logger.i(TAG, "journal_mode=WAL busy_timeout=${BUSY_TIMEOUT_MS}ms")
            }
            return connection
        }
    }
}
