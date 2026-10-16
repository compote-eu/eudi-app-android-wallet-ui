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

package eu.europa.ec.ux.runtime.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import eu.europa.ec.ux.runtime.a11y.AccessibilityBridge
import eu.europa.ec.ux.runtime.bundle.FlowBundle
import eu.europa.ec.ux.runtime.engine.FlowContext
import eu.europa.ec.ux.runtime.engine.FlowLog
import eu.europa.ec.ux.runtime.engine.FlowStatus
import kotlinx.serialization.json.JsonObject

/**
 * Draws the screen of one active status: the hook's hand-written screen when [FlowScreens.custom] has the screen
 * id, else the interpreted node tree. Call it only when [canDraw] is true.
 */
@Composable
internal fun FlowScreen(
    bundle: FlowBundle,
    catalog: ScreenCatalog,
    screens: FlowScreens,
    copy: CopyResolver,
    status: FlowStatus.Active,
    headingFocus: FocusRequester,
    isAccepted: (String) -> Boolean,
    onEvent: (event: String, payload: JsonObject, gen: Long) -> Unit,
    log: FlowLog,
) {
    val screenId = status.screen ?: return
    val custom = screens.custom[screenId]
    val tree = catalog.screens[screenId]
    LaunchedEffect(status.gen) {
        log.log(
            when {
                custom != null -> "screen $screenId: hand-written (hook), state ${status.state}, ui_state ${status.uiState}"
                tree != null -> "screen $screenId: interpreted from the bundle (${tree.template}, ${tree.nodes.size} nodes), " +
                    "state ${status.state}, ui_state ${status.uiState}"
                else -> "screen $screenId: no node tree in the bundle"
            },
        )
    }
    if (custom != null) {
        custom(CustomScope(screenId, catalog, copy, status, headingFocus, isAccepted, onEvent, log))
        return
    }
    if (tree == null) return
    InterpretedScreen(
        ScreenFrame(
            tree = tree,
            rules = bundle.screens[screenId]?.rules.orEmpty(),
            context = status.context,
            copy = copy,
            headingFocus = headingFocus,
            isAccepted = isAccepted,
            onEvent = { event, payload -> onEvent(event, payload, status.gen) },
            elements = screens.elements,
            templates = screens.templates,
            log = log,
        ),
    )
}

/** Whether [FlowScreen] can draw the status: a hand-written screen or a node tree exists for its screen id. */
internal fun canDraw(catalog: ScreenCatalog, screens: FlowScreens, status: FlowStatus.Active): Boolean =
    status.screen?.let { it in screens.custom || it in catalog.screens } == true

private class CustomScope(
    override val screen: String,
    catalog: ScreenCatalog,
    private val copyResolver: CopyResolver,
    status: FlowStatus.Active,
    override val headingFocus: FocusRequester,
    private val accepted: (String) -> Boolean,
    private val onEvent: (String, JsonObject, Long) -> Unit,
    private val log: FlowLog,
) : ScreenScope {
    override val state: String = status.state
    override val uiState: String? = status.uiState
    override val gen: Long = status.gen
    override val pending: Boolean = status.pending
    override val snapshot: JsonObject = ConsentSnapshot.of(catalog, screen, status.context)

    override fun copy(key: String, args: Map<String, String?>): String = copyResolver.text(key, args)

    override fun isAccepted(event: String): Boolean = accepted(event)

    override fun send(event: String, payload: JsonObject) {
        log.log("intent from $screen: $event $payload (gen $gen)")
        onEvent(event, payload, gen)
    }
}

/** The runtime-built snapshot a hand-written screen reads (brief §6.6). */
internal object ConsentSnapshot {
    fun of(catalog: ScreenCatalog, screen: String, context: JsonObject): JsonObject =
        catalog.declaredPaths[screen].orEmpty().fold(JsonObject(emptyMap())) { acc, path ->
            FlowContext.set(acc, path, FlowContext.get(context, path))
        }
}

/**
 * The focus/announcement hook (SPIKE-PLAN §1.4), called once per state entry after the transition settled:
 * `focus-heading` moves focus to the heading node and reports it; `announce-live` leaves focus and reports the live
 * region, which speaks by itself (polite). The platform side is [AccessibilityBridge] (M7).
 */
internal object StateChangeA11y {
    fun settled(tree: ScreenA11y, screen: String, headingFocus: FocusRequester, bridge: AccessibilityBridge, log: FlowLog) {
        when (tree.onEnter) {
            ScreenA11y.FOCUS_HEADING -> {
                val moved = runCatching { headingFocus.requestFocus() }.isSuccess
                log.log("a11y: $screen focus-heading ${tree.heading} (${if (moved) "focused" else "no focus target"})")
                bridge.screenChanged(screen, tree.heading)
            }
            ScreenA11y.ANNOUNCE_LIVE -> {
                log.log("a11y: $screen announce-live ${tree.liveRegion}")
                bridge.liveRegionShown(screen, tree.liveRegion)
            }
            else -> log.log("a11y: $screen on_enter ${tree.onEnter} (nothing to do)")
        }
    }
}
