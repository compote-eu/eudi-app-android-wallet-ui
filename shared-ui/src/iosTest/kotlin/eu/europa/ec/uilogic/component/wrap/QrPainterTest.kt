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

// The iOS QR image is sized in pixels. The screen draws a bitmap painter at its pixel size, so an image sized in
// points came out half as large on a 2× screen as the same QR on Android. (The image itself cannot be built here:
// `CIContext()` is nil in a test binary, which has no rendering context.)
package eu.europa.ec.uilogic.component.wrap

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class QrPainterTest {

    @Test
    fun a_2x_screen_asks_for_twice_the_pixels() {
        assertEquals(300f, qrSizePixels(150.dp, Density(2f)))
        assertEquals(450f, qrSizePixels(150.dp, Density(3f)))
    }
}
