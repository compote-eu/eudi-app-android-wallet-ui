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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.multipaz.util.Logger
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Re-issuance and revocation, re-checked while the wallet is in front — iOS's counterpart of Android's
 * two WorkManager workers, which repeat every 15 minutes.
 *
 * Until 2026-10-02 both ran once per launch, and the revocation run finished half a second after launch,
 * before anyone could pass the PIN screen. Its "documents revoked" message reached no screen, and a
 * revocation found while the app stayed open was never found at all.
 *
 * **Only while the app is active, by decision.** The app shell starts this when the app becomes active
 * and stops it when it resigns active. The wallet database carries `NSFileProtectionComplete`, which is
 * unreadable while the device is locked, so nothing may run in the background (see
 * [runBackgroundRevocation]). The official iOS wallet runs the same two loops from its `AppDelegate`.
 */
object IosForegroundChecks {

    /**
     * The official iOS wallet's first-run delay. Long enough for the user to reach the dashboard, which
     * is where the "documents revoked" message is shown.
     */
    private val INITIAL_DELAY = 30.seconds

    /** Android's cadence for both workers (`revocationInterval`, `reissuanceRule.backgroundInterval`). */
    private val INTERVAL = 15.minutes

    // After the two constants above: an object's properties initialize in order.
    private val schedule = RepeatingCheck(
        scope = CoroutineScope(Dispatchers.Main),
        initialDelay = INITIAL_DELAY,
        interval = INTERVAL,
        run = {
            Logger.i(TAG, "foreground re-issuance: ${runBackgroundReIssuance()}")
            Logger.i(TAG, "foreground revocation: ${runBackgroundRevocation()}")
        },
    )

    /** Called by the app shell when the app becomes active. */
    fun onActive() = schedule.start()

    /** Called by the app shell when the app resigns active. */
    fun onInactive() = schedule.stop()

    private const val TAG = "IosForegroundChecks"
}

/**
 * Runs [run] [initialDelay] after [start], then every [interval], until [stop].
 *
 * The cadence survives a stop and start: a run that happened less than [interval] ago is not repeated
 * just because the app came back to the front, and a restart after a longer absence still waits
 * [initialDelay]. A failure in one run is logged and does not end the schedule.
 */
internal class RepeatingCheck(
    private val scope: CoroutineScope,
    private val initialDelay: Duration,
    private val interval: Duration,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    private val run: suspend () -> Unit,
) {
    private var job: Job? = null
    private var lastRun: ComparableTimeMark? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                delay(nextWait())
                lastRun = timeSource.markNow()
                try {
                    run()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    Logger.w(TAG, "a check failed; the schedule continues", failure)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun nextWait(): Duration {
        val sinceLast = lastRun?.elapsedNow() ?: return initialDelay
        return maxOf(initialDelay, interval - sinceLast)
    }

    private companion object {
        const val TAG = "RepeatingCheck"
    }
}
