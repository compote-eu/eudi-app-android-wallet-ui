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

package eu.europa.ec.shared.wallet.log

import eu.europa.ec.shared.wallet.config.iosWalletConfig
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.multipaz.util.Logger
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * iOS's log file: where it lives, and getting multipaz to write it.
 *
 * The design is taken from the official iOS wallet, which lets **its engine own the log** — it hands
 * `EudiWallet` a `logFileName` at init and later asks `EudiWallet.getLogFileURL(name)` for the URL to
 * share. multipaz offers the same thing to us (`Logger.startLoggingToFile`), so iOS gets a real log
 * without this fork implementing a logging framework, and everything multipaz already narrates —
 * issuance, presentment, the trust evaluation behind revocation — lands in it for free.
 *
 * Android's counterpart is `LogController` in `:business-logic`, a Treessence `FileLoggerTree`.
 *
 * ## Two limits, both inherited and both deliberate rather than overlooked
 *
 * **It truncates.** `Logger.startLoggingToFile` opens `SystemFileSystem.sink(path)` without append, so
 * each launch starts an empty file: what you can share is *this* session. Android keeps 10 files of
 * 5 MB and rotates. Matching Android would mean writing our own rotation on top of multipaz's sink,
 * which is a bigger thing than this feature; matching the official iOS wallet means accepting the
 * single truncating file, which is what it does too.
 *
 * **It has no size cap.** A long session grows the file unbounded. `Library/Caches` is therefore the
 * right home: iOS may reclaim it under storage pressure, and it is excluded from device backups,
 * which is what a diagnostic log should be. `NSCachesDirectory` is created by the system, so unlike
 * the official iOS wallet — which makes its directory and an empty file before sharing — nothing has
 * to be prepared here. Note this is *not* where the wallet's data lives; that is multipaz's own
 * non-backed-up store, so losing this directory costs nothing but diagnostics.
 */
object IosLogFile {

    /**
     * The log file's absolute path, whether or not anything has been written to it yet.
     *
     * Null only if iOS reports no caches directory, which does not happen on a real device but is not
     * worth crashing over: the caller then reports "no logs", the same answer a platform without
     * logging gives.
     */
    val path: String? by lazy {
        val caches = NSSearchPathForDirectoriesInDomains(
            NSCachesDirectory,
            NSUserDomainMask,
            true,
        ).firstOrNull() as? String ?: return@lazy null

        "$caches/${iosWalletConfig.logFileName}"
    }

    /**
     * Points multipaz's logger at [path]. Idempotent by way of multipaz, which closes and reopens an
     * existing sink rather than failing.
     *
     * Called once at startup, before anything the log should capture. Failure is swallowed: a wallet
     * that cannot open a diagnostic file must still start, and the only symptom is an empty log.
     */
    fun start() {
        val target = path ?: return
        try {
            Logger.startLoggingToFile(Path(target))
            Logger.i(TAG, "logging to $target")
        } catch (t: Throwable) {
            // Deliberately not rethrown — see above. The console still gets multipaz's output.
            Logger.w(TAG, "could not open the log file at $target", t)
        }
    }

    /** Whether there is a log file with anything in it, which is what the settings row asks. */
    fun hasContent(): Boolean {
        val target = path ?: return false
        val metadata = try {
            SystemFileSystem.metadataOrNull(Path(target))
        } catch (_: Throwable) {
            null
        }
        return (metadata?.size ?: 0L) > 0L
    }

    private const val TAG = "IosLogFile"
}
