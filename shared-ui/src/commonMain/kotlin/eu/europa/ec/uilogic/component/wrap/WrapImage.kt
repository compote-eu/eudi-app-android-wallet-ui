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

package eu.europa.ec.uilogic.component.wrap

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import org.jetbrains.compose.resources.stringResource
import eu.europa.ec.uilogic.component.IconDataUi
import eu.europa.ec.uilogic.component.contentDescriptionRes
import eu.europa.ec.uilogic.component.imageVector
import eu.europa.ec.uilogic.component.drawableResource
import org.jetbrains.compose.resources.painterResource

@Composable
fun WrapImage(
    iconData: IconDataUi,
    modifier: Modifier = Modifier,
    colorFilter: ColorFilter? = null,
    contentScale: ContentScale? = null,
) {
    val iconContentDescription = stringResource(iconData.contentDescriptionRes)

    iconData.drawableResource?.let { drawable ->
        Image(
            modifier = modifier,
            painter = painterResource(drawable),
            contentDescription = iconContentDescription,
            colorFilter = colorFilter,
            contentScale = contentScale ?: ContentScale.FillBounds,
        )
    } ?: run {
        iconData.imageVector?.let { imageVector ->
            Image(
                modifier = modifier,
                imageVector = imageVector,
                contentDescription = iconContentDescription,
                colorFilter = colorFilter,
                contentScale = contentScale ?: ContentScale.FillBounds,
            )
        }
    }
}

/** Widened from `BitmapPainter` to [Painter] so a platform-drawn image — the QR code — fits too. */
@Composable
fun WrapImage(
    modifier: Modifier = Modifier,
    painter: Painter,
    contentDescription: String,
    colorFilter: ColorFilter? = null,
    contentScale: ContentScale? = null,
) {
    Image(
        modifier = modifier,
        painter = painter,
        contentDescription = contentDescription,
        colorFilter = colorFilter,
        contentScale = contentScale ?: ContentScale.FillBounds,
    )
}

@Composable
fun WrapImage(
    modifier: Modifier = Modifier,
    bitmap: ImageBitmap?,
    contentDescription: String,
    colorFilter: ColorFilter? = null,
    contentScale: ContentScale? = null,
) {
    bitmap?.let {
        Image(
            modifier = modifier,
            bitmap = it,
            contentDescription = contentDescription,
            colorFilter = colorFilter,
            contentScale = contentScale ?: ContentScale.FillBounds,
        )
    }
}