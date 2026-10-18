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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import eu.europa.ec.ux.theme.UxShadow
import eu.europa.ec.ux.theme.UxShadowLayer
import eu.europa.ec.ux.theme.uxElevation
import kotlin.test.Test
import kotlin.test.assertEquals

/** M5c: `uxElevation` really draws (iOS simulator, pixel check): an inset ring over the content, a ring behind the fill. */
@OptIn(ExperimentalTestApi::class)
class ElevationDrawnIosTest {

    private fun ring(inset: Boolean) =
        UxShadow(listOf(UxShadowLayer(Color.Black, 0.dp, 0.dp, 0.dp, 2.dp, inset)))

    @Test
    fun anInsetRingIsDrawnOverTheContentAtTheEdgeOnly() = runComposeUiTest {
        setUx { Box(Modifier.testTag("box").size(40.dp).uxElevation(ring(inset = true), RectangleShape).background(Color.White)) }
        val image = onNodeWithTag("box").captureToImage().toPixelMap()
        assertEquals(Color.Black, image[0, image.height / 2], "the left edge pixel is the ring")
        assertEquals(Color.White, image[image.width / 2, image.height / 2], "the centre is the fill")
    }
}
