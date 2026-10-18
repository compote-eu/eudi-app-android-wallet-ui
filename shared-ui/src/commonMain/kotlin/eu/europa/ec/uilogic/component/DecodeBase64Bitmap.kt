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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import eu.europa.ec.businesslogic.extension.decodeBase64ToByteArrays
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun rememberBase64DecodedBitmap(base64Image: String): ImageBitmap? {
    var decodedBitmap by remember(base64Image) {
        mutableStateOf<ImageBitmap?>(null)
    }

    LaunchedEffect(base64Image) {
        decodedBitmap = withContext(Dispatchers.Default) {
            decodeBase64Bitmap(base64Image)
        }
    }

    return decodedBitmap
}

/**
 * Walks every candidate `decodeBase64ToByteArrays` returned until one is genuinely an image — which is
 * exactly why that function returns a list: the same payload can decode under both Base64 alphabets and
 * only one result will be valid image bytes.
 */
private fun decodeBase64Bitmap(value: String): ImageBitmap? =
    value.decodeBase64ToByteArrays().firstNotNullOfOrNull { bytes -> bytes.toImageBitmapOrNull() }

/**
 * Decodes encoded image bytes (PNG/JPEG/…) into an [ImageBitmap], or null when they are not an image.
 * The only platform-specific step: Android decodes through `BitmapFactory`, iOS through Skia.
 */
internal expect fun ByteArray.toImageBitmapOrNull(): ImageBitmap?