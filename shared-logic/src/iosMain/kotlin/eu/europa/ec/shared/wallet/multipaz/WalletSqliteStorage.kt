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
import platform.Foundation.NSURLIsExcludedFromBackupKey

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
 * 🚩 `journal_mode = WAL` would be the stronger change and is **deliberately not made here**: it adds
 * `-wal` and `-shm` sidecars that would need the same `NSFileProtectionComplete` and backup treatment
 * as the database, and getting a file attribute onto the wrong thing has already cost this project
 * once. Decide it separately, with a device to check it on.
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

        private const val TAG = "WalletSqliteStorage"

        internal fun openConnection(storageFileUrl: NSURL): SQLiteConnection {
            storageFileUrl.setResourceValue(
                value = true,
                forKey = NSURLIsExcludedFromBackupKey,
                error = null,
            )
            val connection = NativeSQLiteDriver().open(storageFileUrl.path!!)
            // Reported rather than assumed: a pragma that failed to take would leave the original
            // defect in place while looking exactly like the fix.
            runCatching { connection.execSQL("PRAGMA busy_timeout = $BUSY_TIMEOUT_MS") }
                .onFailure { Logger.w(TAG, "busy_timeout was refused: ${it.message}") }
            return connection
        }
    }
}
