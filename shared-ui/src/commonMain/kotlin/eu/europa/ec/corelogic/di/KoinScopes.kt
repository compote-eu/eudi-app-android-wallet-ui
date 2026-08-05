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

// Phase 3b: split out of :core-logic's LogicCoreModule.kt so the presentation view-models can close
// their scope from commonMain. Already KMP — koin-core (and `KoinPlatform`) are multiplatform; only
// the file it lived in was Android. The scope *definitions* and `getOrCreateKoinScope` stay in
// :core-logic, which owns them. Package unchanged.
//
// Lives in :shared-ui rather than :shared-logic only because koin-core is on this module's classpath
// (it arrived with @KoinViewModel); moving it down would mean adding that dependency to :shared-logic
// for a single function.
package eu.europa.ec.corelogic.di

import org.koin.core.scope.Scope
import org.koin.mp.KoinPlatform

/**
 * Retrieves an existing Koin scope by its identifier.
 *
 * @param scopeId The unique identifier of the scope to retrieve.
 * @return The [Scope] instance if it exists, or null if no scope with the given ID is found.
 */
fun getOrNullKoinScope(scopeId: String): Scope? =
    KoinPlatform.getKoin().getScopeOrNull(scopeId)
