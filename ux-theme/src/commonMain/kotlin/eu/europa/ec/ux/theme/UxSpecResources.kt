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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** `ux-assets.json` `paint`: a mask is tinted with the content colour, an image is drawn as is. */
enum class UxAssetPaint { Mask, Image }

/** One bound asset of a brand (`ux-assets.json`): the drawable resource name the app looks up. */
data class UxAsset(val id: String, val resource: String, val format: String, val paint: UxAssetPaint)

/**
 * The export's asset table (`ux-spec/resources/files/ux-assets.json`, V32): per brand, asset id to drawable resource.
 * An id the brand does not bind is absent (the element draws its fallback). A `.svg` drawable is absent where the
 * platform cannot load it ([svgDrawablesSupported]: Compose Multiplatform loads SVG on iOS, not on Android).
 */
class UxAssets(private val byBrand: Map<String, Map<String, UxAsset>>) {
    fun asset(brand: String, id: String): UxAsset? =
        byBrand[brand]?.get(id)?.takeIf { it.format != SVG || svgDrawablesSupported }

    fun ids(brand: String): Set<String> = byBrand[brand]?.keys.orEmpty()

    companion object {
        val NONE = UxAssets(emptyMap())
        private const val SVG = "svg"

        fun parse(json: String): UxAssets {
            val brands = Json.parseToJsonElement(json).obj("brands")
            return UxAssets(
                brands.mapValues { (_, assets) ->
                    (assets as JsonObject).mapValues { (id, a) ->
                        val o = a as JsonObject
                        UxAsset(
                            id = id,
                            resource = o.str("resource"),
                            format = o.str("format"),
                            paint = if (o.str("paint") == "mask") UxAssetPaint.Mask else UxAssetPaint.Image,
                        )
                    }
                },
            )
        }
    }
}

/**
 * The library's asset rules per element part (`ux-library.json` element `assets`, DEC-080): a fixed `id`, a `map` from
 * a prop value to an id, or a `kind` whose id is `<kind>.<value>`. Elements ask by element id, part name and prop
 * value, so which icon a part draws is library data, not Kotlin.
 */
class UxLibraryAssets(private val parts: Map<String, Map<String, JsonObject>>) {
    /** The asset id [element]'s [part] draws for prop value [value] (null for a fixed part), or null when none. */
    fun assetId(element: String, part: String, value: String? = null): String? {
        val rule = parts[element]?.get(part) ?: return null
        (rule["id"] as? JsonPrimitive)?.let { return it.content }
        (rule["map"] as? JsonObject)?.let { map -> return (map[value.orEmpty()] as? JsonPrimitive)?.takeIf { it.isString }?.content }
        val kind = (rule["kind"] as? JsonPrimitive)?.content ?: return null
        return value?.takeIf { it.isNotEmpty() }?.let { if (it.startsWith("$kind.")) it else "$kind.$it" }
    }

    companion object {
        val NONE = UxLibraryAssets(emptyMap())

        fun parse(json: String): UxLibraryAssets = UxLibraryAssets(
            Json.parseToJsonElement(json).obj("elements").mapValues { (_, element) ->
                ((element as JsonObject)["assets"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                    .associateBy { it.str("part") }
            },
        )
    }
}

/**
 * The export's `comp.*` values (`ux-tokens.json`, V32) for parts the hand-written shell has no field for, per brand and
 * mode, by exact token key. A mode the export does not bind falls back as the resolver says (dark to light,
 * dark-high-contrast to high-contrast), like [UxBrand.tokens].
 */
class UxComponentTokenTable(private val values: Map<String, Map<String, JsonObject>>) {
    /** A `dimension` token in dp (DTCG px), or null when the brand, mode or key has none. */
    fun dp(brand: String, mode: UxMode, key: String): Dp? = pxValue(brand, mode, key)?.dp

    /** A `dimension` token as a font size or line height (the export reads it as sp where its alias chain passes `sys.type.*`). */
    fun sp(brand: String, mode: UxMode, key: String): TextUnit? = pxValue(brand, mode, key)?.sp

    /** A `color` token (sRGB components and the optional alpha), or null. */
    fun color(brand: String, mode: UxMode, key: String): Color? =
        (token(brand, mode, key)?.takeIf { it.str("type") == "color" }?.get("value") as? JsonObject)?.let(::srgb)

    /** A `fontWeight` token, or null. */
    fun weight(brand: String, mode: UxMode, key: String): FontWeight? =
        (token(brand, mode, key)?.takeIf { it.str("type") == "fontWeight" }?.get("value") as? JsonPrimitive)?.intOrNull?.let(::FontWeight)

    /** A `shadow` token as its layers, bottom first (null when absent). */
    fun shadow(brand: String, mode: UxMode, key: String): UxShadow? {
        val layers = token(brand, mode, key)?.takeIf { it.str("type") == "shadow" }?.get("value") as? JsonArray ?: return null
        return UxShadow(
            layers.filterIsInstance<JsonObject>().map { l ->
                val rgb = (l["color"] as? JsonObject)?.let(::srgb) ?: Color.Transparent
                val inset = (l["inset"] as? JsonPrimitive)?.content == "true"
                UxShadowLayer(rgb, l.px("offsetX").dp, l.px("offsetY").dp, l.px("blur").dp, l.px("spread").dp, inset)
            },
        )
    }

    private fun srgb(o: JsonObject): Color? {
        val c = (o["components"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull?.toFloat() } ?: return null
        return if (c.size == RGB) Color(c[0], c[1], c[2], (o["alpha"] as? JsonPrimitive)?.doubleOrNull?.toFloat() ?: 1f) else null
    }

    private fun JsonObject.px(key: String): Float = ((this[key] as? JsonObject)?.get("value") as? JsonPrimitive)?.doubleOrNull?.toFloat() ?: 0f

    private fun pxValue(brand: String, mode: UxMode, key: String): Float? {
        val value = token(brand, mode, key)?.takeIf { it.str("type") == "dimension" }?.get("value") as? JsonObject ?: return null
        return (value["value"] as? JsonPrimitive)?.doubleOrNull?.takeIf { value.str("unit") == "px" }?.toFloat()
    }

    private fun token(brand: String, mode: UxMode, key: String): JsonObject? {
        val byMode = values[brand] ?: return null
        val wire = when (mode) {
            UxMode.Light -> listOf(LIGHT)
            UxMode.HighContrast -> listOf(HIGH_CONTRAST)
            UxMode.Dark -> listOf("dark", LIGHT)
            UxMode.DarkHighContrast -> listOf("dark-high-contrast", HIGH_CONTRAST)
        }
        return wire.firstNotNullOfOrNull { byMode[it] }?.get(key) as? JsonObject
    }

    companion object {
        val NONE = UxComponentTokenTable(emptyMap())
        private const val LIGHT = "light"
        private const val HIGH_CONTRAST = "high-contrast"
        private const val RGB = 3

        fun parse(json: String): UxComponentTokenTable = UxComponentTokenTable(
            Json.parseToJsonElement(json).obj("values").mapValues { (_, modes) ->
                (modes as JsonObject).mapValues { (_, tokens) -> tokens as JsonObject }
            },
        )
    }
}

/**
 * The library's brand selections (`ux-library.json` `brand_selections`: part style and variant axis per brand), e.g.
 * `EL-status-chip.mark` is `dot` in eudiw_v1 and `icon` in idsk. Elements ask by the library's own key; a brand or key
 * the export lacks answers null and the element draws the library's default.
 */
class UxBrandSelections(private val byBrand: Map<String, Map<String, String>>) {
    fun of(brand: String, key: String): String? = byBrand[brand]?.get(key)

    companion object {
        val NONE = UxBrandSelections(emptyMap())

        fun parse(json: String): UxBrandSelections =
            UxBrandSelections(
                Json.parseToJsonElement(json).obj("brand_selections").mapValues { (_, keys) ->
                    (keys as JsonObject).mapValues { (_, v) -> (v as JsonPrimitive).content }
                },
            )
    }
}

/**
 * The export's data files the elements read beside the generated theme: assets, the library's part-to-asset rules and
 * the `comp.*` table, plus how a drawable resource name becomes a painter (the host owns the `Res` that holds them).
 * [NONE] draws no asset and reads no `comp.*` value, so every element falls back to the shell.
 */
class UxSpecResources(
    val assets: UxAssets = UxAssets.NONE,
    val library: UxLibraryAssets = UxLibraryAssets.NONE,
    val componentTokens: UxComponentTokenTable = UxComponentTokenTable.NONE,
    val selections: UxBrandSelections = UxBrandSelections.NONE,
    val painter: @Composable (resource: String) -> Painter? = { null },
) {
    companion object {
        val NONE = UxSpecResources()
    }
}

/** The active export resources; [UxTheme] provides them. */
val LocalUxSpecResources = staticCompositionLocalOf { UxSpecResources.NONE }

/** The active mode; [UxTheme] provides it. */
val LocalUxMode = staticCompositionLocalOf { UxMode.Light }

/** False where Compose Multiplatform cannot load an SVG drawable (Android). */
internal expect val svgDrawablesSupported: Boolean

private fun JsonElement.obj(key: String): JsonObject = (this as JsonObject)[key] as JsonObject

private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.content.orEmpty()
