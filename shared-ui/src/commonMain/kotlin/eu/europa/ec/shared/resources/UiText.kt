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
 * The seam stops here on purpose. Interactors and `:business-logic` keep eagerly-resolved
 * `String`s (see [StringCatalog]) because values like `FilterItem.name` are sorted, deduplicated
 * and text-searched as data — operations a [StringResource] cannot support.
 *
 * Tests assert on the [UiText] itself, which is a structural comparison against the resource key
 * and arguments, rather than on resolved characters:
 * ```
 * assertEquals(UiText.Resource(Res.string.home_screen_welcome_user_message, "Martin"), state.title)
 * ```
 */
sealed interface UiText {

    /** Text computed at runtime — wallet-core values, issuer names, exception messages. */
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
     * The `vararg` ergonomics are preserved by the companion's `invoke`, which Kotlin selects only
     * when no constructor applies. Note the corollary: `Resource(k, someList)` binds to the
     * *constructor* and treats `someList` as the whole argument list, not as one argument.
     */
    data class Resource(
        val res: StringResource,
        val args: List<Any> = emptyList(),
    ) : UiText {
        companion object {
            operator fun invoke(res: StringResource, vararg args: Any): Resource =
                Resource(res, args.toList())
        }
    }

    /**
     * A quantity-dependent string. Pluralisation rules are applied by compose-resources using the
     * active locale's CLDR categories, so this stays correct for locales with more than the
     * English one/other split.
     */
    data class Plural(
        val res: PluralStringResource,
        val quantity: Int,
        val args: List<Any> = emptyList(),
    ) : UiText {
        companion object {
            operator fun invoke(res: PluralStringResource, quantity: Int, vararg args: Any): Plural =
                Plural(res, quantity, args.toList())
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
