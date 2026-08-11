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

package eu.europa.ec.uilogic.component

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.ic_logo_lockup_wordmark
import eu.europa.ec.uilogic.component.preview.PreviewTheme
import eu.europa.ec.uilogic.component.preview.ThemeModePreviews
import eu.europa.ec.uilogic.component.wrap.WrapImage
import org.jetbrains.compose.resources.painterResource

/**
 * The brand lockup: the coloured mark plus the wordmark beside it.
 *
 * Drawn as two layers rather than one image so the wordmark can follow the colour scheme. Upstream the
 * single asset painted its text with `android:fillColor="?colorOnSurface"`, and compose-resources — which
 * now serves the drawable corpus on both platforms — cannot resolve theme attributes, so that text would
 * have been stuck at its light-theme value. Tinting it here restores the behaviour and, unlike the XML
 * attribute, works on iOS too.
 *
 * The two assets keep the original 161x52 viewport, so `matchParentSize` aligns them exactly; there is no
 * coordinate maths and the result is pixel-identical to the old single image apart from the themed text.
 * Only the mark carries a content description — the lockup should be announced once.
 */
@Composable
fun AppIconAndText(
    modifier: Modifier = Modifier,
    appIconAndTextData: AppIconAndTextDataUi
) {
    Box(modifier = modifier) {
        WrapImage(iconData = appIconAndTextData.appIcon)
        Icon(
            painter = painterResource(Res.drawable.ic_logo_lockup_wordmark),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.matchParentSize(),
        )
    }
}

@ThemeModePreviews
@Composable
private fun AppIconAndTextPreview() {
    PreviewTheme {
        AppIconAndText(
            appIconAndTextData = AppIconAndTextDataUi()
        )
    }
}