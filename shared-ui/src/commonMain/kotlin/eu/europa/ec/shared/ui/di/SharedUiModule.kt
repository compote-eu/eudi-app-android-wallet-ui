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

package eu.europa.ec.shared.ui.di

import eu.europa.ec.shared.resources.ComposeResourcesStringCatalog
import eu.europa.ec.shared.resources.ComposeResourcesStringResolver
import eu.europa.ec.shared.resources.StringCatalog
import eu.europa.ec.shared.resources.StringResolver
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

/**
 * Phase 3b: this module's Koin definitions — one `@Module` per Gradle module, as everywhere else in
 * the project. What is new is that it is declared in **commonMain**, so the same declarations serve
 * Android and iOS.
 *
 * `@ComponentScan` is compilation-scoped, so the broad `eu.europa.ec` package is not a conflict with
 * the feature modules that scan their own sub-packages (e.g. `FeatureStartupModule` scans
 * `eu.europa.ec.startupfeature`): each module's scan only sees its own sources, not its
 * dependencies' binaries. Keeping it broad means a view-model moving into commonMain needs no
 * change here — it just has to be annotated, exactly as it was in its feature module.
 *
 * Registered in `assembly-logic`'s `@KoinApplication`.
 */
@Module
@Configuration
@ComponentScan("eu.europa.ec")
class SharedUiModule

/**
 * The synchronous string corpus used by interactors and transformers. Declared here rather than in
 * `:ui-logic` so iOS resolves the same binding from the same graph.
 *
 * A singleton because it owns the warmed cache; `warm()` is awaited once at application startup.
 */
@Single
fun provideStringCatalog(): StringCatalog = ComposeResourcesStringCatalog()

/**
 * The suspend resolver, for coroutine-scoped resolution and for all plural lookups.
 *
 * Moved here from `:ui-logic`'s `LogicUiModule` in Phase 3a: the contract and its implementation
 * both live in `commonMain`, so binding it from an Android-only module would have left iOS without
 * it.
 */
@Single
fun provideStringResolver(): StringResolver = ComposeResourcesStringResolver()
