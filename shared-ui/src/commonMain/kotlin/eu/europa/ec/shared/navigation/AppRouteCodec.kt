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

/**
 * Carries an [AppRoute] across the one seam that cannot be typed: `:core-logic`.
 *
 * `PresentationControllerConfig.initiatorRoute` remembers which destination started a presentation
 * so the flow can return there, but it lives in the domain layer, which must not depend on
 * `:shared-ui` (domain -> UI). core-logic never reads the value — it is an opaque token — so the
 * route is encoded on the way in (`RequestUriConfigMapper`) and decoded on the way back out
 * (`PresentationSuccessViewModel`).
 *
 * This replaces the Base64 hop the same seam used to take through `UiSerializer`. Plain JSON via the
 * sealed hierarchy's generated polymorphic serializer means no reflection and no `android.util.Base64`
 * — so unlike the old path it also works on a plain JVM (and on iOS).
 */
object AppRouteCodec {

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(route: AppRoute): String = json.encodeToString<AppRoute>(route)

    /** Decodes a token produced by [encode]; `null` if it is absent or unreadable. */
    fun decode(token: String?): AppRoute? {
        if (token.isNullOrBlank()) return null
        return runCatching { json.decodeFromString<AppRoute>(token) }.getOrNull()
    }
}
