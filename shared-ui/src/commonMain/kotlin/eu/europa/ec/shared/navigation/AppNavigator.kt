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

package eu.europa.ec.shared.navigation

import androidx.navigation3.runtime.NavKey
import kotlin.reflect.KClass

/**
 * Phase-3c / N3: the KMP navigation command layer over the Nav3 back stack.
 *
 * Wraps the back stack (a plain `MutableList<NavKey>` — in the Compose host this is the
 * `NavBackStack` from `rememberNavBackStack()`, whose backing `SnapshotStateList` makes mutations
 * recompose `NavDisplay`; in tests a plain list) and exposes the operations the app performs,
 * mapping each former navigation-compose pattern to an explicit list op so behavior is preserved
 * 1:1:
 *
 *  - `navigate(X)`                          -> [navigate]
 *  - `navigate(X){ popUpTo(Y){inclusive} }` -> [navigate] with popUpTo args
 *  - `popBackStack()` (Pop)                 -> [pop]
 *  - `popBackStack(route, inclusive)`       -> [popUpTo]
 *  - `navigate(X){ popUpTo(current){inclusive=true} }` -> [replaceCurrent]
 *  - Splash replacing itself, leaving a stack of one -> [replaceAll]
 *
 * ## Pop targets are matched by destination, not by value
 *
 * [popUpTo] (and [navigate]'s `popUpTo` argument) match on the target's **class**, not on `==`.
 * That is the faithful translation of what navigation-compose did: `popUpTo(Screen.X.screenRoute)`
 * named a route *pattern*, so arguments never took part in the match. It is also what makes the
 * existing call sites correct — config-carrying pop targets such as `AddDocumentRoute(config)` are
 * rebuilt at the call site rather than read off the back stack, so a value comparison would
 * silently fail to find the real entry and the pop would become a no-op. Every [AppRoute] variant
 * is its own class and no destination is legitimately stacked twice, so matching by class
 * identifies exactly one entry.
 */
class AppNavigator(private val backStack: MutableList<NavKey>) {

    val entries: List<NavKey> get() = backStack
    val current: NavKey? get() = backStack.lastOrNull()

    /** Push [route], optionally popping up to [popUpTo] first (mirrors navigate{ popUpTo }). */
    fun navigate(
        route: NavKey,
        popUpTo: NavKey? = null,
        popUpToInclusive: Boolean = false,
    ) {
        if (popUpTo != null) popUpTo(target = popUpTo, inclusive = popUpToInclusive)
        backStack.add(route)
    }

    /** Pop the top entry. No-op (returns false) when only the root remains. */
    fun pop(): Boolean =
        if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
            true
        } else {
            false
        }

    /**
     * Pop entries down to the last entry that is the same destination as [target] (also removing it
     * when [inclusive]). See the class KDoc for why the match is by destination class.
     */
    fun popUpTo(target: NavKey, inclusive: Boolean = false): Boolean =
        popUpTo(destination = target::class, inclusive = inclusive)

    /** Pop entries down to the last entry of type [destination], also removing it when [inclusive]. */
    fun popUpTo(destination: KClass<out NavKey>, inclusive: Boolean = false): Boolean {
        val index = backStack.indexOfLast { it::class == destination }
        if (index < 0) return false
        val keep = if (inclusive) index else index + 1
        while (backStack.size > keep) backStack.removeAt(backStack.lastIndex)
        return true
    }

    /**
     * Replace the entry currently displayed with [route] — the "this screen is consumed by the
     * navigation" pattern (Splash, QrScan, QuickPin, Biometric, AddDocument, DocumentOffer,
     * DocumentOfferCode, ProximityQR).
     */
    fun replaceCurrent(route: NavKey) {
        if (backStack.isNotEmpty()) backStack.removeAt(backStack.lastIndex)
        backStack.add(route)
    }

    /** Reset the whole back stack to a single [route] — the "start anew" module switch. */
    fun replaceAll(route: NavKey) {
        backStack.clear()
        backStack.add(route)
    }

    /** Whether a [destination] is displayed or waiting underneath on the back stack. */
    fun isOnBackStack(destination: KClass<out NavKey>): Boolean =
        backStack.any { it::class == destination }
}
