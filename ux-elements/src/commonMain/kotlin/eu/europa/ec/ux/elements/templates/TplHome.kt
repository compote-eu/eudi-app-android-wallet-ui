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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import eu.europa.ec.ux.elements.LocalUxBarTitle
import eu.europa.ec.ux.elements.LocalUxBottomBarPresence
import eu.europa.ec.ux.elements.LocalUxFullWidthActions
import eu.europa.ec.ux.elements.LocalUxHeadingFocus
import eu.europa.ec.ux.elements.UxComp
import eu.europa.ec.ux.theme.UxThemeAccess
import eu.europa.ec.ux.theme.uxElevation

private const val SCREEN = "comp.screen"

/**
 * TPL-home: the wallet's start screen. One scroll area holds the [appbar] band, the [header] (the screen's h1, drawn
 * in the band between the bar's controls when there is a bar, as the library's renderer does; else the first block),
 * the optional [switch], [notice]s, [identity] card, [documentsHeading] row (heading and link on one line), [cards],
 * [body] (the empty state) and [footer]; the [actions] are fixed at the bottom, over the content, which gets room for
 * them. An action region that holds an `EL-bottom-bar` floats transparent with the bar's inset around it; otherwise it is
 * the raised band. Empty regions take no space. Safe-area insets: the status bar zone continues the band, the sides and
 * the bottom edge are padded. Every spacing, ground and shadow is a `comp.screen.*` / `comp.bottom-bar.*` token. Test
 * tags: [id], `<id>.appbar`, `<id>.header`, `<id>.switch`, `<id>.notice`, `<id>.identity`, `<id>.documents-heading`,
 * `<id>.cards`, `<id>.body`, `<id>.footer`, `<id>.actions`.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
fun TplHome(
    id: String,
    header: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    headingFocus: FocusRequester? = null,
    appbar: (@Composable () -> Unit)? = null,
    switch: (@Composable ColumnScope.() -> Unit)? = null,
    notice: (@Composable ColumnScope.() -> Unit)? = null,
    identity: (@Composable ColumnScope.() -> Unit)? = null,
    documentsHeading: (@Composable RowScope.() -> Unit)? = null,
    cards: (@Composable ColumnScope.() -> Unit)? = null,
    body: (@Composable ColumnScope.() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val tokens = UxThemeAccess.tokens
    val screen = UxComp(SCREEN)
    val bar = UxComp("comp.app-bar")
    val bottomBar = UxComp("comp.bottom-bar")
    val barPresent = remember { mutableStateOf(false) }
    val density = LocalDensity.current
    var actionsHeight by remember { mutableStateOf(Dp.Hairline) }
    val gap = screen.dp("gap", tokens.space.m)
    val gutter = screen.dp("gutter", tokens.space.gutter)
    CompositionLocalProvider(
        LocalUxHeadingFocus provides headingFocus,
        LocalUxBottomBarPresence provides barPresent,
    ) {
        Column(modifier.testTag(id).fillMaxSize().background(screen.color("page-bg", tokens.colors.surfacePage))) {
            val top = if (appbar != null) tokens.colors.surfaceAppBar else screen.color("page-bg", tokens.colors.surfacePage)
            Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(top))
            Box(
                Modifier.weight(1f).fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
            ) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = actionsHeight)) {
                    if (appbar != null) {
                        Box(Modifier.testTag("$id.appbar").fillMaxWidth()) {
                            appbar()
                            Column(
                                Modifier.testTag("$id.header").matchParentSize().padding(horizontal = bar.dp("title-inset", tokens.size.target)),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                CompositionLocalProvider(LocalUxBarTitle provides true) { header() }
                            }
                        }
                    }
                    if (appbar == null) {
                        Block("$id.header", gutter, screen.dp("header-padding-top", gap), gap, gap, header)
                    }
                    val firstScroll = listOf(notice, identity, documentsHeading, cards, body, footer).indexOfFirst { it != null }
                    switch?.let { Block("$id.switch", gutter, gap, gap, gap, it) }
                    notice?.let { Block("$id.notice", gutter, openTop(0 == firstScroll && switch == null, screen, gap), gap, gap, it) }
                    identity?.let { Block("$id.identity", gutter, openTop(1 == firstScroll && switch == null, screen, gap), gap, gap, it) }
                    documentsHeading?.let {
                        Row(
                            Modifier.testTag("$id.documents-heading").fillMaxWidth()
                                .padding(start = gutter, end = gutter, top = openTop(2 == firstScroll && switch == null, screen, gap), bottom = gap),
                            horizontalArrangement = Arrangement.spacedBy(gap, Alignment.Start),
                            verticalAlignment = Alignment.CenterVertically,
                            content = it,
                        )
                    }
                    cards?.let {
                        Block("$id.cards", gutter, openTop(3 == firstScroll && switch == null, screen, gap), screen.dp("cards-gap", gap), gap, it)
                    }
                    body?.let { Block("$id.body", gutter, openTop(4 == firstScroll && switch == null, screen, gap), gap, gap, it) }
                    footer?.let { Block("$id.footer", gutter, openTop(5 == firstScroll && switch == null, screen, gap), gap, gap, it) }
                }
                if (actions != null) {
                    val floating = barPresent.value
                    val inset = bottomBar.dp("inset", gutter)
                    val band = if (floating) {
                        Modifier.padding(start = inset, end = inset, bottom = inset)
                    } else {
                        Modifier.uxElevation(screen.shadow("action-shadow"), RectangleShape)
                            .background(screen.color("actions-bg", tokens.colors.surfaceDefault))
                            .padding(start = gutter, end = gutter, top = screen.dp("actions-padding-top", gap), bottom = screen.dp("actions-padding-bottom", gap))
                    }
                    CompositionLocalProvider(LocalUxFullWidthActions provides true) {
                        Column(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { actionsHeight = with(density) { it.height.toDp() } }
                                .testTag("$id.actions").then(band),
                            verticalArrangement = Arrangement.spacedBy(screen.dp("actions-gap", tokens.space.s)),
                            content = actions,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun openTop(first: Boolean, screen: UxComp, gap: Dp): Dp = if (first) screen.dp("band-gap", gap) else Dp.Hairline

@Composable
private fun Block(tag: String, gutter: Dp, top: Dp, gap: Dp, bottom: Dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.testTag(tag).fillMaxWidth().padding(start = gutter, end = gutter, top = top, bottom = bottom),
        verticalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}
