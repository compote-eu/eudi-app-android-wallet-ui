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

package eu.europa.ec.resourceslogic.theme.values

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import eu.europa.ec.resourceslogic.theme.templates.ThemeShapesTemplate
import eu.europa.ec.resourceslogic.theme.values.ThemeShapes.Companion.LARGE
import eu.europa.ec.resourceslogic.theme.values.ThemeShapes.Companion.SMALL

/**
 * `sk` flavor shapes — ID SK uses a small control radius (~5px for buttons/inputs, 4px for
 * text areas); larger surfaces stay modestly rounded. This is the `sk` source-set override of
 * the default [ThemeShapes].
 */
class ThemeShapes {
    companion object {
        const val EXTRA_SMALL = 4.0
        const val SMALL = 5.0
        const val MEDIUM = 8.0
        const val LARGE = 12.0
        const val EXTRA_LARGE = 16.0

        val shapes = ThemeShapesTemplate(
            extraSmall = EXTRA_SMALL,
            small = SMALL,
            medium = MEDIUM,
            large = LARGE,
            extraLarge = EXTRA_LARGE
        )
    }
}

val Shapes.bottomCorneredShapeSmall: Shape
    @Composable get() = RoundedCornerShape(bottomStart = SMALL.dp, bottomEnd = SMALL.dp)

val Shapes.topCorneredShapeSmall: Shape
    @Composable get() = RoundedCornerShape(topStart = SMALL.dp, topEnd = SMALL.dp)

val Shapes.allCorneredShapeSmall: Shape
    @Composable get() = RoundedCornerShape(SMALL.dp)

val Shapes.allCorneredShapeLarge: Shape
    @Composable get() = RoundedCornerShape(LARGE.dp)
