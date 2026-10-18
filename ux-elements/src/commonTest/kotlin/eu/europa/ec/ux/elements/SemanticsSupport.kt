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

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import eu.europa.ec.ux.theme.UxTheme

internal val isHeading: SemanticsMatcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
internal val noHeading: SemanticsMatcher = SemanticsMatcher.keyNotDefined(SemanticsProperties.Heading)
internal val noLiveRegion: SemanticsMatcher = SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion)
internal fun live(mode: LiveRegionMode): SemanticsMatcher = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, mode)

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.setUx(content: @Composable () -> Unit) = setContent { UxTheme { content() } }
