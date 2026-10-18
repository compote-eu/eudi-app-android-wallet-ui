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

// Hand-written against the generated contract (ux-spec/contract, brief §6.6); written in M5b against the provisional
// copy, re-checked at M6a (one change: the verifier name is nullable, V14). M8r: the verifier identity is drawn from
// its bundle nodes through ScreenScope.Node (RULING-W1 / week-0 F3), no longer from the verifier fields.
package eu.europa.ec.uxspike

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import eu.europa.ec.ux.contract.pres.PresRequestData
import eu.europa.ec.ux.contract.pres.PresRequestUiState
import eu.europa.ec.ux.contract.pres.Requirement
import eu.europa.ec.ux.contract.pres.Trust
import eu.europa.ec.ux.elements.UxAppBar
import eu.europa.ec.ux.elements.UxAttributeItem
import eu.europa.ec.ux.elements.UxAttributeList
import eu.europa.ec.ux.elements.UxBarLeading
import eu.europa.ec.ux.elements.UxBarTrailing
import eu.europa.ec.ux.elements.UxButton
import eu.europa.ec.ux.elements.UxButtonVariant
import eu.europa.ec.ux.elements.UxLink
import eu.europa.ec.ux.elements.UxNotice
import eu.europa.ec.ux.elements.UxNoticeTone
import eu.europa.ec.ux.elements.UxRequirement
import eu.europa.ec.ux.elements.UxText
import eu.europa.ec.ux.elements.UxTextVariant
import eu.europa.ec.ux.elements.templates.TplSheet
import eu.europa.ec.ux.runtime.screen.CustomScreen
import eu.europa.ec.ux.runtime.screen.ScreenScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The hand-written screens of the spike flow, by screen id (brief §6.6). The hook passes this map to the runtime;
 * this file is the only spike source that names a screen (the KC1 guard's one exception).
 */
val PresHandWrittenScreens: Map<String, CustomScreen> = mapOf(
    PresRequestScreenIds.SCREEN to { scope -> PresRequestScreen(scope) },
)

/** The ids this screen is written against: the screen, its node ids (test tags shared with the bundle) and its events. */
object PresRequestScreenIds {
    const val SCREEN: String = "SCR-pres-request"
    const val CONTINUE: String = "CONTINUE"
    const val REJECT: String = "REJECT"
    const val TOGGLE_ITEM: String = "TOGGLE_ITEM"
    const val ITEM_ID: String = "item_id"
    const val UNREADABLE_TAG: String = "pres-request.unreadable"

    /** The EL-rp-identity nodes of the screen's bundle tree, drawn through [ScreenScope.Node]. */
    const val RP_TRUSTED: String = "rp-trusted"
    const val RP_UNTRUSTED: String = "rp-untrusted"
}

private val snapshotJson = Json { ignoreUnknownKeys = true }

/**
 * SCR-pres-request (consent). It reads the runtime-built consent snapshot as the typed
 * [PresRequestData] and the typed [PresRequestUiState], and sends intents only (`TOGGLE_ITEM`, `CONTINUE`, `REJECT`):
 * selection, the no-selection error and every transition stay in the flow. It registers no back handler.
 */
@Composable
fun PresRequestScreen(scope: ScreenScope) {
    val data = runCatching { snapshotJson.decodeFromJsonElement(PresRequestData.serializer(), scope.snapshot) }.getOrNull()
    if (data == null) {
        Box(Modifier.fillMaxSize().testTag(PresRequestScreenIds.UNREADABLE_TAG)) { BasicText("⟦${scope.screen}: no request data⟧") }
        return
    }
    val uiState = PresRequestUiState.entries.firstOrNull { it.id == scope.uiState } ?: PresRequestUiState.DEFAULT
    val untrusted = data.verifier.trust == Trust.UNTRUSTED
    val noSelection = uiState == PresRequestUiState.NO_SELECTION
    val title = scope.copy("CPY-pres-request-title")
    TplSheet(
        id = scope.screen,
        dialogLabel = title,
        appbar = {
            UxAppBar(
                id = "app-bar",
                leading = UxBarLeading.Menu,
                leadingLabel = scope.copy("CPY-home-menu-label"),
                title = scope.copy("CPY-home-title"),
                trailing = UxBarTrailing.Notifications,
                trailingLabel = scope.copy("CPY-home-notifications-label"),
            )
        },
        header = { UxText("title", title, UxTextVariant.Title, focusRequester = scope.headingFocus) },
        body = {
            if (untrusted) {
                UxNotice(
                    "untrusted-notice",
                    UxNoticeTone.Warning,
                    scope.copy("CPY-pres-rp-unverified"),
                    title = scope.copy("CPY-pres-request-untrusted-notice-title"),
                )
            }
            // RULING-W1 / week-0 F3 (M8r): the verifier identity is a bundle node, drawn through the interpreter; the
            // screen rules show the one that matches /verifier/trust, and a prop the spec adds (e.g. the logo) needs no
            // change here.
            scope.Node(PresRequestScreenIds.RP_TRUSTED)
            scope.Node(PresRequestScreenIds.RP_UNTRUSTED)
            data.verifier.privacyUrl?.takeIf { it.isNotEmpty() }?.let { url ->
                UxLink(
                    "privacy",
                    scope.copy("CPY-pres-request-privacy-link"),
                    url,
                    onActivate = {},
                    external = true,
                    externalLabel = scope.copy("CPY-pres-link-external-hint"),
                )
            }
            UxText("data-intro", scope.copy("CPY-pres-request-data-intro", mapOf("document" to data.request.documentName)))
            if (noSelection) {
                UxNotice("no-field-error", UxNoticeTone.Error, scope.copy("CPY-pres-request-no-field-error"))
            } else {
                UxText("instructions", scope.copy("CPY-pres-request-instructions"), UxTextVariant.Small)
            }
            UxAttributeList(
                id = "attributes",
                items = data.request.attributes.map { a ->
                    UxAttributeItem(
                        id = a.id,
                        label = scope.copy(a.labelCopy),
                        requirement = if (a.requirement == Requirement.MANDATORY) UxRequirement.Mandatory else UxRequirement.Optional,
                        value = a.value,
                        selected = a.selected,
                    )
                },
                onToggle = { itemId, _ ->
                    scope.send(PresRequestScreenIds.TOGGLE_ITEM, JsonObject(mapOf(PresRequestScreenIds.ITEM_ID to JsonPrimitive(itemId))))
                },
                optionalHeading = scope.copy("CPY-pres-request-optional-heading"),
                mandatoryLabel = scope.copy("CPY-pres-request-mandatory-tag"),
            )
        },
        actions = {
            UxButton(
                "continue",
                scope.copy("CPY-pres-request-continue"),
                UxButtonVariant.Primary,
                onPress = { scope.send(PresRequestScreenIds.CONTINUE) },
                disabled = !scope.isAccepted(PresRequestScreenIds.CONTINUE),
            )
            UxButton(
                "reject",
                scope.copy("CPY-pres-request-reject"),
                UxButtonVariant.Secondary,
                onPress = { scope.send(PresRequestScreenIds.REJECT) },
                disabled = !scope.isAccepted(PresRequestScreenIds.REJECT),
            )
        },
    )
}
