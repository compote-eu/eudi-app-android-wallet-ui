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


package eu.europa.ec.ux.elements.templates

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import eu.europa.ec.ux.elements.LocalUxHeadingFocus
import eu.europa.ec.ux.theme.UxDecisionSteps
import eu.europa.ec.ux.theme.UxThemeAccess
import eu.europa.ec.ux.theme.uxElevation

/**
 * TPL-sheet: a decision over the app it interrupts. The [layout] parameter is the `decision-steps` axis, and the whole
 * difference lives in this function (track B changes only this implementation): `Sheet` draws the dialog as a bottom
 * sheet that is as tall as its content, over the inert [appbar] backdrop under a scrim that takes every tap; `Page`
 * draws the same dialog full screen, with no backdrop and no scrim.
 *
 * Semantics: the [appbar] region is inert (hidden from the accessibility tree, behind a tap-taking scrim); header,
 * body and actions form the dialog: a node with the dialog semantics, a traversal group, named by [dialogLabel] (the h1
 * text, Android's pane title). Compose Multiplatform 1.12 maps neither to iOS: there is no `accessibilityViewIsModal`,
 * so on iOS modality rests on the backdrop being cleared from the tree (SPIKE-LOG M7). The header holds exactly one
 * `EL-text` title: it is the h1 and takes [headingFocus]. [layout] defaults to the brand's selection.
 * Test tags: [id], `<id>.appbar`, `<id>.scrim`, `<id>.dialog`, `<id>.header`, `<id>.body`, `<id>.actions`.
 */
@Composable
fun TplSheet(
    id: String,
    header: @Composable () -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    layout: UxDecisionSteps = UxThemeAccess.brand.layouts.decisionSteps,
    dialogLabel: String? = null,
    headingFocus: FocusRequester? = null,
    appbar: (@Composable () -> Unit)? = null,
    body: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val tokens = UxThemeAccess.tokens
    val sheetShape = RoundedCornerShape(topStart = tokens.radius.sheet, topEnd = tokens.radius.sheet)
    CompositionLocalProvider(LocalUxHeadingFocus provides headingFocus) {
        when (layout) {
            UxDecisionSteps.Sheet -> Box(modifier.testTag(id).fillMaxSize().background(tokens.colors.surfaceDefault)) {
                if (appbar != null) {
                    Box(
                        Modifier.align(Alignment.TopCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing)
                            .clearAndSetSemantics { testTag = "$id.appbar" },
                    ) { appbar() }
                }
                Box(
                    Modifier.fillMaxSize()
                        .background(tokens.comp.sheet.scrim.copy(alpha = tokens.effects.scrimAlpha))
                        .pointerInput(Unit) { detectTapGestures { } }
                        .clearAndSetSemantics { testTag = "$id.scrim" },
                )
                Dialog(
                    id,
                    dialogLabel,
                    header,
                    body,
                    actions,
                    Modifier.align(Alignment.BottomCenter).uxElevation(tokens.comp.sheet.shadow, sheetShape).clip(sheetShape),
                    bottomInset = true,
                )
            }
            UxDecisionSteps.Page -> Box(
                modifier.testTag(id).fillMaxSize().background(tokens.colors.surfaceRaised)
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) { Dialog(id, dialogLabel, header, body, actions, Modifier.fillMaxSize(), bottomInset = false) }
        }
    }
}

@Composable
private fun Dialog(
    id: String,
    label: String?,
    header: @Composable () -> Unit,
    body: (@Composable ColumnScope.() -> Unit)?,
    actions: @Composable ColumnScope.() -> Unit,
    modifier: Modifier,
    bottomInset: Boolean,
) {
    val tokens = UxThemeAccess.tokens
    Column(
        modifier.testTag("$id.dialog").background(tokens.colors.surfaceRaised).semantics {
            dialog()
            isTraversalGroup = true
            if (label != null) paneTitle = label
        },
    ) {
        Box(Modifier.testTag("$id.header").fillMaxWidth().padding(tokens.space.gutter)) { header() }
        if (body != null) {
            Column(
                Modifier.testTag("$id.body").weight(1f, fill = false).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(horizontal = tokens.space.gutter),
                verticalArrangement = Arrangement.spacedBy(tokens.space.m),
                content = body,
            )
        } else {
            Box(Modifier.weight(1f, fill = false))
        }
        ActionsRegion("$id.actions", actions)
        if (bottomInset) Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
    }
}
