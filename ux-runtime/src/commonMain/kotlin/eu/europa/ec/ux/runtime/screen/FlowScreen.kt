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
import eu.europa.ec.ux.runtime.bundle.ScreenRule
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
 * The focus/announcement hook (SPIKE-PLAN §1.4, KC3), called once per state entry after the transition settled plus
 * one frame: `focus-heading` moves input focus to the heading node and asks the [AccessibilityBridge] to move the screen
 * reader there; `announce-live` leaves focus and has the bridge speak the live region's text once (a node inserted with
 * a new state is not announced by the live region itself). Texts come from the node's `{copy}` props, so nothing here
 * knows a screen.
 */
internal object StateChangeA11y {
    @Suppress("LongParameterList")
    suspend fun settled(
        tree: ScreenTree,
        bundle: FlowBundle,
        status: FlowStatus.Active,
        copy: CopyResolver,
        headingFocus: FocusRequester,
        bridge: AccessibilityBridge,
        log: FlowLog,
    ) {
        val a11y = tree.a11y
        val speech = ScreenSpeech(tree, bundle.screens[tree.id]?.rules.orEmpty(), status.context, copy)
        when (a11y.onEnter) {
            ScreenA11y.FOCUS_HEADING -> {
                val moved = runCatching { headingFocus.requestFocus() }.isSuccess
                log.log("a11y: ${tree.id} focus-heading ${a11y.heading} (${if (moved) "focused" else "no focus target"})")
                bridge.screenChanged(tree.id, a11y.heading, a11y.heading?.let(speech::text), dialog = a11y.dialogLabelledBy != null)
            }
            ScreenA11y.ANNOUNCE_LIVE -> {
                val text = a11y.liveRegion?.let(speech::text).orEmpty()
                log.log("a11y: ${tree.id} announce-live ${a11y.liveRegion} ('$text')")
                bridge.announce(tree.id, a11y.liveRegion, text)
            }
            else -> log.log("a11y: ${tree.id} on_enter ${a11y.onEnter} (nothing to do)")
        }
    }
}

/** What a node says: its `{copy}` props in declared order (after `set_copy`), joined; literals and bindings are not speech. */
internal class ScreenSpeech(private val tree: ScreenTree, rules: List<ScreenRule>, context: JsonObject, copy: CopyResolver) {
    private val props = PropResolver(context, copy)
    private val effects = NodeEffects.of(rules, context)

    fun text(nodeId: String): String? {
        val node = tree.node(nodeId) ?: return null
        return node.props.mapNotNull { (name, raw) ->
            val o = raw as? JsonObject ?: return@mapNotNull null
            if (o["copy"] == null) return@mapNotNull null
            effects.copyOverride(node.id, name)?.let { props.copyText(it, o["args"] as? JsonObject) } ?: displayText(props.resolve(o))
        }.filter { it.isNotBlank() }.joinToString(". ").ifBlank { null }
    }
}
