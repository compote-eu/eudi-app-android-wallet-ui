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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import eu.europa.ec.ux.elements.LocalUxFullWidthActions
import eu.europa.ec.ux.theme.UxThemeAccess

/** The bottom-fixed action region of a template: the primary first, the dismissive action second. */
@Composable
internal fun ActionsRegion(tag: String, content: @Composable ColumnScope.() -> Unit) {
    val tokens = UxThemeAccess.tokens
    CompositionLocalProvider(LocalUxFullWidthActions provides true) {
        Column(
            modifier = Modifier.testTag(tag).fillMaxWidth().padding(tokens.space.gutter),
            verticalArrangement = Arrangement.spacedBy(tokens.space.s),
            content = content,
        )
    }
}

/** The scroll region whose content is vertically centred in the free height (hero regions). */
@Composable
internal fun ColumnScope.CenteredScroll(tag: String, content: @Composable ColumnScope.() -> Unit) {
    val tokens = UxThemeAccess.tokens
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().testTag(tag)) {
        val free = maxHeight
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(min = free).padding(horizontal = tokens.space.gutter),
                verticalArrangement = Arrangement.spacedBy(tokens.space.m, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content,
            )
        }
    }
}

/** The page ground and the safe-area padding of a full-screen template. */
@Composable
internal fun PageRoot(id: String, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier.testTag(id).background(UxThemeAccess.tokens.colors.surfaceDefault)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        content = content,
    )
}
