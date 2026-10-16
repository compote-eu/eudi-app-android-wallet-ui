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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.europa.ec.ux.elements.UxAppBar
import eu.europa.ec.ux.elements.UxAttributeItem
import eu.europa.ec.ux.elements.UxAttributeList
import eu.europa.ec.ux.elements.UxBarLeading
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
import eu.europa.ec.ux.elements.UxTextVariant
import eu.europa.ec.ux.elements.UxTrust
import eu.europa.ec.ux.elements.templates.TplOutcome
import eu.europa.ec.ux.elements.templates.TplProgress
import eu.europa.ec.ux.elements.templates.TplScan
import eu.europa.ec.ux.elements.templates.TplSheet
import eu.europa.ec.ux.theme.UxDecisionSteps
import eu.europa.ec.ux.theme.UxTheme

/** Script-name prefix that opens the template gallery instead of a flow (`--ux-spike gallery-outcome`). */
const val UX_GALLERY_PREFIX = "gallery"

/** The templates the gallery shows, in picker order. */
enum class UxGalleryPage(val key: String) {
    Sheet("sheet"),
    SheetPage("page"),
    Progress("progress"),
    Outcome("outcome"),
    Scan("scan"),
    ;

    companion object {
        /** `gallery` -> sheet, `gallery-outcome` -> outcome; an unknown suffix falls back to the sheet. */
        fun fromScript(script: String): UxGalleryPage =
            entries.firstOrNull { it.key == script.removePrefix(UX_GALLERY_PREFIX).trimStart('-') } ?: Sheet
    }
}

/**
 * Debug-only gallery of the four templates with neutral demo props, reachable through the [UxFlowRoute] entry (script
 * name `gallery[-sheet|-page|-progress|-outcome|-scan]`). It exists for the Accessibility Inspector and the M5
 * screenshots; no flow, bundle or engine is involved.
 */
@Composable
fun UxGallery(initial: UxGalleryPage = UxGalleryPage.Sheet) {
    var page by remember { mutableStateOf(initial) }
    UxTheme {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
            ) {
                UxGalleryPage.entries.forEach {
                    UxButton(
                        id = "gallery-pick-${it.key}",
                        label = it.key,
                        variant = if (it == page) UxButtonVariant.Primary else UxButtonVariant.Secondary,
                        onPress = { page = it },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
            when (page) {
                UxGalleryPage.Sheet -> GallerySheet(UxDecisionSteps.Sheet)
                UxGalleryPage.SheetPage -> GallerySheet(UxDecisionSteps.Page)
                UxGalleryPage.Progress -> GalleryProgress()
                UxGalleryPage.Outcome -> GalleryOutcome()
                UxGalleryPage.Scan -> GalleryScan()
            }
        }
    }
}

@Composable
private fun GallerySheet(layout: UxDecisionSteps) {
    TplSheet(
        id = "g-sheet",
        layout = layout,
        dialogLabel = "Share your data?",
        appbar = { UxAppBar("g-bar", UxBarLeading.Menu, leadingLabel = "Menu", title = "Wallet") },
        header = { UxText("g-title", "Share your data?", UxTextVariant.Title) },
        body = {
            UxRpIdentity(
                "g-rp", "Example Verifier s.r.o.", UxTrust.Trusted, "Verified", service = "verifier.example",
                reason = "Check your age", caption = "Requested by",
            )
            UxAttributeList(
                "g-attrs",
                listOf(
                    UxAttributeItem("name", "Name", UxRequirement.Mandatory, value = "Eva Nováková"),
                    UxAttributeItem("age", "Over 18", UxRequirement.Mandatory),
                    UxAttributeItem("photo", "Portrait", UxRequirement.Optional),
                ),
                mandatoryHeading = "Required data",
                optionalHeading = "Optional data",
                mandatoryLabel = "Required",
            )
            UxNotice("g-note", UxNoticeTone.Info, "You can review what was shared later.", title = "Good to know", extra = {
                UxLink("g-link", "Privacy policy", "https://example.invalid/privacy", {}, external = true, externalLabel = "opens in the browser")
            })
        },
        actions = {
            UxButton("g-share", "Share", UxButtonVariant.Primary, {})
            UxButton("g-cancel", "Cancel", UxButtonVariant.Secondary, {})
        },
    )
}

@Composable
private fun GalleryProgress() {
    TplProgress(
        id = "g-progress",
        appbar = { UxAppBar("g-bar", UxBarLeading.Back, leadingLabel = "Back", title = "Wallet") },
        hero = { UxStatusHero("g-hero", UxHeroTone.Pending, "Sending your data", text = "This takes a moment.") },
        actions = { UxButton("g-cancel", "Cancel", UxButtonVariant.Secondary, {}) },
    )
}

@Composable
private fun GalleryOutcome() {
    TplOutcome(
        id = "g-outcome",
        hero = { UxStatusHero("g-hero", UxHeroTone.Success, "Data shared", text = "The verifier received what you approved.") },
        body = { UxNotice("g-note", UxNoticeTone.Info, "You can close this screen.") },
        actions = {
            UxButton("g-done", "Done", UxButtonVariant.Primary, {})
            UxButton("g-again", "Share again", UxButtonVariant.Secondary, {})
        },
    )
}

@Composable
private fun GalleryScan() {
    TplScan(
        id = "g-scan",
        instruction = {
            UxText("g-title", "Scan the QR code", UxTextVariant.Title)
            UxText("g-hint", "Point the camera at the code on the verifier's screen.")
        },
        camera = { UxQrViewfinder("g-camera", "Camera, looking for a QR code") },
        actions = { UxButton("g-cancel", "Cancel", UxButtonVariant.Secondary, {}) },
    )
}
