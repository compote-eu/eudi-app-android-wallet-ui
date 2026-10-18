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

/** `TPL-sheet layouts: decision-steps` (boundary §1.9): the brand picks the option, the template draws it. */
enum class UxDecisionSteps { Sheet, Page }

/** `EL-attribute-list styles: pairs`. */
enum class UxAttributePairs { Columns, Stacked }

/** `EL-attribute-item styles: checkbox`. */
enum class UxCheckboxStyle { Outline, Filled }

/** The brand's `layouts:` selections. */
data class UxLayouts(val decisionSteps: UxDecisionSteps = UxDecisionSteps.Sheet)

/** The brand's `styles:` selections. */
data class UxStyles(
    val attributePairs: UxAttributePairs = UxAttributePairs.Columns,
    val checkbox: UxCheckboxStyle = UxCheckboxStyle.Outline,
)

/**
 * A brand as the runtime receives it: id, the token values per mode, and the `styles` and `layouts` selections
 * (generated per brand in `UxBrandConfig`, `ux-spec/theme`). [light] and [highContrast] are
 * required; an unbound optional mode falls back as the resolver says (`dark` to light, `dark-high-contrast` to
 * high-contrast).
 */
data class UxBrand(
    val id: String,
    val light: UxTokenValues,
    val highContrast: UxTokenValues,
    val dark: UxTokenValues? = null,
    val darkHighContrast: UxTokenValues? = null,
    val styles: UxStyles = UxStyles(),
    val layouts: UxLayouts = UxLayouts(),
) {
    fun tokens(mode: UxMode): UxTokens = when (mode) {
        UxMode.Light -> light.tokens
        UxMode.HighContrast -> highContrast.tokens
        UxMode.Dark -> (dark ?: light).tokens
        UxMode.DarkHighContrast -> (darkHighContrast ?: highContrast).tokens
    }
}
