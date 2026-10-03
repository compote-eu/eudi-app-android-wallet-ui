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

import coil3.annotation.ExperimentalCoilApi
import coil3.svg.Svg
import okio.Buffer

/** Coil's Skia parser, given the SVG with its class rules already copied onto the elements. */
@OptIn(ExperimentalCoilApi::class)
internal actual val platformSvgParser: Svg.Parser = Svg.Parser { source ->
    Svg.Parser.DEFAULT.parse(Buffer().writeUtf8(inlineSvgClassStyles(source.readUtf8())))
}
