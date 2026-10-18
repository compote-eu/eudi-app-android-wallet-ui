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

package eu.europa.ec.shared.wallet.multipaz

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * A refused token request for the log: `400 Bad Request invalid_grant: Token is not active`.
 *
 * Only `error` and `error_description` are read (RFC 6749 §5.2), never the rest of the body, so nothing a
 * server chose to echo back can reach the log. The status alone when the body is not that shape.
 *
 * Why it exists: a refresh refused with a bare `400` was indistinguishable from one refused for any other
 * reason, which is how a rotated refresh token was mistaken for an expired authorization on 2026-09-30.
 */
internal suspend fun HttpResponse.oauthError(): String {
    val fields = runCatching { Json.parseToJsonElement(bodyAsText()).jsonObject }.getOrNull()
    fun field(name: String) = (fields?.get(name) as? JsonPrimitive)?.contentOrNull
    val said = listOfNotNull(field("error"), field("error_description")).joinToString(": ")
    return (if (said.isEmpty()) "$status" else "$status $said").take(MAX_OAUTH_ERROR_LENGTH)
}

private const val MAX_OAUTH_ERROR_LENGTH = 300
