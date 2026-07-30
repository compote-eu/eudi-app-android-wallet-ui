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
import kotlinx.serialization.Serializable

/**
 * Phase-3c prototype: type-safe navigation routes for Navigation 3.
 *
 * Each route is a [NavKey] (the Nav3 back-stack key) *and* `@Serializable`, so navigation
 * arguments are carried as typed fields (see [DocumentDetailsRoute.documentId]) instead of
 * hand-Base64-serialized config strings. The back stack is plain state — `List<NavKey>` — and
 * Nav3 (with kotlinx-serialization) persists it. This removes the whole legacy apparatus:
 * `UiSerializer` (Class<M> reflection + Base64), `generateComposableArguments`/
 * `generateComposableNavigationLink`, and the `Screen(name, "?arg={x}")` string contract.
 */
sealed interface AppRoute : NavKey

@Serializable
data object SplashRoute : AppRoute

@Serializable
data class DashboardRoute(val startTab: String? = null) : AppRoute

@Serializable
data class DocumentDetailsRoute(val documentId: String) : AppRoute
