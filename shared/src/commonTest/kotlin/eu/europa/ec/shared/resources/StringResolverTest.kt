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

import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.StringResource
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Shows that [StringResolver] is trivially fakeable in commonTest — the pattern migrated
 * view-models use to assert on resolved strings without the compose-resources runtime reader.
 */
class StringResolverTest {

    private val fake = object : StringResolver {
        override suspend fun resolve(resource: StringResource) = "resolved:${resource.key}"
        override suspend fun resolve(resource: StringResource, vararg formatArgs: Any) =
            "resolved:${resource.key}:${formatArgs.joinToString()}"
    }

    @Test
    fun a_fake_resolver_returns_canned_values_by_resource_key() = runTest {
        assertEquals("resolved:generic_error_message", fake.resolve(Res.string.generic_error_message))
        assertEquals(
            "resolved:generic_network_error_message:42",
            fake.resolve(Res.string.generic_network_error_message, 42),
        )
    }
}
