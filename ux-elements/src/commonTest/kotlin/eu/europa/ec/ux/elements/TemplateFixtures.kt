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


package eu.europa.ec.ux.elements

import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import eu.europa.ec.ux.elements.templates.TplOutcome
import eu.europa.ec.ux.elements.templates.TplProgress
import eu.europa.ec.ux.elements.templates.TplScan
import eu.europa.ec.ux.elements.templates.TplSheet
import eu.europa.ec.ux.theme.UxDecisionSteps

// Neutral stand-ins for what the runtime will pass: plain props, no screen id and no flow state.

@Composable
internal fun SheetUnderTest(layout: UxDecisionSteps, title: String, focus: FocusRequester? = null) {
    TplSheet(
        id = "s",
        layout = layout,
        dialogLabel = title,
        headingFocus = focus,
        appbar = { UxAppBar("s.bar", UxBarLeading.None, title = "Wallet") },
        header = { UxText("s.title", title, UxTextVariant.Title) },
        body = {
            UxRpIdentity("s.rp", "Acme", UxTrust.Trusted, "Verified")
            UxNotice("s.note", UxNoticeTone.Info, "Check the data.")
        },
        actions = {
            UxButton("s.send", "Send", UxButtonVariant.Primary, {})
            UxButton("s.cancel", "Cancel", UxButtonVariant.Secondary, {})
        },
    )
}

@Composable
internal fun ProgressUnderTest(text: String, focus: FocusRequester) {
    TplProgress(
        id = "p",
        headingFocus = focus,
        hero = { UxStatusHero("p.hero-block", UxHeroTone.Pending, "Working", text = text) },
        actions = { UxButton("p.cancel", "Cancel", UxButtonVariant.Secondary, {}) },
    )
}

@Composable
internal fun OutcomeUnderTest(tone: UxHeroTone, focus: FocusRequester) {
    TplOutcome(
        id = "o",
        headingFocus = focus,
        hero = {
            UxStatusHero(
                "o.hero-block",
                tone,
                if (tone == UxHeroTone.Pending) "Working" else "Shared",
                text = "Details",
            )
        },
        body = { UxNotice("o.note", UxNoticeTone.Info, "Nothing else to do.") },
        actions = { UxButton("o.close", "Close", UxButtonVariant.Primary, {}) },
    )
}

@Composable
internal fun ScanUnderTest(scanning: Boolean, focus: FocusRequester) {
    TplScan(
        id = "c",
        headingFocus = focus,
        instruction = {
            UxText("c.title", "Scan the code", UxTextVariant.Title)
            UxText("c.hint", "Point the camera at the QR code")
        },
        camera = { UxQrViewfinder("c.camera-view", "Camera", scanning = scanning) },
        actions = { UxButton("c.cancel", "Cancel", UxButtonVariant.Secondary, {}) },
    )
}
