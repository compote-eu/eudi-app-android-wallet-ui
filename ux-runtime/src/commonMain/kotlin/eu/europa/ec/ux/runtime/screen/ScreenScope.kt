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
import androidx.compose.ui.focus.FocusRequester
import eu.europa.ec.ux.elements.registry.UxElementAdapter
import eu.europa.ec.ux.elements.registry.UxElementRegistry
import eu.europa.ec.ux.elements.registry.UxTemplateAdapter
import eu.europa.ec.ux.runtime.a11y.AccessibilityBridge
import eu.europa.ec.ux.theme.UxBrand
import kotlinx.serialization.json.JsonObject

/**
 * What a hand-written screen (brief §6.6: the consent screen) gets from the runtime. The runtime builds [snapshot]
 * from the flow context; the screen reads it and sends intents ([send]) only. It never changes the context or the
 * state itself.
 */
interface ScreenScope {
    val screen: String
    val state: String
    val uiState: String?
    val gen: Long
    val pending: Boolean

    /**
     * The consent snapshot: the context paths whose `declared_by` names this screen (its `data_model`), as one JSON
     * tree (for paths `/a/b` and `/c`: `{a: {b: …}, c: …}`), read when the status was published.
     */
    val snapshot: JsonObject

    /** The heading focus target; the runtime focuses it after the state change settles (`on_enter`). */
    val headingFocus: FocusRequester

    fun copy(key: String, args: Map<String, String?> = emptyMap()): String

    fun isAccepted(event: String): Boolean

    /** An intent: the flow event and its payload, stamped with [gen]. The engine decides what it does. */
    fun send(event: String, payload: JsonObject = JsonObject(emptyMap()))
}

typealias CustomScreen = @Composable (ScreenScope) -> Unit

/**
 * How [eu.europa.ec.ux.runtime.host.FlowHost] draws screens. Everything screen-specific comes from the hook: [custom]
 * maps a screen id of the bundle to a hand-written screen (the runtime itself names none); every other screen is
 * interpreted from its node tree through [elements] and [templates].
 */
class FlowScreens(
    val copy: CopyProvider = CopyProvider.NONE,
    val custom: Map<String, CustomScreen> = emptyMap(),
    /** Brands by id; the bundle's `theme.default_brand` picks one. */
    val brands: Map<String, UxBrand> = emptyMap(),
    val accessibility: AccessibilityBridge = AccessibilityBridge.NONE,
    val elements: Map<String, UxElementAdapter> = UxElementRegistry.elements,
    val templates: Map<String, UxTemplateAdapter> = UxElementRegistry.templates,
)
