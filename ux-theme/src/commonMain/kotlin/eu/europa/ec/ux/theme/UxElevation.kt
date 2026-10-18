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


package eu.europa.ec.ux.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate

/**
 * Draws a `sys.elevation.*` token (or a `comp.*.shadow` alias of one) around an element of [shape], one layer after
 * the other. Apply it before `clip` and `background` (the shadow is drawn outside the bounds, rings behind the fill).
 *
 * Compose has no CSS `box-shadow`, so this is an approximation, declared in SPIKE-LOG M5c:
 * - a layer with a blur is the platform shadow of an elevation of half the blur, tinted by the layer colour (the
 *   offsets and a negative spread are not honoured: the platform light source decides the direction);
 * - a layer without a blur (a ring or a divider) is drawn exactly: the outline offset by `offsetX/offsetY` and grown
 *   by `spread`;
 * - an inset layer is drawn exactly, as a ring of `spread` width inside the outline, over the content.
 */
fun Modifier.uxElevation(shadow: UxShadow, shape: Shape): Modifier =
    shadow.layers.fold(this) { modifier, layer -> modifier.then(layer.modifier(shape)) }

private fun UxShadowLayer.modifier(shape: Shape): Modifier = when {
    inset -> Modifier.drawWithContent {
        drawContent()
        val path = outline(shape)
        clipPath(path) { drawPath(path, color, style = Stroke(spread.toPx() * BOTH_SIDES)) }
    }
    blur.value > 0f -> Modifier.shadow(
        elevation = approximateElevation,
        shape = shape,
        clip = false,
        ambientColor = Color.Transparent,
        spotColor = color,
    )
    else -> Modifier.drawBehind {
        val path = outline(shape)
        translate(offsetX.toPx(), offsetY.toPx()) {
            drawPath(path, color)
            if (spread.value > 0f) drawPath(path, color, style = Stroke(spread.toPx() * BOTH_SIDES))
        }
    }
}

private fun DrawScope.outline(shape: Shape): Path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@outline)) }

/** A stroke is centred on the edge: twice the spread covers `spread` on each side. */
private const val BOTH_SIDES = 2f
