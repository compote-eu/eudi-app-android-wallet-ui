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

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Guards the property that lets a Nav3 route payload carry text as a resource key: a [UiText]
 * survives a serialization round-trip, and the encoded form contains the key rather than resolved
 * characters.
 *
 * The round-trip is the real contract. Nav3 saves the back stack through this on every process
 * death, and `AppRouteCodec` puts it through the `:core-logic` `initiatorRoute` seam — so a
 * regression here is a crash on restore, not a cosmetic one.
 */
class UiTextSerializationTest {

    private val key = Res.string.generic_error_message
    private val formatted = Res.string.home_screen_welcome_user_message
    private val plural = Res.plurals.transactions_screen_some_minutes_ago_message

    private fun roundTrip(value: UiText): UiText =
        Json.decodeFromString<UiText>(Json.encodeToString<UiText>(value))

    @Test
    fun raw_survives_a_round_trip() {
        val value: UiText = UiText.Raw("issuer said no")
        assertEquals(value, roundTrip(value))
    }

    @Test
    fun resource_survives_a_round_trip() {
        val value: UiText = UiText.Resource(key)
        assertEquals(value, roundTrip(value))
    }

    @Test
    fun formatted_resource_keeps_its_arguments() {
        val value: UiText = UiText.Resource(formatted, "Martin")
        val restored = roundTrip(value)
        assertEquals(value, restored)
        assertEquals(listOf("Martin"), (restored as UiText.Resource).args)
    }

    @Test
    fun plural_survives_a_round_trip() {
        val value: UiText = UiText.Plural(plural, quantity = 4, args = listOf("4"))
        assertEquals(value, roundTrip(value))
    }

    @Test
    fun the_encoded_form_carries_the_key_not_the_text() {
        val encoded = Json.encodeToString<UiText>(UiText.Resource(key))
        assertTrue(
            encoded.contains(key.key),
            "expected the resource key in $encoded",
        )
    }

    @Test
    fun an_unknown_key_fails_loudly() {
        val encoded = Json.encodeToString<UiText>(UiText.Resource(key))
            .replace(key.key, "no_such_string")
        val failure = assertFailsWith<SerializationException> {
            Json.decodeFromString<UiText>(encoded)
        }
        assertTrue(
            failure.message.orEmpty().contains("no_such_string"),
            "expected the offending key in the message, got: ${failure.message}",
        )
    }
}
