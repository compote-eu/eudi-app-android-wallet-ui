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

package eu.europa.ec.ux.runtime.host

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.europa.ec.ux.elements.registry.wireEnum
import eu.europa.ec.ux.runtime.a11y.rememberPlatformAccessibilityBridge
import eu.europa.ec.ux.runtime.bundle.FlowBundle
import eu.europa.ec.ux.runtime.engine.FlowExit
import eu.europa.ec.ux.runtime.engine.FlowLog
import eu.europa.ec.ux.runtime.engine.FlowPlatform
import eu.europa.ec.ux.runtime.engine.FlowStatus
import eu.europa.ec.ux.runtime.engine.ServicePort
import eu.europa.ec.ux.runtime.screen.CopyResolver
import eu.europa.ec.ux.runtime.screen.FlowScreen
import eu.europa.ec.ux.runtime.screen.FlowScreens
import eu.europa.ec.ux.runtime.screen.ScreenCatalog
import eu.europa.ec.ux.runtime.screen.StateChangeA11y
import eu.europa.ec.ux.runtime.screen.canDraw
import eu.europa.ec.ux.theme.LocalUxBrand
import eu.europa.ec.ux.theme.UxMode
import eu.europa.ec.ux.theme.UxTheme
import kotlinx.serialization.json.JsonObject

/**
 * Hosts one flow inside one Nav3 entry (SPIKE-PLAN §1.5). The flow's states are not Nav3 entries: they are rendered
 * here, one at a time, with [AnimatedContent].
 *
 * Back: this is the only place the runtime registers a platform back handler. [backHandler] is the platform's
 * adapter (each hook passes exactly one); it is enabled unless the state's policy is `pass`, and every press goes to
 * [FlowViewModel.back], which applies the bundle policy. Nothing else here reacts to Back.
 *
 * Screens: each state's screen is drawn by the screen interpreter from its node tree in the bundle, or, when the hook
 * registered one in [FlowScreens.custom], by that hand-written screen. A state whose screen the bundle does not
 * describe falls back to [StatePlaceholder]. The theme is the bundle's `theme.default_brand` from [FlowScreens.brands].
 * After each state change settles, the screen's `a11y.on_enter` runs (focus the heading or leave the live region).
 *
 * @param onExit called once when the flow reaches a final state or is abandoned; the hook maps the exit to the app.
 */
@Composable
fun FlowHost(
    bundle: FlowBundle,
    port: ServicePort,
    entry: String,
    launch: JsonObject,
    platform: FlowPlatform,
    backHandler: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit,
    onExit: (FlowExit?) -> Unit,
    modifier: Modifier = Modifier,
    screens: FlowScreens = FlowScreens(),
    log: FlowLog = FlowLog.NONE,
) {
    val flow = viewModel { FlowViewModel.create(this, bundle, port, entry, launch, platform, log) }
    val status by flow.status.collectAsState()
    val active = status as? FlowStatus.Active

    backHandler(active?.backEnabled == true) { flow.back() }

    LaunchedEffect(status) {
        (status as? FlowStatus.Exited)?.let { exited ->
            log.log("exit ${exited.exit?.state} (${exited.reason})")
            onExit(exited.exit)
        }
    }

    val catalog = remember(bundle) { ScreenCatalog(bundle).also { c -> c.problems.forEach { log.log("screen tree: $it") } } }
    val brand = screens.brands[catalog.defaultBrand]
    val copy = remember(bundle, screens) { CopyResolver(bundle, brand?.id ?: catalog.defaultBrand, screens.copy, log) }
    LaunchedEffect(catalog) {
        if (brand == null) log.log("brand ${catalog.defaultBrand} not supplied by the hook; the default theme is used")
    }

    val accessibility = screens.accessibility ?: rememberPlatformAccessibilityBridge()
    val currentState by rememberUpdatedState(active?.state)

    UxTheme(brand = brand ?: LocalUxBrand.current, mode = wireEnum(catalog.defaultMode, UxMode.entries, UxMode.Light)) {
        Box(modifier.fillMaxSize()) {
            AnimatedContent(targetState = active, contentKey = { it?.state }) { shown ->
                if (shown != null) {
                    val headingFocus = remember { FocusRequester() }
                    val settled by remember {
                        derivedStateOf {
                            transition.currentState == EnterExitState.Visible && transition.targetState == EnterExitState.Visible
                        }
                    }
                    // The leaving state is still drawn during the crossfade: its semantics are cleared, so a screen reader
                    // never sees two h1s or two live regions, and no focus stays on a node of the old state (D7).
                    val leaving by remember { derivedStateOf { currentState != shown.state } }
                    Box(Modifier.fillMaxSize().testTag(PlaceholderTags.STATE_PREFIX + shown.state)) {
                        Box(Modifier.fillMaxSize().then(if (leaving) Modifier.clearAndSetSemantics { } else Modifier)) {
                            StateContent(bundle, catalog, screens, copy, shown, headingFocus, flow, log)
                        }
                    }
                    LaunchedEffect(shown.gen, settled) {
                        val tree = shown.screen?.let(catalog.screens::get)
                        if (settled && tree != null) {
                            withFrameNanos { } // one frame after the transition ended: the new nodes are placed
                            StateChangeA11y.settled(tree, bundle, shown, copy, headingFocus, accessibility, log)
                        }
                    }
                }
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun StateContent(
    bundle: FlowBundle,
    catalog: ScreenCatalog,
    screens: FlowScreens,
    copy: CopyResolver,
    shown: FlowStatus.Active,
    headingFocus: FocusRequester,
    flow: FlowViewModel,
    log: FlowLog,
) {
    if (canDraw(catalog, screens, shown)) {
        FlowScreen(
            bundle = bundle,
            catalog = catalog,
            screens = screens,
            copy = copy,
            status = shown,
            headingFocus = headingFocus,
            isAccepted = flow::isAccepted,
            onEvent = { event, payload, gen -> flow.dispatch(event, payload, gen) },
            log = log,
        )
    } else {
        StatePlaceholder(
            bundle = bundle,
            status = shown,
            isAccepted = flow::isAccepted,
            onEvent = { event -> flow.dispatch(event, JsonObject(emptyMap()), shown.gen) },
        )
    }
}
