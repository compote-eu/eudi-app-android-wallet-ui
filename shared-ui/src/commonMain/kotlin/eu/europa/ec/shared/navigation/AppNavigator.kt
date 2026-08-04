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

/**
 * Phase-3c / N3: the KMP navigation command layer over a Nav3 back stack.
 *
 * Wraps the back stack (a plain `MutableList<NavKey>` — in the Compose host this is a
 * `SnapshotStateList` from `rememberNavBackStack()`, so mutations recompose; in tests a plain
 * list) and exposes the operations the current app performs through `navController`, mapping each
 * navigation-compose pattern to an explicit list op so behavior is preserved 1:1:
 *
 *  - `navigate(X)`                          -> [navigate]
 *  - `navigate(X){ popUpTo(Y){inclusive} }` -> [navigate] with popUpTo args
 *  - `popBackStack()` (Pop)                 -> [pop]
 *  - `popBackStack(route, inclusive)`       -> [popUpTo]
 *  - Splash replacing itself (`navigate(X){ popUpTo(Splash){inclusive} }`, leaving a stack of one)
 *    -> [replaceAll]
 *
 * The Stage-4 Nav2 counterparts of these live in :ui-logic `TypedNavigation.kt`
 * (`navigateToRoute` / `navigateReplacingCurrent` / `popBackStackTo`) with matching parameter
 * names, so the Stage-5 host swap only changes the receiver.
 *
 * Note for Stage 5: [popUpTo] and [navigate]'s `popUpTo` argument match by `==`. Config-carrying
 * pop targets are rebuilt at their call sites rather than read off the back stack, so a value
 * comparison would silently miss the real entry — match those by destination identity/type instead.
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

    /** Pop entries down to the last occurrence of [target] (also removing it when [inclusive]). */
    fun popUpTo(target: NavKey, inclusive: Boolean = false): Boolean {
        val index = backStack.indexOfLast { it == target }
        if (index < 0) return false
        val keep = if (inclusive) index else index + 1
        while (backStack.size > keep) backStack.removeAt(backStack.lastIndex)
        return true
    }

    /** Reset the whole back stack to a single [route] — the "start anew" module switch. */
    fun replaceAll(route: NavKey) {
        backStack.clear()
        backStack.add(route)
    }
}
