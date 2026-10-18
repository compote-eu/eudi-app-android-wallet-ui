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

// M1b, re-run at M6a on the real export; M9r (spike rule T1): the P4 round trip on the synthetic test bundle (one
// state per restore policy, test-owned names). The export's own restore policies: ExportedPoliciesTest.
package eu.europa.ec.ux.runtime.host

import androidx.lifecycle.SavedStateHandle
import eu.europa.ec.ux.runtime.engine.AbandonReason
import eu.europa.ec.ux.runtime.engine.ExitReason
import eu.europa.ec.ux.runtime.engine.FlowPlatform
import eu.europa.ec.ux.runtime.engine.FlowStatus
import eu.europa.ec.ux.runtime.engine.RestoreResult
import eu.europa.ec.ux.runtime.support.FakePort
import eu.europa.ec.ux.runtime.support.RecordingLog
import eu.europa.ec.ux.runtime.support.SynthResults
import eu.europa.ec.ux.runtime.support.Synthetic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * P4: the snapshot lives in the entry view model's [SavedStateHandle]. Each test drives a [FlowViewModel] to a state of
 * one restore policy, kills it ([processDeath] writes the handle out the way the platform does and builds a new handle
 * from that saved state), and checks that a new view model, with a new port, recreates the engine where the policy says:
 * snapshot = the same state and context, to_history = back to the choice with no call, to = the recovery state with no
 * second non-idempotent call; an abandon platform abandons. The platform subclasses supply the real saved-state round trip.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class FlowViewModelSavedStateScenario {

    /** Saves [handle] through its saved-state provider, as the platform does at process death, and restores it. */
    abstract fun processDeath(handle: SavedStateHandle): SavedStateHandle

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun model(handle: SavedStateHandle, port: FakePort, platform: FlowPlatform = FlowPlatform.ANDROID) =
        FlowViewModel(handle, Synthetic.bundle(), port, Synthetic.LINK, Synthetic.launch, platform, RecordingLog())

    private fun FlowViewModel.active(): FlowStatus.Active = assertIs<FlowStatus.Active>(status.value)

    private fun FlowViewModel.send(event: String) = dispatch(event, JsonObject(emptyMap()), active().gen)

    private fun toChoose(handle: SavedStateHandle, port: FakePort = FakePort()): FlowViewModel {
        port.auto[Synthetic.SVC_LOAD] = SynthResults.loaded
        return model(handle, port).also { assertEquals(Synthetic.CHOOSE, it.active().state) }
    }

    @Test
    fun aSnapshotStateRestoresFromTheSnapshot() {
        val handle = SavedStateHandle()
        val first = toChoose(handle)
        val before = first.active().context

        val port = FakePort()
        val second = model(processDeath(handle), port)

        assertEquals(RestoreResult.Restored(Synthetic.CHOOSE, reinvoked = false), second.restored)
        assertEquals(Synthetic.CHOOSE, second.active().state)
        assertEquals(before, second.active().context)
        assertEquals(0, port.total)
    }

    @Test
    fun aToHistoryStateRestoresToTheChoiceWithoutCalling() {
        val handle = SavedStateHandle()
        val first = toChoose(handle)
        first.send(Synthetic.GO)
        assertEquals(Synthetic.CHECK, first.active().state)

        val port = FakePort()
        val second = model(processDeath(handle), port)

        assertEquals(Synthetic.CHOOSE, second.active().state)
        assertEquals(0, port.calls(Synthetic.SVC_CHECK))
    }

    @Test
    fun aNonIdempotentStateRestoresToItsTargetAndNeverCallsAgain() {
        val handle = SavedStateHandle()
        val port1 = FakePort().apply {
            auto[Synthetic.SVC_CHECK] = SynthResults.passed
            auto[Synthetic.SVC_PRODUCE] = SynthResults.made
        }
        val first = toChoose(handle, port1)
        first.send(Synthetic.GO)
        assertEquals(Synthetic.CODE, first.active().state)
        first.send(Synthetic.CONFIRM)
        assertEquals(Synthetic.COMMIT, first.active().state)
        assertEquals(1, port1.waiting(Synthetic.SVC_COMMIT))

        val port2 = FakePort()
        val second = model(processDeath(handle), port2)

        assertEquals(Synthetic.FAILED, second.active().state)
        assertEquals(0, port2.calls(Synthetic.SVC_COMMIT))
        assertEquals(1, port1.calls(Synthetic.SVC_COMMIT))
    }

    @Test
    fun anAbandonPlatformAbandonsTheSavedFlow() {
        val handle = SavedStateHandle()
        toChoose(handle).send(Synthetic.GO)

        val port = FakePort()
        val second = model(processDeath(handle), port, FlowPlatform.IOS)

        assertEquals(RestoreResult.Abandoned(AbandonReason.PLATFORM_POLICY), second.restored)
        val exited = assertIs<FlowStatus.Exited>(second.status.value)
        assertEquals(ExitReason.ABANDONED, exited.reason)
        assertEquals(Synthetic.CLOSED, exited.exit?.state)
        assertEquals(0, port.total)
    }

    @Test
    fun theHandleHoldsTheEngineSnapshotAndForgetsItAtExit() {
        val handle = SavedStateHandle()
        val flow = toChoose(handle)
        val restoredHandle = processDeath(handle)
        assertNotNull(restoredHandle.get<Any>(FlowViewModel.SNAPSHOT_KEY), "the snapshot key was not saved")

        flow.send(Synthetic.ABORT)
        assertEquals(Synthetic.ABORTED, flow.active().state)
        flow.send(Synthetic.CLOSE)
        assertIs<FlowStatus.Exited>(flow.status.value)

        val port = FakePort().apply { auto[Synthetic.SVC_LOAD] = SynthResults.loaded }
        val next = model(processDeath(handle), port)
        assertNull(next.restored)
        assertEquals(Synthetic.CHOOSE, next.active().state)
        assertEquals(1, port.calls(Synthetic.SVC_LOAD))
    }
}
