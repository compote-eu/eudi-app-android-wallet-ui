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

import androidx.compose.runtime.Composable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Phase 3a: display text held by view-model state as *intent* rather than as resolved characters.
 *
 * This is what lets a view-model move to `commonMain` without depending on any resolver: state
 * declares which string it wants, and the composable resolves it at render time via
 * compose-resources — on Android and iOS alike. It also removes the sync-vs-suspend problem at
 * the view-model layer entirely, because nothing is resolved in `setInitialState()`.
 *
 * It is `@Serializable`, which extends the same idea to Nav3: a route payload carries the resource
 * *key* and the destination resolves it (see [StringResourceSerializer]). Before that, a
 * view-model whose only remaining resolver use was filling a config it was about to navigate with
 * — `ContentHeaderConfig`, `BiometricUiConfig`, `SuccessUIConfig` — could not be freed of the
 * resolver at all.
 *
 * The seam still stops short of the domain layer on purpose. Interactors and `:business-logic`
 * keep eagerly-resolved `String`s (see [StringCatalog]) because values like `FilterItem.name` are
 * sorted, deduplicated and text-searched as data — operations a [StringResource] cannot support.
 *
 * Tests assert on the [UiText] itself, which is a structural comparison against the resource key
 * and arguments, rather than on resolved characters:
 * ```
 * assertEquals(UiText.Resource(Res.string.home_screen_welcome_user_message, "Martin"), state.title)
 * ```
 */
@Serializable
sealed interface UiText {

    /** Text computed at runtime — wallet-core values, issuer names, exception messages. */
    @Serializable
    @SerialName("Raw")
    data class Raw(val value: String) : UiText

    /**
     * A shared string resource, optionally formatted.
     *
     * [args] is a `List` rather than a `vararg` property deliberately: a `vararg val` would be
     * typed `Array<out Any>`, and a data class compares arrays by *identity*. That would make
     * `Resource(k, "a") != Resource(k, "a")`, breaking both the test story above and
     * `MutableStateFlow`'s equality-based conflation — every state update carrying a formatted
     * string would emit spuriously and churn recomposition.
     *
     * It is a `List<String>`, not `List<Any>`, because compose-resources stringifies every format
     * argument before substituting it (`formatArgs.map { it.toString() }`), as does
     * [formatPositional]. Nothing downstream can observe the difference, and narrowing the type is
     * what makes the class serializable. Numbers are still accepted at the call site: the
     * companion's `invoke` converts them.
     *
     * The `vararg` ergonomics are preserved by that `invoke`, which Kotlin selects only when no
     * constructor applies. Note the corollary: `Resource(k, someList)` binds to the *constructor*
     * and treats `someList` as the whole argument list, not as one argument.
     */
    @Serializable
    @SerialName("Resource")
    data class Resource(
        @Serializable(with = StringResourceSerializer::class)
        val res: StringResource,
        val args: List<String> = emptyList(),
    ) : UiText {
        companion object {
            operator fun invoke(res: StringResource, vararg args: Any): Resource =
                Resource(res, args.map { it.toString() })
        }
    }

    /**
     * A quantity-dependent string. Pluralisation rules are applied by compose-resources using the
     * active locale's CLDR categories, so this stays correct for locales with more than the
     * English one/other split.
     */
    @Serializable
    @SerialName("Plural")
    data class Plural(
        @Serializable(with = PluralStringResourceSerializer::class)
        val res: PluralStringResource,
        val quantity: Int,
        val args: List<String> = emptyList(),
    ) : UiText {
        companion object {
            operator fun invoke(res: PluralStringResource, quantity: Int, vararg args: Any): Plural =
                Plural(res, quantity, args.map { it.toString() })
        }
    }

    companion object {
        val Empty: UiText = Raw("")
    }
}

/** Resolves this [UiText] against the active locale. Android and iOS share this implementation. */
@Composable
fun UiText.resolve(): String = when (this) {
    is UiText.Raw -> value
    is UiText.Resource -> stringResource(res, *args.toTypedArray())
    is UiText.Plural -> pluralStringResource(res, quantity, *args.toTypedArray())
}

/** Convenience for the common `String` -> [UiText] lift at interactor/UI boundaries. */
fun String.asUiText(): UiText = UiText.Raw(this)

/**
 * Lifts optional runtime text, falling back to [fallback] when it is absent or blank.
 *
 * The [UiText] counterpart of `String?.ifEmptyOrNull`, and it exists for one recurring shape: a
 * relying party's name arrives from the request and is frequently missing, in which case the copy
 * falls back to a resource. Blank counts as absent, matching the `String` version this replaces.
 */
fun String?.asUiTextOr(fallback: UiText): UiText =
    if (isNullOrBlank()) fallback else UiText.Raw(this)
