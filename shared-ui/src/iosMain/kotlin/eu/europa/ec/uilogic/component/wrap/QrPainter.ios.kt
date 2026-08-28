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
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.Image
import androidx.compose.ui.graphics.toComposeImageBitmap
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreGraphics.CGRectGetWidth
import platform.CoreImage.CIContext
import platform.CoreImage.CIFilter
import platform.CoreImage.createCGImage
import platform.CoreImage.filterWithName
import platform.Foundation.NSData
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataUsingEncoding
import platform.Foundation.setValue
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation
import platform.posix.memcpy

/**
 * CoreImage's `CIQRCodeGenerator`, the encoder iOS already has.
 *
 * The generator produces a tiny image — one pixel per module — so it is scaled up before rasterising,
 * with no interpolation, which is what keeps the modules crisp squares rather than a blur. The result goes
 * through PNG bytes into a Skia image: that is the one conversion Compose Multiplatform offers from a
 * `UIImage`, and a QR is small enough that the encode costs nothing worth optimising.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberQrPainter(
    content: String,
    size: Dp,
    padding: Dp,
): Painter = remember(content, size) {
    val bitmap = qrImageBitmap(content = content, sizePoints = size.value.toDouble())
    if (bitmap == null) {
        // A blank painter: the screen shows an empty square rather than dying mid-presentation.
        BitmapPainter(Image.makeFromEncoded(TRANSPARENT_PNG).toComposeImageBitmap())
    } else {
        BitmapPainter(bitmap)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun qrImageBitmap(content: String, sizePoints: Double) = runCatching {
    val data = (content as platform.Foundation.NSString).dataUsingEncoding(NSUTF8StringEncoding)
    val filter = CIFilter.filterWithName("CIQRCodeGenerator") ?: return@runCatching null
    filter.setValue(data, forKey = "inputMessage")
    // Medium error correction, as zxing defaults to on the Android side.
    filter.setValue("M", forKey = "inputCorrectionLevel")

    val output = filter.outputImage ?: return@runCatching null
    val scale = sizePoints / CGRectGetWidth(output.extent())
    val scaled = output.imageByApplyingTransform(CGAffineTransformMakeScale(scale, scale))

    val cgImage = CIContext().createCGImage(scaled, fromRect = scaled.extent())
        ?: return@runCatching null
    val png = UIImagePNGRepresentation(UIImage.imageWithCGImage(cgImage))
        ?: return@runCatching null

    Image.makeFromEncoded(png.toByteArray()).toComposeImageBitmap()
}.getOrNull()

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val bytes = ByteArray(length.toInt())
    if (bytes.isNotEmpty()) {
        bytes.usePinned { memcpy(it.addressOf(0), this.bytes, length) }
    }
    return bytes
}

/** A 1×1 transparent PNG, so the blank case is still a real image. */
private val TRANSPARENT_PNG: ByteArray = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D,
    0x49, 0x48, 0x44, 0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06,
    0x00, 0x00, 0x00, 0x1F, 0x15.toByte(), 0xC4.toByte(), 0x89.toByte(), 0x00, 0x00, 0x00,
    0x0A, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00, 0x05,
    0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(), 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E,
    0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
)
