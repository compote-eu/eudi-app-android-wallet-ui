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

// M1b, re-run at M6a on the real export (was provisional on the boundary fixture): the P4 round trip on the exported
// bundle.
package eu.europa.ec.ux.runtime.host

import androidx.lifecycle.SavedStateHandle
import eu.europa.ec.ux.runtime.engine.AbandonReason
import eu.europa.ec.ux.runtime.engine.ExitReason
import eu.europa.ec.ux.runtime.engine.FlowPlatform
import eu.europa.ec.ux.runtime.engine.FlowStatus
import eu.europa.ec.ux.runtime.engine.RestoreResult
import eu.europa.ec.ux.runtime.support.FakePort
import eu.europa.ec.ux.runtime.support.Fixture
import eu.europa.ec.ux.runtime.support.QR_URI
import eu.europa.ec.ux.runtime.support.RecordingLog
import eu.europa.ec.ux.runtime.support.Results
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * P4: the snapshot lives in the entry view model's [SavedStateHandle]. Each test drives a [FlowViewModel] to a hard
 * state, kills it ([processDeath] writes the handle out the way the platform does and builds a new handle from that
 * saved state), and checks that a new view model, with a new port, recreates the engine in the state the bundle
 * policy names (brief §5): request = snapshot, auth = back to request, sending = error with no second send; iOS
 * abandons. The platform subclasses supply the real saved-state round trip.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class FlowViewModelSavedStateScenario {

    /** Saves [handle] through its saved-state provider, as the platform does at process death, and restores it. */
    abstract fun processDeath(handle: SavedStateHandle): SavedStateHandle

    private val launch: JsonObject = buildJsonObject { put("uri", QR_URI) }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun model(handle: SavedStateHandle, port: FakePort, platform: FlowPlatform = FlowPlatform.ANDROID) =
        FlowViewModel(handle, Fixture.bundle(), port, ENTRY, launch, platform, RecordingLog())

    private fun FlowViewModel.active(): FlowStatus.Active = assertIs<FlowStatus.Active>(status.value)

    private fun FlowViewModel.send(event: String) = dispatch(event, JsonObject(emptyMap()), active().gen)

    private fun toRequest(handle: SavedStateHandle, port: FakePort = FakePort()): FlowViewModel {
        port.auto["fetchRequest"] = Results.requestReady
        return model(handle, port).also { assertEquals("request", it.active().state) }
    }

    @Test
    fun requestRestoresFromTheSnapshot() {
        val handle = SavedStateHandle()
        val first = toRequest(handle)
        val before = first.active().context

        val port = FakePort()
        val second = model(processDeath(handle), port)

        assertEquals(RestoreResult.Restored("request", reinvoked = false), second.restored)
        assertEquals("request", second.active().state)
        assertEquals(before, second.active().context)
        assertEquals(0, port.total)
    }

    @Test
    fun authRestoresToRequestWithoutAuthenticating() {
        val handle = SavedStateHandle()
        val first = toRequest(handle)
        first.send("CONTINUE")
        assertEquals("auth", first.active().state)

        val port = FakePort()
        val second = model(processDeath(handle), port)

        assertEquals("request", second.active().state)
        assertEquals(0, port.calls("authenticate"))
    }

    @Test
    fun sendingRestoresToErrorAndNeverSendsAgain() {
        val handle = SavedStateHandle()
        val port1 = FakePort().apply {
            auto["authenticate"] = Results.authenticated
            auto["verificationCode"] = Results.codeReady
        }
        val first = toRequest(handle, port1)
        first.send("CONTINUE")
        assertEquals("verification-code", first.active().state)
        first.send("CONFIRM")
        assertEquals("sending", first.active().state)
        assertEquals(1, port1.waiting("send"))

        val port2 = FakePort()
        val second = model(processDeath(handle), port2)

        assertEquals("error", second.active().state)
        assertEquals(0, port2.calls("send"))
        assertEquals(1, port1.calls("send"))
    }

    @Test
    fun iosAbandonsTheSavedFlow() {
        val handle = SavedStateHandle()
        toRequest(handle).send("CONTINUE")

        val port = FakePort()
        val second = model(processDeath(handle), port, FlowPlatform.IOS)

        assertEquals(RestoreResult.Abandoned(AbandonReason.PLATFORM_POLICY), second.restored)
        val exited = assertIs<FlowStatus.Exited>(second.status.value)
        assertEquals(ExitReason.ABANDONED, exited.reason)
        assertEquals(Fixture.bundle().abandonState, exited.exit?.state)
        assertEquals(0, port.total)
    }

    @Test
    fun theHandleHoldsTheEngineSnapshotAndForgetsItAtExit() {
        val handle = SavedStateHandle()
        val flow = toRequest(handle)
        val restoredHandle = processDeath(handle)
        assertNotNull(restoredHandle.get<Any>(FlowViewModel.SNAPSHOT_KEY), "the snapshot key was not saved")

        flow.send("REJECT")
        assertEquals("cancelled", flow.active().state)
        flow.send("CLOSE")
        assertIs<FlowStatus.Exited>(flow.status.value)

        val port = FakePort().apply { auto["fetchRequest"] = Results.requestReady }
        val next = model(processDeath(handle), port)
        assertNull(next.restored)
        assertEquals("request", next.active().state)
        assertEquals(1, port.calls("fetchRequest"))
    }

    private companion object {
        const val ENTRY = "deeplink"
    }
}
