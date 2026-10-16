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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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

/** `sys.font.family.*` (the webfont is not bundled: the platform family stands in) and `sys.font.weight.*`. */
data class UxFonts(
    val base: FontFamily,
    val heading: FontFamily,
    val regular: FontWeight,
    val label: FontWeight,
    val semibold: FontWeight,
    val bold: FontWeight,
    val value: FontWeight,
    val status: FontWeight,
)

/** One layer of a `sys.elevation.*` shadow (DTCG shadow: colour, offsets, blur, spread, inset). */
data class UxShadowLayer(
    val color: Color,
    val offsetX: Dp,
    val offsetY: Dp,
    val blur: Dp,
    val spread: Dp,
    val inset: Boolean,
) {
    /** Compose has no CSS blur: the platform shadow of this elevation approximates a blurred layer. */
    val approximateElevation: Dp get() = blur / BLUR_PER_ELEVATION

    private companion object {
        const val BLUR_PER_ELEVATION = 2
    }
}

/** A `sys.elevation.*` token: its layers, bottom first. */
data class UxShadow(val layers: List<UxShadowLayer>) {
    companion object {
        val None = UxShadow(emptyList())
    }
}

/** `sys.elevation.*`. */
data class UxElevations(
    val action: UxShadow,
    val bar: UxShadow,
    val card: UxShadow,
    val floating: UxShadow,
    val floatingAction: UxShadow,
    val menu: UxShadow,
    val raised: UxShadow,
    val sheet: UxShadow,
)

/**
 * NOT IN SPEC (no `sys.*` token exists yet): opacities and the hairline width the elements used to hard-code. Named
 * here so no element holds a literal; the exporter must either add `sys.opacity.*` / `sys.size.hairline` to the spec
 * or carry them as brand constants (SPIKE-LOG M5c, `spike-evidence/M5c/exporter-requirements.md`).
 */
data class UxEffects(val disabledAlpha: Float, val scrimAlpha: Float, val dimmedAlpha: Float, val hairline: Dp)

/** `comp.app-bar.*`. */
data class UxAppBarTokens(val minHeight: Dp, val badgeSize: Dp, val badgeOffset: Dp, val weight: FontWeight, val shadow: UxShadow)

/** `comp.button.*` (the floating variants' shadows and the label weight). */
data class UxButtonTokens(val weight: FontWeight, val iconShadow: UxShadow, val floatingShadow: UxShadow)

/** `comp.link.*`. */
data class UxLinkTokens(val gap: Dp, val weight: FontWeight)

/** `comp.sheet.*` (the scrim colour; its opacity is [UxEffects.scrimAlpha]). */
data class UxSheetTokens(val shadow: UxShadow, val scrim: Color)

/** `comp.rp-identity.*`. */
data class UxRpIdentityTokens(val nameWeight: FontWeight, val nameUnverifiedWeight: FontWeight)

/** `comp.attribute-list.*`. */
data class UxAttributeListTokens(val valueWeight: FontWeight)

/** NOT IN SPEC (`comp.scan.*` does not exist): the minimum height of the camera region of TPL-scan. */
data class UxScanTokens(val cameraMinHeight: Dp)

/** NOT IN SPEC (`comp.qr-viewfinder.*` does not exist): the corner marks of EL-qr-viewfinder. */
data class UxQrViewfinderTokens(val strokeWidth: Dp, val armRatio: Float)

/** `comp.*` for the elements in scope (the nine elements and four templates): what they read beyond `sys.*`. */
data class UxComponentTokens(
    val appBar: UxAppBarTokens,
    val button: UxButtonTokens,
    val link: UxLinkTokens,
    val sheet: UxSheetTokens,
    val rpIdentity: UxRpIdentityTokens,
    val attributeList: UxAttributeListTokens,
    val scan: UxScanTokens,
    val qrViewfinder: UxQrViewfinderTokens,
)

/** One resolved token set (one brand, one mode). */
data class UxTokens(
    val colors: UxColors,
    val type: UxTypography,
    val space: UxSpacing,
    val radius: UxRadii,
    val size: UxSizes,
    val elevation: UxElevations,
    val font: UxFonts,
    val effects: UxEffects,
    val comp: UxComponentTokens,
)

/** What a generated brand object implements (`object EudiwV1Light : UxTokenValues`). */
interface UxTokenValues {
    val tokens: UxTokens
}

/**
 * The scheme x contrast cell the user chose (resolver modifier `mode`, `facets`): `light` and `high-contrast` are
 * required of every brand, `dark` and `dark-high-contrast` are optional (fallbacks `light` and `high-contrast`).
 * [wireEnum]-compatible with the bundle's `theme.default_mode` (`high-contrast` -> [HighContrast]).
 */
enum class UxMode { Light, HighContrast, Dark, DarkHighContrast }
