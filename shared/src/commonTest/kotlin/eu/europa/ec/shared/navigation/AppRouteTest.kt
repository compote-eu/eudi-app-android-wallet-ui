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

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Proves the Nav3 type-safe routing model on both Android (JVM) and iOS (Kotlin/Native):
 * routes carry typed arguments and round-trip via kotlinx-serialization — no Base64, no
 * Class<M> reflection, no UiSerializer. Also shows the back stack as plain state.
 */
class AppRouteTest {

    @Test
    fun a_typed_route_round_trips_via_kotlinx_serialization() {
        val route = DocumentDetailsRoute(documentId = "doc-123")

        val json = Json.encodeToString(DocumentDetailsRoute.serializer(), route)
        val restored = Json.decodeFromString(DocumentDetailsRoute.serializer(), json)

        assertEquals(route, restored)
        assertEquals("doc-123", restored.documentId)
    }

    @Test
    fun the_back_stack_is_plain_typed_state() {
        val backStack: MutableList<AppRoute> = mutableListOf(SplashRoute)

        backStack.add(DashboardRoute(startTab = "documents"))
        backStack.add(DocumentDetailsRoute(documentId = "doc-9"))

        assertEquals(3, backStack.size)
        assertEquals(DocumentDetailsRoute("doc-9"), backStack.last())
        backStack.removeAt(backStack.lastIndex) // "pop"
        assertEquals(DashboardRoute("documents"), backStack.last())
    }
}
