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

package eu.europa.ec.uxspike

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import eu.europa.ec.ux.runtime.bundle.FlowBundle
import eu.europa.ec.ux.runtime.bundle.LoadResult
import eu.europa.ec.ux.runtime.engine.FlowLog
import eu.europa.ec.ux.runtime.engine.FlowPlatform
import eu.europa.ec.ux.runtime.engine.ServicePort
import eu.europa.ec.ux.runtime.host.FlowHost
import eu.europa.ec.ux.runtime.screen.CopyProvider
import eu.europa.ec.ux.theme.UxAssets
import eu.europa.ec.ux.theme.UxBrandSelections
import eu.europa.ec.ux.theme.UxComponentTokenTable
import eu.europa.ec.ux.theme.UxLibraryAssets
import eu.europa.ec.ux.theme.UxSpecResources
import eu.europa.ec.ux.theme.generated.UxBrandConfig
import eu.europa.ec.ux.theme.generated.UxThemeSpec
import eu.europa.ec.uxspike.fixture.ProbeFixture
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val FLOW = "probe"

private val files = ProbeFixture.Files

/** The export's screen probe as the hook reads it (the library gives the variant axes for brands the probe has no gating for). */
internal val probe: ScreenProbe by lazy { ScreenProbe.parse(files.HOME, files.LIBRARY) }

private fun load(brand: String, uiState: String, mode: String = "light"): LoadResult =
    FlowBundle.load(probe.bundle(brand, uiState, mode, FLOW).encodeToByteArray(), FLOW, probe.specHash, UxThemeSpec.SPEC_HASH)

/** The screen probe as data: selection, gating, the bundle it builds and its load through the handshake. */
class ScreenProbeTest {

    @Test
    fun theScriptNamesTheUiStateTheBrandAndTheMode() {
        val brands = UxBrandConfig.brands.map { it.id }.toSet()
        assertEquals(ProbeSelection(probe.uiStates.first(), probe.defaultBrand, "light"), ProbeSelection.parse("", probe, brands))
        assertEquals(ProbeSelection("dl-expiring", "idsk", "high-contrast"), ProbeSelection.parse("-dl-expiring.idsk.hc", probe, brands))
        assertEquals(ProbeSelection("offline", probe.defaultBrand, "dark"), ProbeSelection.parse("-offline.dark", probe, brands))
    }

    @Test
    fun theExportsGatingRemovesTheNodesItNamesAndAnUnlistedBrandGetsItsVariantsOmits() {
        val eudiw = probe.hidden("eudiw_v1")
        assertEquals(setOf("switch", "trustmark", "present"), eudiw, "the export's gating of the default brand")
        val ids = probe.nodes().map { it.second.let { n -> (n["id"] as kotlinx.serialization.json.JsonPrimitive).content } }
        assertTrue(eudiw.none { it in ids }, "the probe's screen already lacks them")
        assertEquals(setOf("bottom-bar"), probe.hidden("idsk"), "idsk: the library's bottom-nav axis omits the bar")
    }

    @Test
    fun everyBrandAndUiStateBuildsABundleTheHandshakeLoads() {
        UxBrandConfig.brands.map { it.id }.forEach { brand ->
            probe.uiStates.forEach { ui ->
                val r = load(brand, ui)
                assertTrue(r is LoadResult.Loaded, "$brand/$ui: $r")
                val screen = (r as LoadResult.Loaded).bundle.states.getValue("screen")
                assertEquals(ui, screen.uiState)
                assertEquals(brand, ((r.bundle.raw["theme"] as JsonObject)["default_brand"] as kotlinx.serialization.json.JsonPrimitive).content)
            }
        }
    }

    @Test
    fun aUiStatesDataOverridesTheContextExample() {
        val offline = (load(probe.defaultBrand, "offline") as LoadResult.Loaded).bundle.context.getValue("/network/online").default
        val online = (load(probe.defaultBrand, "default") as LoadResult.Loaded).bundle.context.getValue("/network/online").default
        assertEquals("false", offline.toString())
        assertEquals("true", online.toString())
    }
}

/** The visibility oracle: what the generic host draws for each ui state is what the export says is visible. */
@OptIn(ExperimentalTestApi::class)
abstract class ProbeOracleScenario {

    private val resources = UxSpecResources(
        assets = UxAssets.parse(files.ASSETS),
        library = UxLibraryAssets.parse(files.LIBRARY),
        componentTokens = UxComponentTokenTable.parse(files.TOKENS),
        selections = UxBrandSelections.parse(files.LIBRARY),
        painter = { ColorPainter(Color.Transparent) },
    )

    /** Hosts the probe in the (brand, ui state, mode) of [selection], a new host per change (one `setContent` per test). */
    private fun androidx.compose.ui.test.ComposeUiTest.host(selection: androidx.compose.runtime.MutableState<Triple<String, String, String>>) {
        setContent {
            val (brand, ui, mode) = selection.value
            androidx.compose.runtime.key(selection.value) {
                val bundle = (load(brand, ui, mode) as LoadResult.Loaded).bundle
                val owner = androidx.compose.runtime.remember {
                    object : ViewModelStoreOwner {
                        override val viewModelStore = ViewModelStore()
                    }
                }
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    FlowHost(
                        bundle = bundle,
                        port = ServicePort { service, _ -> error("no services ($service)") },
                        entry = "default",
                        launch = JsonObject(emptyMap()),
                        platform = FlowPlatform.IOS,
                        backHandler = { _, _ -> },
                        onExit = { },
                        screens = uxSpikeScreens(CopyProvider.NONE, resources),
                        log = FlowLog { },
                    )
                }
            }
        }
    }

    private fun ids(): List<String> = probe.nodes().map { (_, n) -> (n["id"] as kotlinx.serialization.json.JsonPrimitive).content }

    @Test
    fun theDrawnNodesAreExactlyTheOracleVisibleOnesForEveryUiStateOfTheDefaultBrand() = runComposeUiTest {
        val selection = androidx.compose.runtime.mutableStateOf(Triple(probe.defaultBrand, probe.uiStates.first(), "light"))
        host(selection)
        probe.uiStates.forEach { ui ->
            selection.value = Triple(probe.defaultBrand, ui, "light")
            waitForIdle()
            val expected = probe.expectedVisible(probe.defaultBrand, ui) ?: error("the export has no oracle for $ui")
            val gated = probe.hidden(probe.defaultBrand)
            val drawn = ids().filter { it !in gated && onAllNodes(hasTestTag(it), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }.toSet()
            assertEquals(expected, drawn, "ui state $ui")
        }
    }

    @Test
    fun anotherBrandDrawsTheOracleMinusWhatItsVariantsOmit() = runComposeUiTest {
        val brand = UxBrandConfig.brands.first { it.id != probe.defaultBrand }.id
        val selection = androidx.compose.runtime.mutableStateOf(Triple(brand, probe.uiStates.first(), "light"))
        host(selection)
        listOf("default", "offline", "empty").filter { it in probe.uiStates }.forEach { ui ->
            selection.value = Triple(brand, ui, "light")
            waitForIdle()
            val expected = probe.expectedVisible(probe.defaultBrand, ui)!! - probe.hidden(brand)
            val drawn = ids().filter { onAllNodes(hasTestTag(it), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }.toSet()
            assertEquals(expected, drawn, "$brand / $ui")
        }
    }
}
