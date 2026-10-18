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

import eu.europa.ec.shared.wallet.platform.IosKeychainAccessGroup
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.decodeToString
import kotlinx.io.bytestring.encodeToByteString
import org.multipaz.storage.StorageTableSpec
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * multipaz's storage contract, run against [KeychainWalletStorage] in the app.
 *
 * A probe rather than a unit test because the Keychain cannot be reached from a Kotlin/Native test
 * binary: every `SecItem` call there returns `errSecNotAvailable`. The checks are the ones multipaz's
 * own storage suite makes, minus those that only exercise `BaseStorage`, plus the ones a partitioned
 * read has to keep since `enumerate` lists names first and reads data only for the items it returns.
 *
 *     xcrun simctl launch --console-pty <device> <bundle-id> --keychain-storage-probe
 */
@OptIn(ExperimentalForeignApi::class)
fun probeKeychainStorage(onResult: (String) -> Unit) {
    CoroutineScope(Dispatchers.Main).launch {
        val checks = Checks(onResult)
        val prefix = "eu.europa.ec.probe.keychain.${Clock.System.now().toEpochMilliseconds()}"
        try {
            val clock = MovableClock()
            val storage = KeychainWalletStorage(servicePrefix = prefix, clock = clock)

            // ---- the basics, on a table with neither partitions nor expiration ----
            val plain = storage.getTable(StorageTableSpec("ProbePlain", false, false))
            val generated = plain.insert(key = null, data = "hello".encodeToByteString())
            checks.check("insert(key=null) mints a key", generated.isNotEmpty())
            checks.check("get returns what insert stored", plain.get(generated)?.decodeToString() == "hello")
            plain.insert(key = "explicit", data = "one".encodeToByteString())
            checks.check("insert(explicit key) round-trips", plain.get("explicit")?.decodeToString() == "one")
            checks.check("get of an absent key is null", plain.get("nope") == null)
            plain.update("explicit", "two".encodeToByteString())
            checks.check("update replaces the value", plain.get("explicit")?.decodeToString() == "two")
            checks.check(
                "insert on a live key throws KeyExistsStorageException",
                failureOf { plain.insert(key = "explicit", data = "three".encodeToByteString()) } ==
                    "KeyExistsStorageException",
            )
            checks.check(
                "update of an absent key throws NoRecordStorageException",
                failureOf { plain.update("absent", "x".encodeToByteString()) } == "NoRecordStorageException",
            )
            checks.check("delete reports true for a live key", plain.delete("explicit"))
            checks.check("delete reports false the second time", !plain.delete("explicit"))
            checks.check("keys are case sensitive", run {
                plain.insert(key = "Key", data = "upper".encodeToByteString())
                plain.insert(key = "key", data = "lower".encodeToByteString())
                plain.get("Key")?.decodeToString() == "upper" && plain.get("key")?.decodeToString() == "lower"
            })

            // ---- ordering, afterKey and limit within partitions ----
            val ordered = storage.getTable(StorageTableSpec("ProbeOrdered", true, false))
            val alpha = listOf("a", "b", "c", "d", "e")
            alpha.forEach { ordered.insert(key = it, data = "p1-$it".encodeToByteString(), partitionId = "p1") }
            ordered.insert(key = "z", data = "p2-z".encodeToByteString(), partitionId = "p2")
            checks.check(
                "enumerate returns a partition's keys in ascending order",
                ordered.enumerate(partitionId = "p1") == alpha,
            )
            checks.check("enumerate does not leak across partitions", ordered.enumerate(partitionId = "p2") == listOf("z"))
            checks.check(
                "enumerate(afterKey) resumes",
                ordered.enumerate(partitionId = "p1", afterKey = "b") == listOf("c", "d", "e"),
            )
            checks.check("enumerate(limit) truncates", ordered.enumerate(partitionId = "p1", limit = 2) == listOf("a", "b"))
            checks.check(
                "afterKey and limit together",
                ordered.enumerate(partitionId = "p1", afterKey = "a", limit = 2) == listOf("b", "c"),
            )
            checks.check("enumerate(limit = 0) is empty", ordered.enumerate(partitionId = "p1", limit = 0).isEmpty())
            checks.check(
                "enumerateWithData returns each item's own value, and only its partition's",
                ordered.enumerateWithData(partitionId = "p1").map { it.first to it.second.decodeToString() } ==
                    alpha.map { it to "p1-$it" },
            )
            checks.check(
                "deletePartition removes one partition only",
                run {
                    ordered.deletePartition("p1")
                    ordered.enumerate(partitionId = "p1").isEmpty() && ordered.enumerate(partitionId = "p2") == listOf("z")
                },
            )

            // ---- expiration, which rides inside the value ----
            val expiring = storage.getTable(StorageTableSpec("ProbeExpiring", false, true))
            clock.time = Instant.fromEpochMilliseconds(1_000_000)
            expiring.insert(key = "soon", data = "gone".encodeToByteString(), expiration = Instant.fromEpochMilliseconds(2_000_000))
            expiring.insert(key = "later", data = "stays".encodeToByteString(), expiration = Instant.fromEpochMilliseconds(9_000_000))
            checks.check("before expiry both are visible", expiring.enumerate().size == 2)
            clock.time = Instant.fromEpochMilliseconds(5_000_000)
            checks.check("an expired record reads as absent", expiring.get("soon") == null)
            checks.check("an unexpired one survives", expiring.get("later") != null)
            checks.check("expired records vanish from enumerate", expiring.enumerate() == listOf("later"))
            storage.purgeExpired()
            checks.check("purgeExpired keeps the live record", expiring.get("later") != null)

            // ---- an expired record must not use up a limit ----
            val partExpiring = storage.getTable(StorageTableSpec("ProbePartExpiring", true, true))
            clock.time = Instant.fromEpochMilliseconds(1_000_000)
            partExpiring.insert(
                key = "a", data = "a".encodeToByteString(), partitionId = "p",
                expiration = Instant.fromEpochMilliseconds(2_000_000),
            )
            listOf("b", "c", "d").forEach { partExpiring.insert(key = it, data = it.encodeToByteString(), partitionId = "p") }
            clock.time = Instant.fromEpochMilliseconds(5_000_000)
            checks.check(
                "an expired record is skipped before limit is applied",
                partExpiring.enumerate(partitionId = "p", limit = 2) == listOf("b", "c"),
            )

            // ---- size ----
            for (size in listOf(4 * 1024, 64 * 1024, 512 * 1024, 4 * 1024 * 1024)) {
                val blob = ByteString(Random.Default.nextBytes(size))
                val label = "${size / 1024} KiB"
                try {
                    val key = plain.insert(key = null, data = blob)
                    checks.check("a $label value round-trips", plain.get(key) == blob)
                } catch (e: Throwable) {
                    checks.check("a $label value round-trips", false, "${e::class.simpleName}: ${e.message}")
                }
            }

            // ---- persistence: two Storage instances over the same items ----
            val second = KeychainWalletStorage(servicePrefix = prefix, clock = clock)
            val reopened = second.getTable(StorageTableSpec("ProbePlain", false, false))
            checks.check("a second Storage sees the first one's records", reopened.get(generated)?.decodeToString() == "hello")

            checks.check("deleteAll empties the table", run {
                plain.deleteAll()
                plain.enumerate().isEmpty()
            })
            onResult(checks.summary())
        } catch (e: Throwable) {
            onResult("ABORTED: ${e::class.simpleName}: ${e.message}")
            onResult(checks.summary())
        } finally {
            val removed = KeychainWalletStorage.deleteEverythingUnder(prefix, IosKeychainAccessGroup.identifier())
            onResult("cleaned up $removed probe item(s)")
        }
    }
}

private class Checks(private val onResult: (String) -> Unit) {
    private var passed = 0
    private var failed = 0

    fun check(what: String, ok: Boolean, detail: String? = null) {
        if (ok) passed++ else failed++
        onResult((if (ok) "PASS " else "FAIL ") + what + (detail?.let { " — $it" } ?: ""))
    }

    fun summary(): String = "SUMMARY: $passed passed, $failed failed"
}

private suspend fun failureOf(block: suspend () -> Unit): String? = try {
    block()
    null
} catch (e: Throwable) {
    e::class.simpleName
}

private class MovableClock : Clock {
    var time: Instant = Instant.fromEpochMilliseconds(0)
    override fun now(): Instant = time
}
