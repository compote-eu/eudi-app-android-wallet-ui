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
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.svg.SvgDecoder
import eu.europa.ec.uilogic.component.IconDataUi
import eu.europa.ec.uilogic.component.drawableResource
import org.jetbrains.compose.resources.painterResource

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
    val context = LocalContext.current
    val imageLoader = ImageLoader.Builder(context)
        .components {
            add(SvgDecoder.Factory())
        }
        .build()

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