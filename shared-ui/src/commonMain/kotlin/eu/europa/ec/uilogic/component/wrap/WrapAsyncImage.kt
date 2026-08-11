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
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.svg.SvgDecoder
import eu.europa.ec.uilogic.component.IconDataUi
import eu.europa.ec.uilogic.component.drawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * Loads a remote image, with SVG support — issuer logos are frequently SVG.
 *
 * Shared rather than per-platform because Coil 3 is multiplatform: the only Android-specific thing here
 * was `LocalContext`, and Coil supplies `LocalPlatformContext` for exactly this, resolving to the
 * `Context` on Android and to a platform-appropriate handle elsewhere.
 */
@Composable
fun WrapAsyncImage(
    source: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Fit,
    placeholder: IconDataUi? = null,
    error: IconDataUi? = null,
    fallback: IconDataUi? = null,
) {
    val context = LocalPlatformContext.current
    // Remembered, unlike before: an `ImageLoader` owns a memory and disk cache, so building one per
    // recomposition gave every pass a fresh empty cache and re-fetched the same image repeatedly.
    val imageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components {
                add(SvgDecoder.Factory())
            }
            .build()
    }

    AsyncImage(
        modifier = modifier,
        model = ImageRequest.Builder(context)
            .data(source)
            .build(),
        imageLoader = imageLoader,
        contentDescription = contentDescription,
        contentScale = contentScale,
        error = error?.drawableResource?.let { painterResource(it) },
        fallback = fallback?.drawableResource?.let { painterResource(it) },
        placeholder = placeholder?.drawableResource?.let { painterResource(it) }
    )
}