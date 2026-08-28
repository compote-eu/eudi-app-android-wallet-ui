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
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import org.multipaz.util.Logger
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import kotlin.time.Clock

/**
 * iOS's log: a rotating set of files, written by multipaz's own logger.
 *
 * The shape is taken from the official iOS wallet, which lets **its engine own the log** — it hands
 * `EudiWallet` a `logFileName` at init and later asks `EudiWallet.getLogFileURL(name)` for the URL to
 * share. multipaz gives us the same reach, so iOS gets a real log without this fork implementing a
 * logging framework: everything multipaz narrates — issuance, presentment, the trust evaluation behind
 * revocation — lands in it alongside our own iosMain call sites.
 *
 * ## Why this does not simply call `Logger.startLoggingToFile`
 *
 * It did at first, and that is what the official iOS wallet settles for. Two properties of multipaz's
 * own file writer made it worth replacing:
 *
 *  - **it truncates.** `startLoggingToFile` opens `SystemFileSystem.sink(path)` with no `append`, so
 *    every launch began an empty file and the previous session's log was simply gone.
 *  - **it has no size cap**, so a long-lived session grew one file without bound.
 *
 * Android has neither problem: `LogController` plants a Treessence `FileLoggerTree` with
 * `withSizeLimit`, `withFileLimit` and `appendToFile(true)`. [FILE_SIZE_LIMIT] and [FILE_LIMIT] are
 * those same numbers, so the two platforms keep a comparable amount of history and share a comparable
 * bundle.
 *
 * The hook is `Logger.logPrinter`, which multipaz consults for **every** entry. Two consequences worth
 * knowing before touching this:
 *
 *  - it **replaces** the platform printer rather than adding to it (`printLine` reads
 *    `this.logPrinter ?: getPlatformLogPrinter()`), so this class owes the console the output that
 *    printer used to provide — hence the [println]. `getPlatformLogPrinter` is `internal` to multipaz,
 *    so it cannot be delegated to.
 *  - `Logger.startLoggingToFile` is deliberately **not** called. Its writer is independent of
 *    `logPrinter`, so it would duplicate every line into a second, non-rotating file.
 *
 * ## Location
 *
 * `Library/Caches/logs`, the counterpart of Android's `filesDir/logs`, with one deliberate difference:
 * Caches is excluded from device backups and reclaimable under storage pressure, which is what a
 * diagnostic log should be. Android's `filesDir` is *not* excluded — that project's `backup_rules.xml`
 * and `data_extraction_rules.xml` are both empty templates — so its logs are eligible for cloud
 * backup. The asymmetry favours iOS and is worth keeping. This is not where wallet data lives; that is
 * multipaz's own non-backed-up store, so losing this directory costs nothing but diagnostics.
 */
object IosLogFile {

    /** Android's `FILE_SIZE_LIMIT`, so a generation holds a comparable amount on both platforms. */
    private const val FILE_SIZE_LIMIT = 5_242_880L

    /** Android's `FILE_LIMIT`: the file being written plus nine older generations. */
    private const val FILE_LIMIT = 10

    /**
     * Guards [sink] and the rotation that swaps it.
     *
     * multipaz calls its printer from whatever thread emitted the entry and Kotlin/Native has no
     * `@Synchronized`, so two concurrent writes to one buffered sink could interleave or throw.
     * `atomicfu`'s `ReentrantLock` is the answer `NativeSecurePin` already uses here. Note multipaz's
     * own file writer takes no such lock, which is one more reason not to use it.
     */
    private val lock: ReentrantLock = reentrantLock()

    private var sink: Sink? = null

    /** The directory holding the rotating set. Mirrors Android's `filesDir/logs`. */
    private val directory: Path? by lazy {
        val caches = NSSearchPathForDirectoriesInDomains(
            NSCachesDirectory,
            NSUserDomainMask,
            true,
        ).firstOrNull() as? String ?: return@lazy null

        Path("$caches/logs")
    }

    /**
     * The file currently being written, generation 0.
     *
     * Null only if iOS reports no caches directory, which does not happen on a device but is not worth
     * crashing over: callers then report "no logs", the same answer a platform without logging gives.
     */
    val path: String? get() = generation(0)?.toString()

    /**
     * Every generation that has content, newest first — what the settings row offers for sharing.
     *
     * Android shares its whole rotating set, so this hands the user a comparable bundle rather than the
     * single file the first version of this class could offer.
     */
    fun paths(): List<String> = lock.withLock {
        (0 until FILE_LIMIT)
            .mapNotNull { index -> generation(index) }
            .filter { candidate -> sizeOf(candidate) > 0L }
            .map { candidate -> candidate.toString() }
    }

    /**
     * Installs the printer, **appending** to the current generation rather than truncating it.
     *
     * Called once at startup, before anything the log should capture. Failure is swallowed: a wallet
     * that cannot open a diagnostic file must still start, and the symptom is console-only logging.
     */
    fun start() {
        val dir = directory ?: return
        try {
            SystemFileSystem.createDirectories(dir, mustCreate = false)
            lock.withLock { openAppending() }
            Logger.logPrinter = LogPrinter
            Logger.i(TAG, "logging to $dir, up to $FILE_LIMIT x $FILE_SIZE_LIMIT bytes")
        } catch (t: Throwable) {
            // Not rethrown — see above. The printer is left uninstalled, so multipaz keeps its own
            // console output and nothing else changes.
            println("$TAG: could not open the log directory at $dir: $t")
        }
    }

    /**
     * Every entry multipaz emits: to the console, because taking over `logPrinter` took that away, and
     * to the current generation, rotating first when it is full.
     */
    private object LogPrinter : Logger.LogPrinter {
        override fun print(
            level: Logger.LogPrinter.Level,
            tag: String,
            msg: String,
            throwable: Throwable?,
        ) {
            val line = prepareLine(level, tag, msg, throwable)
            // Restores what `getPlatformLogPrinter` used to do; `simctl launch --console-pty` and the
            // Xcode console both pick this up.
            println(line)
            append(line)
        }
    }

    private fun append(line: String) = lock.withLock {
        val open = sink ?: return@withLock
        try {
            if (sizeOf(generation(0)) >= FILE_SIZE_LIMIT) {
                rotate()
            }
            (sink ?: return@withLock).apply {
                writeString(line + "\n")
                flush()
            }
        } catch (t: Throwable) {
            // A failed write must not take down whatever was being logged. Drop the sink so the app
            // carries on with console-only output instead of throwing on every subsequent entry.
            runCatching { open.close() }
            sink = null
            println("$TAG: log write failed, continuing without a file: $t")
        }
    }

    /**
     * Shifts every generation up by one and starts a fresh 0, dropping the oldest.
     *
     * The caller holds [lock]. `atomicMove` rather than copy-then-delete: the set must never contain
     * two files claiming the same generation, which a half-finished copy would produce.
     */
    private fun rotate() {
        sink?.let { open -> runCatching { open.close() } }
        sink = null

        generation(FILE_LIMIT - 1)?.let { oldest ->
            SystemFileSystem.delete(oldest, mustExist = false)
        }
        for (index in FILE_LIMIT - 2 downTo 0) {
            val from = generation(index) ?: continue
            val to = generation(index + 1) ?: continue
            if (SystemFileSystem.exists(from)) {
                SystemFileSystem.atomicMove(from, to)
            }
        }
        openAppending()
    }

    /** The caller holds [lock]. */
    private fun openAppending() {
        val target = generation(0) ?: return
        sink = SystemFileSystem.sink(target, append = true).buffered()
    }

    /**
     * Generation [index]'s path, substituting Android's `%g` placeholder so the two names line up:
     * `eudi-android-wallet-logs%g.txt` there, `eudi-ios-wallet-logs%g.txt` here. A configured name
     * without the placeholder still rotates — the index is prefixed instead — so a mis-set config
     * degrades rather than collapsing every generation onto one file.
     */
    private fun generation(index: Int): Path? {
        val dir = directory ?: return null
        val name = iosWalletConfig.logFileName
        val resolved =
            if (name.contains(GENERATION)) name.replace(GENERATION, index.toString())
            else "$index-$name"
        return Path(dir, resolved)
    }

    private fun sizeOf(candidate: Path?): Long =
        candidate?.let { runCatching { SystemFileSystem.metadataOrNull(it)?.size }.getOrNull() } ?: 0L

    /**
     * multipaz's own line format, mirrored because `Logger.prepareLine` is `internal` to it. Keeping
     * the shape identical means a line written through this printer reads the same as one multipaz
     * wrote itself, which matters when comparing an iOS log against the console or against Android's.
     */
    private fun prepareLine(
        level: Logger.LogPrinter.Level,
        tag: String,
        msg: String,
        throwable: Throwable?,
    ): String {
        val timestamp = Clock.System.now()
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .format(LocalDateTime.Formats.ISO)
        val suffix = throwable?.let { "\nEXCEPTION: $it" } ?: ""
        return "$timestamp: ${level.name}: $tag: $msg$suffix"
    }

    private const val GENERATION = "%g"
    private const val TAG = "IosLogFile"
}
