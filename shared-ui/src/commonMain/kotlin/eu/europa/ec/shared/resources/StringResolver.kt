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

import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString

/**
 * Phase 3a: the KMP-shared *suspend* string accessor, for code that resolves inside a coroutine.
 *
 * There are now three ways to reach the shared corpus, and which one to use follows from the
 * calling layer:
 *  - view-model state holds [UiText] and the composable resolves it — no resolver injected;
 *  - synchronous interactor/transformer code uses [StringCatalog];
 *  - suspend code (and *all* plural resolution) uses this interface.
 *
 * Plurals live here rather than on [StringCatalog] because selecting a plural form needs the
 * locale's CLDR categories, which compose-resources applies during the suspend read. Caching
 * forms for a synchronous accessor would bake in English's one/other split.
 *
 * Fakeable in `commonTest` without compose-resources' runtime reader.
 */
interface StringResolver {

    suspend fun resolve(resource: StringResource): String

    suspend fun resolve(resource: StringResource, vararg formatArgs: Any): String

    suspend fun resolvePlural(
        resource: PluralStringResource,
        quantity: Int,
        vararg formatArgs: Any,
    ): String
}

/** Default [StringResolver] backed by Compose Multiplatform's compose-resources. */
class ComposeResourcesStringResolver : StringResolver {

    override suspend fun resolve(resource: StringResource): String =
        getString(resource)

    override suspend fun resolve(resource: StringResource, vararg formatArgs: Any): String =
        getString(resource, *formatArgs)

    override suspend fun resolvePlural(
        resource: PluralStringResource,
        quantity: Int,
        vararg formatArgs: Any,
    ): String = getPluralString(resource, quantity, *formatArgs)
}
