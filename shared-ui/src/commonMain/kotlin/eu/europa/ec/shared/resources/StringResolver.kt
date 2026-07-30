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
 * Phase 3a: the KMP-shared string accessor for (eventually commonMain) presentation code —
 * the replacement for the Android-`Context` `ResourceProvider.getString`. View-models depend
 * on this interface and resolve shared [Res] strings platform-neutrally; unit tests can fake
 * it, avoiding compose-resources' runtime resource reader.
 *
 * Resolution is `suspend` because compose-resources reads packaged resources asynchronously;
 * view-models call it from their coroutine scopes.
 */
interface StringResolver {

    suspend fun resolve(resource: StringResource): String

    suspend fun resolve(resource: StringResource, vararg formatArgs: Any): String
}

/** Default [StringResolver] backed by Compose Multiplatform's compose-resources. */
class ComposeResourcesStringResolver : StringResolver {

    override suspend fun resolve(resource: StringResource): String =
        getString(resource)

    override suspend fun resolve(resource: StringResource, vararg formatArgs: Any): String =
        getString(resource, *formatArgs)
}
