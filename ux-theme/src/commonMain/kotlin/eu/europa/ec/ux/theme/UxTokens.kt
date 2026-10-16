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


package eu.europa.ec.ux.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp

/*
 * The token value types: the hand-written shell that the generated per-brand `UxTokenValues` objects fill in
 * (boundary §2.6). Names follow `library/tokens/sys.contract.yaml` (sys.color.*, sys.type.*, sys.space.*, ...).
 */

/** `sys.color.*`: the roles the nine elements and four templates read. */
data class UxColors(
    val textPrimary: Color,
    val textSecondary: Color,
    val textInverse: Color,
    val textLink: Color,
    val textHeading: Color,
    val textLabel: Color,
    val textOnBrand: Color,
    val textOnAppBar: Color,
    val surfaceDefault: Color,
    val surfaceSubtle: Color,
    val surfaceInverse: Color,
    val surfaceRaised: Color,
    val surfaceAppBar: Color,
    val surfacePage: Color,
    val surfaceCard: Color,
    val overlayScrim: Color,
    val borderDefault: Color,
    val borderInverse: Color,
    val actionPrimaryBg: Color,
    val actionPrimaryFg: Color,
    val actionSecondaryBg: Color,
    val actionSecondaryFg: Color,
    val actionFloatingBg: Color,
    val actionFloatingFg: Color,
    val focusRing: Color,
    val feedbackInfo: UxFeedbackColors,
    val feedbackSuccess: UxFeedbackColors,
    val feedbackWarning: UxFeedbackColors,
    val feedbackError: UxFeedbackColors,
)

/** `sys.color.feedback.<tone>.*`. */
data class UxFeedbackColors(val bg: Color, val border: Color, val fg: Color, val ring: Color)

/** `sys.type.*`: one text style per typographic role (size, line height and weight; the colour is set by the element). */
data class UxTypography(
    val title: TextStyle,
    val headline: TextStyle,
    val section: TextStyle,
    val body: TextStyle,
    val small: TextStyle,
    val caption: TextStyle,
    val display: TextStyle,
    val appBar: TextStyle,
    val button: TextStyle,
    val status: TextStyle,
)

/** `sys.space.*`. */
data class UxSpacing(val xs: Dp, val s: Dp, val m: Dp, val section: Dp, val gutter: Dp)

/** `sys.radius.*`. */
data class UxRadii(val control: Dp, val card: Dp, val sheet: Dp, val pill: Dp, val floating: Dp)

/** `sys.size.*`. */
data class UxSizes(val target: Dp, val iconMd: Dp, val iconSm: Dp, val focusRing: Dp, val glyphHero: Dp)

/** One resolved token set (one brand, one mode). */
data class UxTokens(
    val colors: UxColors,
    val type: UxTypography,
    val space: UxSpacing,
    val radius: UxRadii,
    val size: UxSizes,
)

/** What a generated brand object implements (`object EudiwV1Light : UxTokenValues`). */
interface UxTokenValues {
    val tokens: UxTokens
}

/** The user's colour scheme. The brand binds the modes it has; the others fall back (resolver `optional`). */
enum class UxMode { Light, Dark }
