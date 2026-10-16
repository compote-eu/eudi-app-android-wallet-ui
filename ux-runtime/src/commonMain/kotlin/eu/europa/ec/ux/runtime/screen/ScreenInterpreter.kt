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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import eu.europa.ec.ux.elements.registry.UxElementAdapter
import eu.europa.ec.ux.elements.registry.UxNodeScope
import eu.europa.ec.ux.elements.registry.UxRecord
import eu.europa.ec.ux.elements.registry.UxTemplateAdapter
import eu.europa.ec.ux.elements.registry.UxTemplateScope
import eu.europa.ec.ux.runtime.bundle.ScreenRule
import eu.europa.ec.ux.runtime.engine.FlowLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Test tags of what the interpreter draws when the bundle names a part this runtime does not have. */
object InterpreterTags {
    const val UNKNOWN_ELEMENT_PREFIX: String = "ux-unknown-element."
    const val UNKNOWN_TEMPLATE_PREFIX: String = "ux-unknown-template."
}

/**
 * Everything one interpreted frame needs: the tree, the screen rules, the context of the status being drawn, and
 * how to reach the engine. Built per status; nothing in it is specific to a screen.
 */
internal class ScreenFrame(
    val tree: ScreenTree,
    rules: List<ScreenRule>,
    context: JsonObject,
    copy: CopyResolver,
    val headingFocus: FocusRequester,
    val isAccepted: (String) -> Boolean,
    val onEvent: (event: String, payload: JsonObject) -> Unit,
    val elements: Map<String, UxElementAdapter>,
    val templates: Map<String, UxTemplateAdapter>,
    val log: FlowLog,
) {
    val props = PropResolver(context, copy)
    val effects = NodeEffects.of(rules, context)

    /** A node's prop after `set_copy` and binding. */
    fun prop(node: ScreenNode, name: String): JsonElement? {
        effects.copyOverride(node.id, name)?.let { return JsonPrimitive(props.copyText(it, (node.props[name] as? JsonObject)?.get("args") as? JsonObject)) }
        return props.resolve(node.props[name])
    }

    fun enabled(node: ScreenNode): Boolean =
        effects.enabledByRule(node.id) != false && node.events.all { isAccepted(it.emits) }
}

/** Traversal (boundary §1.7): screen → template by `TPL-*` id → regions in order → nodes → slots. */
@Composable
internal fun InterpretedScreen(frame: ScreenFrame) {
    val tree = frame.tree
    val scope = object : UxTemplateScope {
        override val id: String = tree.id
        override val dialogLabel: String? = tree.a11y.dialogLabelledBy?.let(tree::node)?.let { n ->
            displayText(frame.prop(n, "text")) ?: displayText(frame.prop(n, "title"))
        }

        override fun hasRegion(region: String): Boolean =
            tree.regions[region].orEmpty().any { frame.effects.isVisible(it.id) }

        @Composable
        override fun Region(region: String) {
            tree.regions[region].orEmpty().forEach { RenderNode(it, frame) }
        }
    }
    val template = tree.template?.let(frame.templates::get)
    if (template != null) {
        template(scope)
    } else {
        // A visible placeholder: the regions are still drawn, so the screen stays usable and the gap is obvious.
        Column(Modifier.fillMaxSize().testTag(InterpreterTags.UNKNOWN_TEMPLATE_PREFIX + tree.template)) {
            BasicText("⟦template ${tree.template}⟧")
            tree.regions.keys.forEach { scope.Region(it) }
        }
    }
}

@Composable
private fun RenderNode(node: ScreenNode, frame: ScreenFrame) {
    if (!frame.effects.isVisible(node.id)) return
    val adapter = frame.elements[node.element]
    if (adapter == null) {
        BasicText("⟦${node.element} ${node.id}⟧", Modifier.testTag(InterpreterTags.UNKNOWN_ELEMENT_PREFIX + node.id))
        return
    }
    val scope = NodeScope(node, frame)
    if (node.a11y.inert) {
        Box(Modifier.clearAndSetSemantics { }) { adapter(scope) }
    } else {
        adapter(scope)
    }
}

private class NodeScope(private val node: ScreenNode, private val frame: ScreenFrame) : UxNodeScope {
    override val id: String = node.id
    override val enabled: Boolean = frame.enabled(node)
    override val focusRequester: FocusRequester? = frame.headingFocus.takeIf { frame.tree.a11y.heading == node.id }

    override fun text(prop: String): String? = displayText(frame.prop(node, prop))

    override fun flag(prop: String): Boolean? =
        (frame.prop(node, prop) as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()

    override fun records(prop: String): List<UxRecord> =
        (frame.prop(node, prop) as? JsonArray).orEmpty().filterIsInstance<JsonObject>().map(::JsonRecord)

    override fun copy(key: String): String = frame.props.copyText(key)

    override fun hasSlot(slot: String): Boolean = node.slots[slot].orEmpty().any { frame.effects.isVisible(it.id) }

    @Composable
    override fun Slot(slot: String) {
        node.slots[slot].orEmpty().forEach { RenderNode(it, frame) }
    }

    override fun emit(event: String, payload: Map<String, String>) {
        val mapped = node.events.firstOrNull { it.on == event }
        when {
            mapped == null -> frame.log.log("interpreter: ${node.id} raised '$event', which the node does not map")
            !enabled -> frame.log.log("interpreter: ${node.id} '$event' ignored (disabled)")
            else -> {
                frame.log.log("interpreter: ${node.id} $event -> ${mapped.emits}")
                frame.onEvent(mapped.emits, JsonObject(payload.mapValues { JsonPrimitive(it.value) }))
            }
        }
    }
}

private class JsonRecord(private val o: JsonObject) : UxRecord {
    override fun text(field: String): String? = displayText(o[field])

    override fun flag(field: String): Boolean? = (o[field] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
}
