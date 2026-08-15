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

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A QR code for [content], as a [Painter] the caller can just draw.
 *
 * Expect/actual rather than a shared encoder, because both platforms already ship one and neither ships
 * the other's: Android has zxing on the classpath (the wallet scans QR codes with it too), and iOS has
 * CoreImage's `CIQRCodeGenerator`. Adding a multiplatform QR library to get one implementation would mean
 * a third encoder in the app rather than none.
 *
 * Returns a blank painter rather than throwing when the content cannot be encoded — a QR that will not
 * render is a screen that shows nothing, not a crash mid-presentation.
 */
@Composable
expect fun rememberQrPainter(
    content: String,
    size: Dp = 150.dp,
    padding: Dp = 0.dp,
): Painter
