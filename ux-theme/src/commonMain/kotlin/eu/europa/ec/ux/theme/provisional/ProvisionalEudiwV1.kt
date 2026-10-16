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


// PROVISIONAL: hand-written eudiw_v1 light token set. The values are read from
// ux/product/theme/{edoklady,eudiw_v1}/{ref,sys.light}.tokens.json of the spec repo (2026-10-16). The generated
// `eu.europa.ec.ux.theme.generated` Kotlin replaces this file at the M6 sync (boundary §2.6). The webfont is not
// bundled, so the platform default font family stands in for `ref.font.family.sans`.
package eu.europa.ec.ux.theme.provisional

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.europa.ec.ux.theme.UxBrand
import eu.europa.ec.ux.theme.UxColors
import eu.europa.ec.ux.theme.UxFeedbackColors
import eu.europa.ec.ux.theme.UxRadii
import eu.europa.ec.ux.theme.UxSizes
import eu.europa.ec.ux.theme.UxSpacing
import eu.europa.ec.ux.theme.UxTokenValues
import eu.europa.ec.ux.theme.UxTokens
import eu.europa.ec.ux.theme.UxTypography

private object Ref {
    val primary100 = Color(0xFFEFF5FE)
    val primary400 = Color(0xFF126DFF)
    val primary500 = Color(0xFF0B4199)
    val primary600 = Color(0xFF072C66)
    val white = Color(0xFFFFFFFF)
    val black = Color(0xFF000000)
    val n90 = Color(0xFFFAFAFA)
    val n200 = Color(0xFFEEEEEE)
    val n300 = Color(0xFFE0E0E0)
    val n700 = Color(0xFF616161)
    val positive = Color(0xFF078814)
    val positiveLight = Color(0xFFEBF5EC)
    val positiveMedium = Color(0xFF9FDAA5)
    val positiveDark = Color(0xFF033608)
    val danger = Color(0xFFC3112B)
    val dangerLight = Color(0xFFFBEEF0)
    val dangerMedium = Color(0xFFF8B5B9)
    val dangerDark = Color(0xFF4E0711)
    val caution = Color(0xFFBD730C)
    val cautionLight = Color(0xFFFAF4EC)
    val cautionMedium = Color(0xFFF0D3A8)
    val cautionDark = Color(0xFF4E2A00)
    val focus = Color(0xFFD96E00)
}

private fun style(size: Int, line: Int, weight: FontWeight) =
    TextStyle(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight)

/** PROVISIONAL `object EudiwV1Light : UxTokenValues`. */
object ProvisionalEudiwV1Light : UxTokenValues {
    override val tokens = UxTokens(
        colors = UxColors(
            textPrimary = Ref.black,
            textSecondary = Ref.n700,
            textInverse = Ref.white,
            textLink = Ref.primary500,
            textHeading = Ref.primary600,
            textLabel = Ref.n700,
            textOnBrand = Ref.white,
            textOnAppBar = Ref.primary600,
            surfaceDefault = Ref.white,
            surfaceSubtle = Ref.n200,
            surfaceInverse = Ref.black,
            surfaceRaised = Ref.white,
            surfaceAppBar = Ref.white,
            surfacePage = Ref.n90,
            surfaceCard = Ref.white,
            overlayScrim = Ref.black,
            borderDefault = Ref.n300,
            borderInverse = Ref.white,
            actionPrimaryBg = Ref.primary500,
            actionPrimaryFg = Ref.white,
            actionSecondaryBg = Ref.primary100,
            actionSecondaryFg = Ref.primary600,
            actionFloatingBg = Ref.primary500,
            actionFloatingFg = Ref.white,
            focusRing = Ref.focus,
            feedbackInfo = UxFeedbackColors(Ref.primary100, Ref.primary400, Ref.primary600, Ref.primary100),
            feedbackSuccess = UxFeedbackColors(Ref.positiveLight, Ref.positive, Ref.positiveDark, Ref.positiveMedium),
            feedbackWarning = UxFeedbackColors(Ref.cautionLight, Ref.caution, Ref.cautionDark, Ref.cautionMedium),
            feedbackError = UxFeedbackColors(Ref.dangerLight, Ref.danger, Ref.dangerDark, Ref.dangerMedium),
        ),
        type = UxTypography(
            title = style(24, 35, FontWeight.Bold),
            headline = style(28, 34, FontWeight.Bold),
            section = style(18, 24, FontWeight.SemiBold),
            body = style(16, 24, FontWeight.Normal),
            small = style(14, 20, FontWeight.Normal),
            caption = style(12, 16, FontWeight.Normal),
            display = style(36, 45, FontWeight.SemiBold),
            appBar = style(18, 18, FontWeight.SemiBold),
            button = style(16, 24, FontWeight.SemiBold),
            status = style(13, 16, FontWeight.SemiBold),
        ),
        space = UxSpacing(xs = 5.dp, s = 10.dp, m = 15.dp, section = 30.dp, gutter = 20.dp),
        radius = UxRadii(control = 5.dp, card = 10.dp, sheet = 15.dp, pill = 99.dp, floating = 24.dp),
        size = UxSizes(target = 48.dp, iconMd = 24.dp, iconSm = 18.dp, focusRing = 3.dp, glyphHero = 72.dp),
    )
}

/** PROVISIONAL `UxBrandConfig` for eudiw_v1: `EL-attribute-list {pairs: columns}`, `EL-attribute-item {checkbox: outline}`. */
val ProvisionalEudiwV1 = UxBrand(id = "eudiw_v1", light = ProvisionalEudiwV1Light)
