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

import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * That the wallet's SQLite connection waits instead of failing when another connection holds the lock.
 *
 * The defect this guards was found on a device, not by reading: a probe died with
 * `SQLiteException: Error code: 5, message: database is locked` in 2 of 5 runs, because
 * multipaz's `IosStorage` opens with no pragmas and SQLite's default busy timeout is **0** — a
 * contended reader fails outright rather than retrying. The app makes two connections at every launch
 * (`reconcileDocumentRegistrations` and `refreshRevocationOnLaunch`), so the race is its own.
 *
 * The assertion is on the pragma rather than on a staged collision: forcing two connections to contend
 * deterministically needs one to hold a write transaction open across the other's read, which a unit
 * test cannot arrange without becoming a timing test. What *is* worth pinning is that the pragma is
 * applied at all — a value that silently failed to take would leave the original defect in place while
 * looking exactly like the fix.
 */
class WalletSqliteStorageTest {

    private fun temporaryUrl(): NSURL = NSURL.fileURLWithPath(
        "/tmp/wallet-busy-timeout-${NSUUID().UUIDString()}.db"
    )

    @Test
    fun the_connection_carries_a_busy_timeout() {
        val connection = WalletSqliteStorage.openConnection(temporaryUrl())

        val applied = connection.prepare("PRAGMA busy_timeout").use { statement ->
            assertTrue(statement.step(), "PRAGMA busy_timeout returned no row")
            statement.getLong(0)
        }
        connection.close()

        assertEquals(
            WalletSqliteStorage.BUSY_TIMEOUT_MS.toLong(),
            applied,
            "the connection would fail instantly on contention instead of waiting",
        )
    }

    @Test
    fun the_default_would_have_been_zero() {
        // Not a tautology about SQLite: it pins *why* the pragma is needed. If a future driver or
        // multipaz version started defaulting to a non-zero timeout, this failing is the signal that
        // `WalletSqliteStorage` may no longer be earning its place.
        val bare = androidx.sqlite.driver.NativeSQLiteDriver().open(temporaryUrl().path!!)

        val default = bare.prepare("PRAGMA busy_timeout").use { statement ->
            statement.step()
            statement.getLong(0)
        }
        bare.close()

        assertEquals(0L, default, "SQLite's default busy timeout is no longer 0 — re-read the KDoc")
    }
}
