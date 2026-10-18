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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import eu.europa.ec.ux.elements.LocalUxHeadingFocus
import eu.europa.ec.ux.theme.UxThemeAccess

/**
 * TPL-scan: the camera step. The [instruction] region reads first (an `EL-text` title, the h1, which takes
 * [headingFocus], and an optional body text), then the [camera] (an `EL-qr-viewfinder`) that fills the free height
 * (at least 240 dp), then the single bottom action. Test tags: [id], `<id>.instruction`, `<id>.camera`, `<id>.actions`.
 */
@Composable
fun TplScan(
    id: String,
    instruction: @Composable ColumnScope.() -> Unit,
    camera: @Composable () -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    headingFocus: FocusRequester? = null,
) {
    val tokens = UxThemeAccess.tokens
    CompositionLocalProvider(LocalUxHeadingFocus provides headingFocus) {
        PageRoot(id, modifier) {
            Column(
                Modifier.testTag("$id.instruction").fillMaxWidth().padding(tokens.space.gutter),
                verticalArrangement = Arrangement.spacedBy(tokens.space.s),
                content = instruction,
            )
            Box(
                Modifier.testTag("$id.camera").weight(1f).fillMaxWidth().heightIn(min = tokens.comp.scan.cameraMinHeight)
                    .padding(horizontal = tokens.space.gutter),
            ) { camera() }
            ActionsRegion("$id.actions", actions)
        }
    }
}
