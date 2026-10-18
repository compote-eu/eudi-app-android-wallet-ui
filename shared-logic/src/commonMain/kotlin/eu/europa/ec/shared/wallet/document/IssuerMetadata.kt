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

package eu.europa.ec.shared.wallet.document

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Issuer-published display metadata for a stored document — a KMP port of
 * `eu.europa.ec.eudi.wallet.document.metadata.IssuerMetadata`, for platforms that read multipaz's
 * `DocumentStore` directly instead of going through the Android-only wallet document manager.
 *
 * **The JSON shape is deliberately identical to the original**, because the document manager
 * persists this class as a JSON string inside its CBOR document metadata. Two field types are
 * platform-neutral here where the original used JVM types — exactly the substitutions already made
 * for `WalletDocument.issuerLogoUri`:
 *  - `java.util.Locale` -> a BCP-47 language tag [String] (the original's serializer wrote
 *    `Locale.toLanguageTag()`, so the wire form is unchanged).
 *  - `java.net.URI` -> [String] (likewise `URI.toString()`).
 *
 * Unknown keys are ignored, so metadata written by a newer issuer still parses.
 */
@Serializable
internal data class IssuerMetadata(
    val documentConfigurationIdentifier: String,
    val display: List<Display> = emptyList(),
    val claims: List<Claim>? = null,
    val credentialIssuerIdentifier: String,
    val issuerDisplay: List<IssuerDisplay>? = null,
) {

    fun toJson(): String = JsonFormat.encodeToString(serializer(), this)

    /** Display properties of the document itself, for one language. */
    @Serializable
    data class Display(
        val name: String,
        val locale: String? = null,
        val logo: Logo? = null,
        val description: String? = null,
        val backgroundColor: String? = null,
        val textColor: String? = null,
        val backgroundImageUri: String? = null,
    )

    /** Display properties of the issuer that issued the document, for one language. */
    @Serializable
    data class IssuerDisplay(
        val name: String,
        val locale: String? = null,
        val logo: Logo? = null,
    )

    @Serializable
    data class Logo(
        val uri: String? = null,
        val alternativeText: String? = null,
    )

    /**
     * Per-claim metadata. [path] is `[namespace, elementIdentifier]` for mdoc and the claim path for
     * SD-JWT VC — the same convention the original uses.
     */
    @Serializable
    data class Claim(
        @SerialName("path") val path: List<String>,
        @SerialName("mandatory") val mandatory: Boolean? = false,
        @SerialName("display") val display: List<Display> = emptyList(),
    ) {

        @Serializable
        data class Display(
            @SerialName("name") val name: String? = null,
            @SerialName("locale") val locale: String? = null,
        )

        /**
         * This claim's name in [locale], or the closest thing to it.
         *
         * Falls back in the order a reader would want: the exact locale, then its language (`en` for
         * `en-GB`), then an entry with no locale at all, then the first one published. Null only when
         * the issuer named the claim in no language, which leaves the caller free to show the
         * identifier.
         */
        fun displayNameFor(locale: String): String? {
            val language = locale.substringBefore('-').substringBefore('_')
            return display.firstOrNull { it.locale == locale }?.name
                ?: display.firstOrNull {
                    it.locale?.substringBefore('-')?.substringBefore('_') == language
                }?.name
                ?: display.firstOrNull { it.locale == null }?.name
                ?: display.firstOrNull { it.name != null }?.name
        }
    }

    companion object {

        private val JsonFormat = Json { ignoreUnknownKeys = true }

        /** Parses [json]; a `Result` rather than a throw, mirroring the original's contract. */
        fun fromJson(json: String): Result<IssuerMetadata> =
            runCatching { JsonFormat.decodeFromString(serializer(), json) }
    }
}

/**
 * Picks the entry matching [locale], falling back to the first entry, then to null.
 *
 * Matching is on the **language subtag only** (`en-GB` matches `en`), which is what the Android side
 * does (`Locale.compareLocaleLanguage` compares `Locale.language`), so both platforms resolve the
 * same display for the same wallet. Comparison is case-insensitive because a BCP-47 tag's language
 * subtag is case-insensitive, and an entry with no locale never matches — but can still be the
 * first-entry fallback, as on Android.
 */
internal fun <T> List<T>?.localizedOrFirst(
    locale: String,
    localeOf: (T) -> String?,
): T? {
    if (isNullOrEmpty()) return null
    val language = locale.languageSubtag()
    return firstOrNull { language != null && localeOf(it)?.languageSubtag() == language }
        ?: first()
}

/**
 * The language subtag of a BCP-47 tag: everything before the first `-` or `_`, lower-cased. Null for
 * a blank tag, so a document with `locale: ""` cannot accidentally match the user's locale.
 */
private fun String?.languageSubtag(): String? =
    this?.substringBefore('-')?.substringBefore('_')?.lowercase()?.takeIf { it.isNotEmpty() }
