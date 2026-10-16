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

package eu.europa.ec.ux.elements.registry

import androidx.compose.runtime.Composable
import eu.europa.ec.ux.elements.UxAppBar
import eu.europa.ec.ux.elements.UxAttributeItem
import eu.europa.ec.ux.elements.UxAttributeList
import eu.europa.ec.ux.elements.UxBarLeading
import eu.europa.ec.ux.elements.UxBarTrailing
import eu.europa.ec.ux.elements.UxButton
import eu.europa.ec.ux.elements.UxButtonVariant
import eu.europa.ec.ux.elements.UxHeroTone
import eu.europa.ec.ux.elements.UxLink
import eu.europa.ec.ux.elements.UxNotice
import eu.europa.ec.ux.elements.UxNoticeTone
import eu.europa.ec.ux.elements.UxQrViewfinder
import eu.europa.ec.ux.elements.UxRequirement
import eu.europa.ec.ux.elements.UxRpIdentity
import eu.europa.ec.ux.elements.UxStatusHero
import eu.europa.ec.ux.elements.UxText
import eu.europa.ec.ux.elements.UxTextTone
import eu.europa.ec.ux.elements.UxTextVariant
import eu.europa.ec.ux.elements.UxTrust
import eu.europa.ec.ux.elements.templates.TplOutcome
import eu.europa.ec.ux.elements.templates.TplProgress
import eu.europa.ec.ux.elements.templates.TplScan
import eu.europa.ec.ux.elements.templates.TplSheet

/**
 * The generic element registry (SPIKE-PLAN §1.4): element type id (`EL-*`) and template id (`TPL-*`) to the adapter
 * that maps a resolved node onto the element's typed props. Keyed by element and template ids only: no screen, state
 * or flow is named here, so a new screen built from these parts needs no entry.
 */
object UxElementRegistry {

    val elements: Map<String, UxElementAdapter> = mapOf(
        "EL-text" to { n ->
            UxText(
                id = n.id,
                text = n.text("text").orEmpty(),
                variant = wireEnum(n.text("variant"), UxTextVariant.entries, UxTextVariant.Body),
                tone = wireEnum(n.text("tone"), UxTextTone.entries, UxTextTone.Default),
                focusRequester = n.focusRequester,
            )
        },
        "EL-button" to { n ->
            UxButton(
                id = n.id,
                label = n.text("label").orEmpty(),
                variant = wireEnum(n.text("variant"), UxButtonVariant.entries, UxButtonVariant.Primary),
                onPress = { n.emit("press") },
                disabled = !n.enabled,
                icon = n.text("icon"),
                popup = n.flag("popup") == true,
                popupStateLabel = n.text("popup_state_label"),
                focusRequester = n.focusRequester,
            )
        },
        "EL-status-hero" to { n ->
            UxStatusHero(
                id = n.id,
                tone = wireEnum(n.text("tone"), UxHeroTone.entries, UxHeroTone.Info),
                title = n.text("title").orEmpty(),
                text = n.text("text"),
                focusRequester = n.focusRequester,
            )
        },
        "EL-notice" to { n ->
            UxNotice(
                id = n.id,
                tone = wireEnum(n.text("tone"), UxNoticeTone.entries, UxNoticeTone.Info),
                text = n.text("text").orEmpty(),
                title = n.text("title"),
                extra = if (n.hasSlot("extra")) ({ n.Slot("extra") }) else null,
            )
        },
        "EL-link" to { n ->
            UxLink(
                id = n.id,
                label = n.text("label").orEmpty(),
                href = n.text("href").orEmpty(),
                onActivate = { n.emit("activate") },
                external = n.flag("external") == true,
                externalLabel = n.text("external_label"),
                focusRequester = n.focusRequester,
            )
        },
        "EL-qr-viewfinder" to { n ->
            UxQrViewfinder(id = n.id, label = n.text("label").orEmpty(), scanning = n.flag("scanning") != false)
        },
        "EL-app-bar" to { n ->
            UxAppBar(
                id = n.id,
                leading = wireEnum(n.text("leading"), UxBarLeading.entries, UxBarLeading.None),
                leadingLabel = n.text("leading_label"),
                title = n.text("title"),
                trailing = wireEnum(n.text("trailing"), UxBarTrailing.entries, UxBarTrailing.None),
                trailingLabel = n.text("trailing_label"),
                badge = n.flag("badge") == true,
                onLeading = { n.emit("leading") },
                onTrailing = { n.emit("trailing") },
            )
        },
        "EL-rp-identity" to { n ->
            UxRpIdentity(
                id = n.id,
                name = n.text("name").orEmpty(),
                trust = wireEnum(n.text("trust"), UxTrust.entries, UxTrust.Unknown),
                trustLabel = n.text("trust_label").orEmpty(),
                service = n.text("service"),
                reason = n.text("reason"),
                caption = n.text("caption"),
                serviceLabel = n.text("service_label"),
                reasonLabel = n.text("reason_label"),
            )
        },
        "EL-attribute-list" to { n ->
            UxAttributeList(
                id = n.id,
                items = n.records("items").map { r -> attributeItem(r, n) },
                readonly = n.flag("readonly") == true,
                onToggle = { itemId, _ -> n.emit("toggle", mapOf(ITEM_ID to itemId)) },
                onCopy = { itemId -> n.emit("copy", mapOf(ITEM_ID to itemId)) },
                mandatoryHeading = n.text("mandatory_heading"),
                optionalHeading = n.text("optional_heading"),
                mandatoryLabel = n.text("mandatory_label"),
                optionalLabel = n.text("optional_label"),
                copyLabel = n.text("copy_label"),
            )
        },
    )

    val templates: Map<String, UxTemplateAdapter> = mapOf(
        "TPL-outcome" to { t ->
            TplOutcome(
                id = t.id,
                hero = { t.Region("hero") },
                actions = { t.Region("actions") },
                appbar = t.optional("appbar"),
                body = if (t.hasRegion("body")) ({ t.Region("body") }) else null,
            )
        },
        "TPL-progress" to { t ->
            TplProgress(
                id = t.id,
                hero = { t.Region("hero") },
                appbar = t.optional("appbar"),
                steps = t.optional("steps"),
                actions = if (t.hasRegion("actions")) ({ t.Region("actions") }) else null,
            )
        },
        "TPL-scan" to { t ->
            TplScan(
                id = t.id,
                instruction = { t.Region("instruction") },
                camera = { t.Region("camera") },
                actions = { t.Region("actions") },
            )
        },
        "TPL-sheet" to { t ->
            TplSheet(
                id = t.id,
                header = { t.Region("header") },
                actions = { t.Region("actions") },
                dialogLabel = t.dialogLabel,
                appbar = t.optional("appbar"),
                body = if (t.hasRegion("body")) ({ t.Region("body") }) else null,
            )
        },
    )

    /** The payload field of the `toggle` and `copy` events of EL-attribute-list (boundary §1.7). */
    const val ITEM_ID: String = "item_id"

    private fun attributeItem(r: UxRecord, n: UxNodeScope) = UxAttributeItem(
        id = r.text("id").orEmpty(),
        label = r.text("label_copy")?.let(n::copy) ?: r.text("label").orEmpty(),
        requirement = wireEnum(r.text("requirement"), UxRequirement.entries, UxRequirement.Optional),
        value = r.text("value"),
        selected = r.flag("selected") == true,
        copyable = r.flag("copyable") == true,
        photo = r.text("media") == "photo",
    )

    private fun UxTemplateScope.optional(region: String): (@Composable () -> Unit)? =
        if (hasRegion(region)) ({ Region(region) }) else null
}
