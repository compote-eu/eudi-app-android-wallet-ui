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

package eu.europa.ec.shared.resources

import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/**
 * Phase 3a: synchronous access to the shared string corpus, for the layers that cannot use
 * [UiText].
 *
 * View-models do **not** use this — they hold [UiText] and let the composable resolve. This exists
 * for interactors, transformers and controllers, where a resolved `String` is genuinely required:
 * `FilterItem.name` in `:business-logic` is sorted, deduplicated and free-text searched, and 67 of
 * the ~100 interactor entry points are plain (non-suspend) functions — `getFilters()` alone
 * resolves ~20 strings in one synchronous call.
 *
 * Deliberately string-only. Plurals are **not** exposed synchronously: choosing a plural form
 * requires the locale's CLDR categories, and pre-caching forms would silently bake in English's
 * one/other split. The single plural call site resolves through the suspend path instead.
 *
 * [warm] must complete before the first [get]. It is awaited during Koin startup; the first real
 * resolution happens when a feature screen builds its filters, long afterwards.
 */
interface StringCatalog {

    /** Resolves [resource]. Requires [warm] to have completed. */
    operator fun get(resource: StringResource): String

    /** Resolves [resource] and substitutes positional arguments (`%1$s`, `%2$d`, …). */
    fun get(resource: StringResource, vararg args: Any): String

    /** Loads the whole corpus into memory. Idempotent. */
    suspend fun warm()
}

/**
 * [StringCatalog] backed by compose-resources.
 *
 * Warming reads `Res.allStringResources` once. compose-resources caches the underlying resource
 * file, so this is a single file read plus one offset lookup per key, not N file reads.
 */
class ComposeResourcesStringCatalog(
    private val onMiss: (String) -> Unit = {},
) : StringCatalog {

    private var cache: Map<String, String> = emptyMap()

    override suspend fun warm() {
        if (cache.isNotEmpty()) return
        cache = Res.allStringResources.mapValues { (_, resource) -> getString(resource) }
    }

    override fun get(resource: StringResource): String =
        cache[resource.key] ?: miss(resource)

    override fun get(resource: StringResource, vararg args: Any): String =
        formatPositional(get(resource), args)

    /**
     * Returns the resource key rather than an empty string. The key is recognisable in a
     * screenshot or bug report, whereas the `ResourceProvider` this replaces silently returned
     * `""` on any failure — a missing string looked identical to an intentionally blank one.
     */
    private fun miss(resource: StringResource): String {
        onMiss(resource.key)
        return resource.key
    }
}

private val SPECIFIER = Regex("""%(?:(\d+)\$([sd])|%)""")

/**
 * Substitutes positional specifiers into [template].
 *
 * Only `%N$s`, `%N$d` and the `%%` escape need handling: the Phase 3a corpus move numbered every
 * bare `%s`/`%d` positionally, so this sync path and compose-resources' own formatter cannot
 * disagree about argument order. A specifier with no corresponding argument is left verbatim
 * rather than substituted with a placeholder, so the defect is visible instead of silent.
 */
internal fun formatPositional(template: String, args: Array<out Any>): String =
    SPECIFIER.replace(template) { match ->
        val index = match.groupValues[1]
        if (index.isEmpty()) return@replace "%"
        args.getOrNull(index.toInt() - 1)?.toString() ?: match.value
    }
