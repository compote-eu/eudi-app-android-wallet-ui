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

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import eu.europa.ec.ux.elements.UxAppBar
import eu.europa.ec.ux.elements.UxAttributeItem
import eu.europa.ec.ux.elements.UxBarAction
import eu.europa.ec.ux.elements.UxBarTab
import eu.europa.ec.ux.elements.UxBottomBar
import eu.europa.ec.ux.elements.UxCardArt
import eu.europa.ec.ux.elements.UxCardVariant
import eu.europa.ec.ux.elements.UxChipStatus
import eu.europa.ec.ux.elements.UxDocStatus
import eu.europa.ec.ux.elements.UxDocType
import eu.europa.ec.ux.elements.UxDocumentCard
import eu.europa.ec.ux.elements.UxSegmentedSwitch
import eu.europa.ec.ux.elements.UxStatusChip
import eu.europa.ec.ux.elements.UxStorage
import eu.europa.ec.ux.elements.UxSwitchLayout
import eu.europa.ec.ux.elements.UxSwitchOption
import eu.europa.ec.ux.elements.UxTrustMark
import eu.europa.ec.ux.elements.UxTrustMarkArt
import eu.europa.ec.ux.elements.UxTrustMarkVariant
import eu.europa.ec.ux.elements.UxAttributeList
import eu.europa.ec.ux.elements.UxBarLeading
import eu.europa.ec.ux.elements.UxBarTrailing
import eu.europa.ec.ux.elements.UxButton
import eu.europa.ec.ux.elements.UxButtonVariant
import eu.europa.ec.ux.elements.UxHeroArt
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
import eu.europa.ec.ux.elements.templates.TplHome
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
                logo = n.text("logo"),
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
        "EL-status-chip" to { n ->
            UxStatusChip(
                id = n.id,
                status = wireEnum(n.text("status"), UxChipStatus.entries, UxChipStatus.Info),
                label = n.text("label").orEmpty(),
            )
        },
        "EL-document-card" to { n ->
            UxDocumentCard(
                id = n.id,
                variant = wireEnum(n.text("variant"), UxCardVariant.entries, UxCardVariant.Full),
                docType = wireEnum(n.text("doc_type"), UxDocType.entries, UxDocType.IdCard),
                title = n.text("title").orEmpty(),
                status = wireEnum(n.text("status"), UxDocStatus.entries, UxDocStatus.Valid),
                statusLabel = n.text("status_label").orEmpty(),
                titleEn = n.text("title_en"),
                initials = n.text("initials"),
                holder = n.text("holder"),
                number = n.text("number"),
                storage = n.text("storage")?.let { wireEnum(it, UxStorage.entries, UxStorage.Phone) },
                storageLabel = n.text("storage_label"),
                art = wireEnum(n.text("art"), UxCardArt.entries, UxCardArt.Brand),
                interactive = n.flag("interactive") != false,
                enabled = n.enabled,
                onOpen = { n.emit("open") },
            )
        },
        "EL-bottom-bar" to { n ->
            UxBottomBar(
                id = n.id,
                label = n.text("label").orEmpty(),
                tabs = n.records("tabs").map { r ->
                    UxBarTab(
                        id = r.text("id").orEmpty(),
                        label = r.text("label_copy")?.let(n::copy).orEmpty(),
                        icon = r.text("icon").orEmpty(),
                        selected = r.flag("selected") == true,
                    )
                },
                action = wireEnum(n.text("action"), UxBarAction.entries, UxBarAction.None),
                actionLabel = n.text("action_label"),
                enabled = n.enabled,
                onSelect = { tab -> n.emit("select", mapOf(ITEM_ID to tab)) },
                onAction = { n.emit("action") },
            )
        },
        "EL-segmented-switch" to { n ->
            UxSegmentedSwitch(
                id = n.id,
                label = n.text("label").orEmpty(),
                options = n.records("options").map { r ->
                    UxSwitchOption(
                        id = r.text("id").orEmpty(),
                        label = r.text("label_copy")?.let(n::copy).orEmpty(),
                        selected = r.flag("selected") == true,
                        disabled = r.flag("disabled") == true,
                    )
                },
                labelVisible = n.flag("label_visible") == true,
                layout = wireEnum(n.text("layout"), UxSwitchLayout.entries, UxSwitchLayout.Auto),
                enabled = n.enabled,
                onChange = { option -> n.emit("change", mapOf(ITEM_ID to option)) },
            )
        },
        "EL-trust-mark" to { n ->
            UxTrustMark(
                id = n.id,
                variant = wireEnum(n.text("variant"), UxTrustMarkVariant.entries, UxTrustMarkVariant.Badge),
                label = n.text("label").orEmpty(),
                art = wireEnum(n.text("art"), UxTrustMarkArt.entries, UxTrustMarkArt.Official),
                href = n.text("href"),
                enabled = n.enabled,
                onActivate = { n.emit("activate") },
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
        "TPL-home" to { t ->
            TplHome(
                id = t.id,
                header = { t.Region("header") },
                appbar = t.optional("appbar"),
                switch = t.optionalBody("switch"),
                notice = t.optionalBody("notice"),
                identity = t.optionalBody("identity"),
                documentsHeading = if (t.hasRegion("documents-heading")) ({ t.Region("documents-heading") }) else null,
                cards = t.optionalBody("cards"),
                body = t.optionalBody("body"),
                footer = t.optionalBody("footer"),
                actions = t.optionalBody("actions"),
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

    /** The accepted props, variants, events and slots per element (M5c): one entry per adapter above. */
    val elementSchemas: Map<String, UxElementSchema> = mapOf(
        "EL-text" to UxElementSchema(
            props = mapOf("text" to null, "variant" to enumKeys(UxTextVariant.entries), "tone" to enumKeys(UxTextTone.entries)),
        ),
        "EL-button" to UxElementSchema(
            props = mapOf(
                "label" to null,
                "variant" to enumKeys(UxButtonVariant.entries),
                "disabled" to null,
                "icon" to null,
                "popup" to null,
                "popup_state_label" to null,
            ),
            events = setOf("press"),
        ),
        "EL-status-hero" to UxElementSchema(
            props = mapOf(
                "tone" to enumKeys(UxHeroTone.entries),
                "title" to null,
                "text" to null,
                "art" to enumKeys(UxHeroArt.entries),
            ),
        ),
        "EL-notice" to UxElementSchema(
            props = mapOf("tone" to enumKeys(UxNoticeTone.entries), "title" to null, "text" to null),
            slots = setOf("extra"),
        ),
        "EL-link" to UxElementSchema(
            props = mapOf("label" to null, "href" to null, "external" to null, "external_label" to null),
            events = setOf("activate"),
        ),
        "EL-qr-viewfinder" to UxElementSchema(props = mapOf("label" to null, "scanning" to null)),
        "EL-app-bar" to UxElementSchema(
            props = mapOf(
                "leading" to enumKeys(UxBarLeading.entries),
                "leading_label" to null,
                "title" to null,
                "trailing" to enumKeys(UxBarTrailing.entries),
                "trailing_label" to null,
                "badge" to null,
            ),
            events = setOf("leading", "trailing"),
        ),
        "EL-rp-identity" to UxElementSchema(
            props = mapOf(
                "name" to null,
                "service" to null,
                "reason" to null,
                "trust" to enumKeys(UxTrust.entries),
                "trust_label" to null,
                "caption" to null,
                "service_label" to null,
                "reason_label" to null,
                "logo" to null,
            ),
        ),
        "EL-attribute-list" to UxElementSchema(
            props = mapOf(
                "items" to null,
                "readonly" to null,
                "mandatory_heading" to null,
                "optional_heading" to null,
                "mandatory_label" to null,
                "optional_label" to null,
                "copy_label" to null,
            ),
            events = setOf("toggle", "copy"),
            itemsProp = "items",
            itemProps = mapOf(
                "id" to null,
                "label" to null,
                "label_copy" to null,
                "value" to null,
                "requirement" to enumKeys(UxRequirement.entries),
                "selected" to null,
                "copyable" to null,
                "media" to null,
            ),
        ),
        "EL-status-chip" to UxElementSchema(props = mapOf("status" to enumKeys(UxChipStatus.entries), "label" to null)),
        "EL-document-card" to UxElementSchema(
            props = mapOf(
                "variant" to enumKeys(UxCardVariant.entries),
                "title_en" to null,
                "initials" to null,
                "doc_type" to enumKeys(UxDocType.entries),
                "title" to null,
                "holder" to null,
                "number" to null,
                "status" to enumKeys(UxDocStatus.entries),
                "status_label" to null,
                "storage" to enumKeys(UxStorage.entries),
                "storage_label" to null,
                "art" to enumKeys(UxCardArt.entries),
                "interactive" to null,
            ),
            events = setOf("open"),
        ),
        "EL-bottom-bar" to UxElementSchema(
            props = mapOf("label" to null, "tabs" to null, "action" to enumKeys(UxBarAction.entries), "action_label" to null),
            events = setOf("select", "action"),
            itemsProp = "tabs",
            itemProps = mapOf("id" to null, "label_copy" to null, "icon" to null, "selected" to null),
        ),
        "EL-segmented-switch" to UxElementSchema(
            props = mapOf("label" to null, "label_visible" to null, "layout" to enumKeys(UxSwitchLayout.entries), "options" to null),
            events = setOf("change"),
            itemsProp = "options",
            itemProps = mapOf("id" to null, "label_copy" to null, "selected" to null, "disabled" to null),
        ),
        "EL-trust-mark" to UxElementSchema(
            props = mapOf(
                "variant" to enumKeys(UxTrustMarkVariant.entries),
                "label" to null,
                "art" to enumKeys(UxTrustMarkArt.entries),
                "href" to null,
            ),
            events = setOf("activate"),
        ),
    )

    /** The accepted regions per template (M5c). */
    val templateSchemas: Map<String, UxTemplateSchema> = mapOf(
        "TPL-outcome" to UxTemplateSchema(setOf("appbar", "hero", "body", "actions")),
        "TPL-home" to UxTemplateSchema(
            setOf("appbar", "header", "switch", "notice", "identity", "documents-heading", "cards", "body", "footer", "actions"),
        ),
        "TPL-progress" to UxTemplateSchema(setOf("appbar", "steps", "hero", "actions")),
        "TPL-scan" to UxTemplateSchema(setOf("instruction", "camera", "actions")),
        "TPL-sheet" to UxTemplateSchema(setOf("appbar", "header", "body", "actions")),
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

    private fun UxTemplateScope.optionalBody(region: String): (@Composable ColumnScope.() -> Unit)? =
        if (hasRegion(region)) ({ Region(region) }) else null
}
