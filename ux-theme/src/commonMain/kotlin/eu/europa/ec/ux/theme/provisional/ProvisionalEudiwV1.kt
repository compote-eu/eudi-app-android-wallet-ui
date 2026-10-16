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


// PROVISIONAL: hand-written transcription of the eudiw_v1 token sets for all four modes (light, high-contrast, dark,
// dark-high-contrast). The values are the resolved result (resolver.json: `components` set + brand + mode contexts) of
// ux/library/tokens and ux/product/theme/{edoklady,eudiw_v1} of the spec repo at commit
// ec5e58e3def93cfbd3ba846005ccd3e2c13f53ba (2026-10-16); the resolution procedure is in
// spike-evidence/M5c/exporter-requirements.md. The generated `eu.europa.ec.ux.theme.generated` Kotlin replaces this
// file at the M6 sync (boundary §2.6). The webfont is not bundled, so the platform sans family stands in for
// `sys.font.family.*`. Values marked NOT IN SPEC below have no token yet.
package eu.europa.ec.ux.theme.provisional

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.europa.ec.ux.theme.UxAppBarTokens
import eu.europa.ec.ux.theme.UxAttributeListTokens
import eu.europa.ec.ux.theme.UxBrand
import eu.europa.ec.ux.theme.UxButtonTokens
import eu.europa.ec.ux.theme.UxColors
import eu.europa.ec.ux.theme.UxComponentTokens
import eu.europa.ec.ux.theme.UxEffects
import eu.europa.ec.ux.theme.UxElevations
import eu.europa.ec.ux.theme.UxFeedbackColors
import eu.europa.ec.ux.theme.UxFonts
import eu.europa.ec.ux.theme.UxLinkTokens
import eu.europa.ec.ux.theme.UxQrViewfinderTokens
import eu.europa.ec.ux.theme.UxRadii
import eu.europa.ec.ux.theme.UxRpIdentityTokens
import eu.europa.ec.ux.theme.UxScanTokens
import eu.europa.ec.ux.theme.UxShadow
import eu.europa.ec.ux.theme.UxShadowLayer
import eu.europa.ec.ux.theme.UxSheetTokens
import eu.europa.ec.ux.theme.UxSizes
import eu.europa.ec.ux.theme.UxSpacing
import eu.europa.ec.ux.theme.UxTokenValues
import eu.europa.ec.ux.theme.UxTokens
import eu.europa.ec.ux.theme.UxTypography

/** `ref.font.family.sans` stands in as the platform sans family (the webfont is not bundled). */
private val base = FontFamily.SansSerif
private val heading = FontFamily.SansSerif

/** NOT IN SPEC: disabled and dimmed opacities, scrim opacity, hairline width (formerly hard-coded in the elements). */
private val effects = UxEffects(disabledAlpha = 0.4f, scrimAlpha = 0.4f, dimmedAlpha = 0.35f, hairline = 1.dp)

/** NOT IN SPEC: `comp.scan.camera-min-height`. */
private val scan = UxScanTokens(cameraMinHeight = 240.dp)

/** NOT IN SPEC: `comp.qr-viewfinder.stroke-width` and `arm-ratio` (the arm is a fifth of the square's side). */
private val qrViewfinder = UxQrViewfinderTokens(strokeWidth = 6.dp, armRatio = 5f)

private fun style(size: Int, line: Int, weight: FontWeight, family: FontFamily) =
    TextStyle(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, fontFamily = family)

private fun layer(argb: Long, x: Float, y: Float, blur: Float, spread: Float, inset: Boolean = false) =
    UxShadowLayer(Color(argb), x.dp, y.dp, blur.dp, spread.dp, inset)

private fun shadow(vararg layers: UxShadowLayer) = UxShadow(layers.toList())

/** PROVISIONAL `object EudiwV1Light : UxTokenValues` (mode `light`). */
object ProvisionalEudiwV1Light : UxTokenValues {
    override val tokens = UxTokens(
        colors = UxColors(
            textPrimary = Color(0xFF000000),
            textSecondary = Color(0xFF616161),
            textInverse = Color(0xFFFFFFFF),
            textLink = Color(0xFF0B4199),
            textHeading = Color(0xFF072C66),
            textLabel = Color(0xFF616161),
            textOnBrand = Color(0xFFFFFFFF),
            textOnAppBar = Color(0xFF072C66),
            surfaceDefault = Color(0xFFFFFFFF),
            surfaceSubtle = Color(0xFFEEEEEE),
            surfaceInverse = Color(0xFF000000),
            surfaceRaised = Color(0xFFFFFFFF),
            surfaceAppBar = Color(0xFFFFFFFF),
            surfacePage = Color(0xFFFAFAFA),
            surfaceCard = Color(0xFFFFFFFF),
            overlayScrim = Color(0xFF000000),
            borderDefault = Color(0xFFE0E0E0),
            borderInverse = Color(0xFFFFFFFF),
            actionPrimaryBg = Color(0xFF0B4199),
            actionPrimaryFg = Color(0xFFFFFFFF),
            actionSecondaryBg = Color(0xFFEFF5FE),
            actionSecondaryFg = Color(0xFF072C66),
            actionFloatingBg = Color(0xFF0B4199),
            actionFloatingFg = Color(0xFFFFFFFF),
            focusRing = Color(0xFFD96E00),
            feedbackInfo = UxFeedbackColors(Color(0xFFEFF5FE), Color(0xFF126DFF), Color(0xFF072C66), Color(0xFFEFF5FE)),
            feedbackSuccess = UxFeedbackColors(Color(0xFFEBF5EC), Color(0xFF078814), Color(0xFF033608), Color(0xFF9FDAA5)),
            feedbackWarning = UxFeedbackColors(Color(0xFFFAF4EC), Color(0xFFBD730C), Color(0xFF4E2A00), Color(0xFFF0D3A8)),
            feedbackError = UxFeedbackColors(Color(0xFFFBEEF0), Color(0xFFC3112B), Color(0xFF4E0711), Color(0xFFF8B5B9)),
        ),
        type = UxTypography(
            title = style(24, 35, FontWeight.Bold, heading),
            headline = style(28, 34, FontWeight.Bold, heading),
            section = style(18, 24, FontWeight.Bold, base),
            body = style(16, 24, FontWeight.Normal, base),
            small = style(14, 20, FontWeight.Normal, base),
            caption = style(12, 16, FontWeight.Normal, base),
            display = style(36, 45, FontWeight.Bold, base),
            appBar = style(18, 24, FontWeight.Bold, base),
            button = style(16, 24, FontWeight.Bold, base),
            status = style(13, 16, FontWeight.SemiBold, base),
        ),
        space = UxSpacing(xs = 5.dp, s = 10.dp, m = 15.dp, section = 30.dp, gutter = 20.dp),
        radius = UxRadii(control = 5.dp, card = 10.dp, sheet = 15.dp, pill = 99.dp, floating = 24.dp),
        size = UxSizes(target = 48.dp, iconMd = 24.dp, iconSm = 18.dp, focusRing = 3.dp, glyphHero = 72.dp),
        elevation = UxElevations(
            action = shadow(layer(0x2E000000, 0.0f, -8.0f, 24.0f, -12.0f)),
            bar = shadow(layer(0xFFE0E0E0, 0.0f, 0.5f, 0.0f, 0.5f)),
            card = shadow(layer(0xFFE0E0E0, 0.0f, 0.0f, 0.0f, 1.0f, inset = true)),
            floating = shadow(layer(0x290D264C, 0.0f, 8.0f, 24.0f, 0.0f), layer(0xFFE0E0E0, 0.0f, 0.0f, 0.0f, 1.0f)),
            floatingAction = shadow(layer(0x3D0D264C, 0.0f, 8.0f, 24.0f, 0.0f)),
            menu = shadow(layer(0x380D264C, 0.0f, 12.0f, 32.0f, 0.0f), layer(0xFFE0E0E0, 0.0f, 0.0f, 0.0f, 1.0f)),
            raised = shadow(layer(0x331A1A1A, 0.0f, 4.0f, 8.0f, 0.0f)),
            sheet = shadow(layer(0x40000000, 0.0f, -10.0f, 40.0f, 0.0f)),
        ),
        font = UxFonts(base = base, heading = heading, regular = FontWeight.Normal, label = FontWeight.Normal, semibold = FontWeight.SemiBold, bold = FontWeight.Bold, value = FontWeight.SemiBold, status = FontWeight.SemiBold),
        effects = effects,
        comp = UxComponentTokens(
            appBar = UxAppBarTokens(64.dp, 8.dp, 9.dp, FontWeight.Bold, shadow(layer(0xFFE0E0E0, 0.0f, 0.5f, 0.0f, 0.5f))),
            button = UxButtonTokens(FontWeight.Bold, shadow(layer(0x290D264C, 0.0f, 8.0f, 24.0f, 0.0f), layer(0xFFE0E0E0, 0.0f, 0.0f, 0.0f, 1.0f)), shadow(layer(0x3D0D264C, 0.0f, 8.0f, 24.0f, 0.0f))),
            link = UxLinkTokens(5.dp, FontWeight.Bold),
            sheet = UxSheetTokens(shadow(layer(0x40000000, 0.0f, -10.0f, 40.0f, 0.0f)), Color(0xFF000000)),
            rpIdentity = UxRpIdentityTokens(FontWeight.Bold, FontWeight.Normal),
            attributeList = UxAttributeListTokens(FontWeight.SemiBold),
            scan = scan,
            qrViewfinder = qrViewfinder,
        ),
    )
}

/** PROVISIONAL `object EudiwV1HighContrast : UxTokenValues` (mode `high-contrast`). */
object ProvisionalEudiwV1HighContrast : UxTokenValues {
    override val tokens = UxTokens(
        colors = UxColors(
            textPrimary = Color(0xFF000000),
            textSecondary = Color(0xFF000000),
            textInverse = Color(0xFFFFFFFF),
            textLink = Color(0xFF072C66),
            textHeading = Color(0xFF000000),
            textLabel = Color(0xFF000000),
            textOnBrand = Color(0xFFFFFFFF),
            textOnAppBar = Color(0xFF072C66),
            surfaceDefault = Color(0xFFFFFFFF),
            surfaceSubtle = Color(0xFFFFFFFF),
            surfaceInverse = Color(0xFF000000),
            surfaceRaised = Color(0xFFFFFFFF),
            surfaceAppBar = Color(0xFFFFFFFF),
            surfacePage = Color(0xFFFFFFFF),
            surfaceCard = Color(0xFFFFFFFF),
            overlayScrim = Color(0xFF000000),
            borderDefault = Color(0xFF000000),
            borderInverse = Color(0xFFFFFFFF),
            actionPrimaryBg = Color(0xFF072C66),
            actionPrimaryFg = Color(0xFFFFFFFF),
            actionSecondaryBg = Color(0xFFFFFFFF),
            actionSecondaryFg = Color(0xFF072C66),
            actionFloatingBg = Color(0xFF072C66),
            actionFloatingFg = Color(0xFFFFFFFF),
            focusRing = Color(0xFF000000),
            feedbackInfo = UxFeedbackColors(Color(0xFFFFFFFF), Color(0xFF072C66), Color(0xFF000000), Color(0xFFFFFFFF)),
            feedbackSuccess = UxFeedbackColors(Color(0xFFFFFFFF), Color(0xFF033608), Color(0xFF000000), Color(0xFFFFFFFF)),
            feedbackWarning = UxFeedbackColors(Color(0xFFFFFFFF), Color(0xFF4E2A00), Color(0xFF000000), Color(0xFFFFFFFF)),
            feedbackError = UxFeedbackColors(Color(0xFFFFFFFF), Color(0xFF4E0711), Color(0xFF000000), Color(0xFFFFFFFF)),
        ),
        type = UxTypography(
            title = style(24, 35, FontWeight.Bold, heading),
            headline = style(28, 34, FontWeight.Bold, heading),
            section = style(18, 24, FontWeight.Bold, base),
            body = style(16, 24, FontWeight.Normal, base),
            small = style(14, 20, FontWeight.Normal, base),
            caption = style(12, 16, FontWeight.Normal, base),
            display = style(36, 45, FontWeight.Bold, base),
            appBar = style(18, 24, FontWeight.Bold, base),
            button = style(16, 24, FontWeight.Bold, base),
            status = style(14, 20, FontWeight.Bold, base),
        ),
        space = UxSpacing(xs = 5.dp, s = 10.dp, m = 15.dp, section = 30.dp, gutter = 20.dp),
        radius = UxRadii(control = 5.dp, card = 10.dp, sheet = 15.dp, pill = 99.dp, floating = 24.dp),
        size = UxSizes(target = 48.dp, iconMd = 24.dp, iconSm = 18.dp, focusRing = 3.dp, glyphHero = 72.dp),
        elevation = UxElevations(
            action = shadow(layer(0xFF000000, 0.0f, -2.0f, 0.0f, 0.0f)),
            bar = shadow(layer(0xFF000000, 0.0f, 0.5f, 0.0f, 0.5f)),
            card = shadow(layer(0xFF000000, 0.0f, 0.0f, 0.0f, 1.0f)),
            floating = shadow(layer(0xFF000000, 0.0f, 0.0f, 0.0f, 2.0f)),
            floatingAction = shadow(layer(0xFF000000, 0.0f, 0.0f, 0.0f, 2.0f)),
            menu = shadow(layer(0xFF000000, 0.0f, 0.0f, 0.0f, 2.0f)),
            raised = shadow(layer(0xFF000000, 0.0f, 0.0f, 0.0f, 2.0f)),
            sheet = UxShadow.None,
        ),
        font = UxFonts(base = base, heading = heading, regular = FontWeight.Normal, label = FontWeight.Normal, semibold = FontWeight.SemiBold, bold = FontWeight.Bold, value = FontWeight.SemiBold, status = FontWeight.Bold),
        effects = effects,
        comp = UxComponentTokens(
            appBar = UxAppBarTokens(64.dp, 8.dp, 9.dp, FontWeight.Bold, shadow(layer(0xFF000000, 0.0f, 0.5f, 0.0f, 0.5f))),
            button = UxButtonTokens(FontWeight.Bold, shadow(layer(0xFF000000, 0.0f, 0.0f, 0.0f, 2.0f)), shadow(layer(0xFF000000, 0.0f, 0.0f, 0.0f, 2.0f))),
            link = UxLinkTokens(5.dp, FontWeight.Bold),
            sheet = UxSheetTokens(UxShadow.None, Color(0xFF000000)),
            rpIdentity = UxRpIdentityTokens(FontWeight.Bold, FontWeight.Normal),
            attributeList = UxAttributeListTokens(FontWeight.SemiBold),
            scan = scan,
            qrViewfinder = qrViewfinder,
        ),
    )
}

/** PROVISIONAL `object EudiwV1Dark : UxTokenValues` (mode `dark`). */
object ProvisionalEudiwV1Dark : UxTokenValues {
    override val tokens = UxTokens(
        colors = UxColors(
            textPrimary = Color(0xFFE6E8EB),
            textSecondary = Color(0xFFB2B8C1),
            textInverse = Color(0xFFFFFFFF),
            textLink = Color(0xFF88B3F6),
            textHeading = Color(0xFFC3D9F9),
            textLabel = Color(0xFFB2B8C1),
            textOnBrand = Color(0xFFFFFFFF),
            textOnAppBar = Color(0xFFC3D9F9),
            surfaceDefault = Color(0xFF1A1E24),
            surfaceSubtle = Color(0xFF262B33),
            surfaceInverse = Color(0xFF000000),
            surfaceRaised = Color(0xFF1A1E24),
            surfaceAppBar = Color(0xFF1A1E24),
            surfacePage = Color(0xFF0F1216),
            surfaceCard = Color(0xFF1A1E24),
            overlayScrim = Color(0xFFB2B8C1),
            borderDefault = Color(0xFF3D444F),
            borderInverse = Color(0xFFFFFFFF),
            actionPrimaryBg = Color(0xFF88B3F6),
            actionPrimaryFg = Color(0xFF072C66),
            actionSecondaryBg = Color(0xFF1E3359),
            actionSecondaryFg = Color(0xFFC3D9F9),
            actionFloatingBg = Color(0xFF88B3F6),
            actionFloatingFg = Color(0xFF072C66),
            focusRing = Color(0xFFD96E00),
            feedbackInfo = UxFeedbackColors(Color(0xFF1E3359), Color(0xFF88B3F6), Color(0xFFC3D9F9), Color(0xFF88B3F6)),
            feedbackSuccess = UxFeedbackColors(Color(0xFF11301A), Color(0xFF5FC46B), Color(0xFF9FDAA5), Color(0xFF5FC46B)),
            feedbackWarning = UxFeedbackColors(Color(0xFF38290D), Color(0xFFE3A44A), Color(0xFFF0D3A8), Color(0xFFE3A44A)),
            feedbackError = UxFeedbackColors(Color(0xFF3E161C), Color(0xFFF27884), Color(0xFFF8B5B9), Color(0xFFF27884)),
        ),
        type = UxTypography(
            title = style(24, 35, FontWeight.Bold, heading),
            headline = style(28, 34, FontWeight.Bold, heading),
            section = style(18, 24, FontWeight.Bold, base),
            body = style(16, 24, FontWeight.Normal, base),
            small = style(14, 20, FontWeight.Normal, base),
            caption = style(12, 16, FontWeight.Normal, base),
            display = style(36, 45, FontWeight.Bold, base),
            appBar = style(18, 24, FontWeight.Bold, base),
            button = style(16, 24, FontWeight.Bold, base),
            status = style(13, 16, FontWeight.SemiBold, base),
        ),
        space = UxSpacing(xs = 5.dp, s = 10.dp, m = 15.dp, section = 30.dp, gutter = 20.dp),
        radius = UxRadii(control = 5.dp, card = 10.dp, sheet = 15.dp, pill = 99.dp, floating = 24.dp),
        size = UxSizes(target = 48.dp, iconMd = 24.dp, iconSm = 18.dp, focusRing = 3.dp, glyphHero = 72.dp),
        elevation = UxElevations(
            action = shadow(layer(0x2E000000, 0.0f, -8.0f, 24.0f, -12.0f)),
            bar = shadow(layer(0xFF2C323B, 0.0f, 0.5f, 0.0f, 0.5f)),
            card = shadow(layer(0xFF2C323B, 0.0f, 0.0f, 0.0f, 1.0f, inset = true)),
            floating = shadow(layer(0x80000000, 0.0f, 8.0f, 24.0f, 0.0f), layer(0xFF2C323B, 0.0f, 0.0f, 0.0f, 1.0f)),
            floatingAction = shadow(layer(0x99000000, 0.0f, 8.0f, 24.0f, 0.0f)),
            menu = shadow(layer(0x99000000, 0.0f, 12.0f, 32.0f, 0.0f), layer(0xFF2C323B, 0.0f, 0.0f, 0.0f, 1.0f)),
            raised = shadow(layer(0x331A1A1A, 0.0f, 4.0f, 8.0f, 0.0f)),
            sheet = shadow(layer(0x40000000, 0.0f, -10.0f, 40.0f, 0.0f)),
        ),
        font = UxFonts(base = base, heading = heading, regular = FontWeight.Normal, label = FontWeight.Normal, semibold = FontWeight.SemiBold, bold = FontWeight.Bold, value = FontWeight.SemiBold, status = FontWeight.SemiBold),
        effects = effects,
        comp = UxComponentTokens(
            appBar = UxAppBarTokens(64.dp, 8.dp, 9.dp, FontWeight.Bold, shadow(layer(0xFF2C323B, 0.0f, 0.5f, 0.0f, 0.5f))),
            button = UxButtonTokens(FontWeight.Bold, shadow(layer(0x80000000, 0.0f, 8.0f, 24.0f, 0.0f), layer(0xFF2C323B, 0.0f, 0.0f, 0.0f, 1.0f)), shadow(layer(0x99000000, 0.0f, 8.0f, 24.0f, 0.0f))),
            link = UxLinkTokens(5.dp, FontWeight.Bold),
            sheet = UxSheetTokens(shadow(layer(0x40000000, 0.0f, -10.0f, 40.0f, 0.0f)), Color(0xFFB2B8C1)),
            rpIdentity = UxRpIdentityTokens(FontWeight.Bold, FontWeight.Normal),
            attributeList = UxAttributeListTokens(FontWeight.SemiBold),
            scan = scan,
            qrViewfinder = qrViewfinder,
        ),
    )
}

/** PROVISIONAL `object EudiwV1DarkHighContrast : UxTokenValues` (mode `dark-high-contrast`). */
object ProvisionalEudiwV1DarkHighContrast : UxTokenValues {
    override val tokens = UxTokens(
        colors = UxColors(
            textPrimary = Color(0xFFFFFFFF),
            textSecondary = Color(0xFFFFFFFF),
            textInverse = Color(0xFFFFFFFF),
            textLink = Color(0xFFC3D9F9),
            textHeading = Color(0xFFFFFFFF),
            textLabel = Color(0xFFFFFFFF),
            textOnBrand = Color(0xFFFFFFFF),
            textOnAppBar = Color(0xFFFFFFFF),
            surfaceDefault = Color(0xFF000000),
            surfaceSubtle = Color(0xFF000000),
            surfaceInverse = Color(0xFF000000),
            surfaceRaised = Color(0xFF000000),
            surfaceAppBar = Color(0xFF000000),
            surfacePage = Color(0xFF000000),
            surfaceCard = Color(0xFF000000),
            overlayScrim = Color(0xFFB2B8C1),
            borderDefault = Color(0xFFFFFFFF),
            borderInverse = Color(0xFFFFFFFF),
            actionPrimaryBg = Color(0xFFC3D9F9),
            actionPrimaryFg = Color(0xFF000000),
            actionSecondaryBg = Color(0xFF000000),
            actionSecondaryFg = Color(0xFFFFFFFF),
            actionFloatingBg = Color(0xFFC3D9F9),
            actionFloatingFg = Color(0xFF000000),
            focusRing = Color(0xFFFFFFFF),
            feedbackInfo = UxFeedbackColors(Color(0xFF000000), Color(0xFFC3D9F9), Color(0xFFFFFFFF), Color(0xFF000000)),
            feedbackSuccess = UxFeedbackColors(Color(0xFF000000), Color(0xFF9FDAA5), Color(0xFFFFFFFF), Color(0xFF000000)),
            feedbackWarning = UxFeedbackColors(Color(0xFF000000), Color(0xFFF0D3A8), Color(0xFFFFFFFF), Color(0xFF000000)),
            feedbackError = UxFeedbackColors(Color(0xFF000000), Color(0xFFF8B5B9), Color(0xFFFFFFFF), Color(0xFF000000)),
        ),
        type = UxTypography(
            title = style(24, 35, FontWeight.Bold, heading),
            headline = style(28, 34, FontWeight.Bold, heading),
            section = style(18, 24, FontWeight.Bold, base),
            body = style(16, 24, FontWeight.Normal, base),
            small = style(14, 20, FontWeight.Normal, base),
            caption = style(12, 16, FontWeight.Normal, base),
            display = style(36, 45, FontWeight.Bold, base),
            appBar = style(18, 24, FontWeight.Bold, base),
            button = style(16, 24, FontWeight.Bold, base),
            status = style(14, 20, FontWeight.Bold, base),
        ),
        space = UxSpacing(xs = 5.dp, s = 10.dp, m = 15.dp, section = 30.dp, gutter = 20.dp),
        radius = UxRadii(control = 5.dp, card = 10.dp, sheet = 15.dp, pill = 99.dp, floating = 24.dp),
        size = UxSizes(target = 48.dp, iconMd = 24.dp, iconSm = 18.dp, focusRing = 3.dp, glyphHero = 72.dp),
        elevation = UxElevations(
            action = shadow(layer(0xFFFFFFFF, 0.0f, -2.0f, 0.0f, 0.0f)),
            bar = shadow(layer(0xFFFFFFFF, 0.0f, 0.5f, 0.0f, 0.5f)),
            card = shadow(layer(0xFFFFFFFF, 0.0f, 0.0f, 0.0f, 1.0f)),
            floating = shadow(layer(0xFFFFFFFF, 0.0f, 0.0f, 0.0f, 2.0f)),
            floatingAction = shadow(layer(0xFFFFFFFF, 0.0f, 0.0f, 0.0f, 2.0f)),
            menu = shadow(layer(0xFFFFFFFF, 0.0f, 0.0f, 0.0f, 2.0f)),
            raised = shadow(layer(0xFFFFFFFF, 0.0f, 0.0f, 0.0f, 2.0f)),
            sheet = UxShadow.None,
        ),
        font = UxFonts(base = base, heading = heading, regular = FontWeight.Normal, label = FontWeight.Normal, semibold = FontWeight.SemiBold, bold = FontWeight.Bold, value = FontWeight.SemiBold, status = FontWeight.Bold),
        effects = effects,
        comp = UxComponentTokens(
            appBar = UxAppBarTokens(64.dp, 8.dp, 9.dp, FontWeight.Bold, shadow(layer(0xFFFFFFFF, 0.0f, 0.5f, 0.0f, 0.5f))),
            button = UxButtonTokens(FontWeight.Bold, shadow(layer(0xFFFFFFFF, 0.0f, 0.0f, 0.0f, 2.0f)), shadow(layer(0xFFFFFFFF, 0.0f, 0.0f, 0.0f, 2.0f))),
            link = UxLinkTokens(5.dp, FontWeight.Bold),
            sheet = UxSheetTokens(UxShadow.None, Color(0xFFB2B8C1)),
            rpIdentity = UxRpIdentityTokens(FontWeight.Bold, FontWeight.Normal),
            attributeList = UxAttributeListTokens(FontWeight.SemiBold),
            scan = scan,
            qrViewfinder = qrViewfinder,
        ),
    )
}


/** PROVISIONAL `UxBrandConfig` for eudiw_v1: `EL-attribute-list {pairs: columns}`, `EL-attribute-item {checkbox: outline}`. */
val ProvisionalEudiwV1 = UxBrand(
    id = "eudiw_v1",
    light = ProvisionalEudiwV1Light,
    highContrast = ProvisionalEudiwV1HighContrast,
    dark = ProvisionalEudiwV1Dark,
    darkHighContrast = ProvisionalEudiwV1DarkHighContrast,
)
