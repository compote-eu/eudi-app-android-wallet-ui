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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.testTag
import eu.europa.ec.ux.theme.UxThemeAccess

/**
 * EL-qr-viewfinder: the camera region with a marked square scan area. It is not operable (scanning completes by a
 * system event), has no moving or flashing part, and is one image named by [label] (nothing inside is announced).
 * `scanning = false` dims the frame. The camera feed is a neutral placeholder surface here. Test tag: [id].
 */
@Composable
fun UxQrViewfinder(
    id: String,
    label: String,
    modifier: Modifier = Modifier,
    scanning: Boolean = true,
) {
    val tokens = UxThemeAccess.tokens
    val frame = tokens.colors.borderInverse
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(tokens.radius.card))
            .background(tokens.colors.surfaceInverse)
            .clearAndSetSemantics {
                testTag = id
                contentDescription = label
                role = Role.Image
            },
    ) {
        Canvas(Modifier.fillMaxSize().padding(tokens.space.section).then(if (scanning) Modifier else Modifier.alpha(tokens.effects.dimmedAlpha))) {
            val side = minOf(size.width, size.height)
            val left = (size.width - side) / 2f
            val top = (size.height - side) / 2f
            val arm = side / tokens.comp.qrViewfinder.armRatio
            val stroke = Stroke(width = tokens.comp.qrViewfinder.strokeWidth.toPx())
            fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                drawLine(frame, Offset(x, y), Offset(x + dx * arm, y), stroke.width)
                drawLine(frame, Offset(x, y), Offset(x, y + dy * arm), stroke.width)
            }
            corner(left, top, 1f, 1f)
            corner(left + side, top, -1f, 1f)
            corner(left, top + side, 1f, -1f)
            corner(left + side, top + side, -1f, -1f)
        }
    }
}
