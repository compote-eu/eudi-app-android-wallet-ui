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

package eu.europa.ec.shared.wallet.document

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs on both Android (JVM) and iOS (Kotlin/Native) from the same source. */
class IssuerMetadataTest {

    private val JsonFormatForTest = Json { ignoreUnknownKeys = true }

    // The wire form the Android document manager writes: locales as BCP-47 tags, logo URIs as
    // strings. Parsing this unchanged is the whole point of the port.
    private val json = """
        {
          "documentConfigurationIdentifier": "eu.europa.ec.eudi.pid_mdoc",
          "credentialIssuerIdentifier": "https://issuer.example",
          "display": [
            { "name": "Personal ID", "locale": "en", "description": "Your PID" }
          ],
          "issuerDisplay": [
            {
              "name": "Test Issuer",
              "locale": "en",
              "logo": { "uri": "https://issuer.example/en.png", "alternativeText": "logo" }
            },
            {
              "name": "Testovací vydavateľ",
              "locale": "sk",
              "logo": { "uri": "https://issuer.example/sk.png" }
            }
          ],
          "claims": [
            {
              "path": ["eu.europa.ec.eudi.pid.1", "given_name"],
              "mandatory": true,
              "display": [{ "name": "First name", "locale": "en" }]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun the_android_wire_form_parses_into_every_field() {
        val metadata = IssuerMetadata.fromJson(json).getOrThrow()

        assertEquals("eu.europa.ec.eudi.pid_mdoc", metadata.documentConfigurationIdentifier)
        assertEquals("https://issuer.example", metadata.credentialIssuerIdentifier)
        assertEquals("Personal ID", metadata.display.single().name)
        assertEquals(2, metadata.issuerDisplay?.size)
        assertEquals(
            "https://issuer.example/sk.png",
            metadata.issuerDisplay?.last()?.logo?.uri,
        )

        val claim = metadata.claims!!.single()
        assertEquals(listOf("eu.europa.ec.eudi.pid.1", "given_name"), claim.path)
        assertEquals(true, claim.mandatory)
        assertEquals("First name", claim.display.single().name)
    }

    @Test
    fun unknown_keys_are_ignored_so_newer_issuer_metadata_still_parses() {
        val withExtras = """
            {
              "documentConfigurationIdentifier": "id",
              "credentialIssuerIdentifier": "https://issuer.example",
              "somethingNewUpstreamAdded": { "nested": [1, 2, 3] }
            }
        """.trimIndent()

        val metadata = IssuerMetadata.fromJson(withExtras).getOrThrow()

        assertEquals("id", metadata.documentConfigurationIdentifier)
        assertTrue(metadata.display.isEmpty())
        assertNull(metadata.issuerDisplay)
    }

    @Test
    fun malformed_json_is_a_failed_result_rather_than_a_throw() {
        assertTrue(IssuerMetadata.fromJson("not json at all").isFailure)
    }

    @Test
    fun a_round_trip_through_json_preserves_the_model() {
        val metadata = IssuerMetadata.fromJson(json).getOrThrow()

        assertEquals(metadata, IssuerMetadata.fromJson(metadata.toJson()).getOrThrow())
    }

    //region locale selection

    private val issuerDisplays = listOf(
        IssuerMetadata.IssuerDisplay(name = "English", locale = "en"),
        IssuerMetadata.IssuerDisplay(name = "Slovak", locale = "sk"),
    )

    @Test
    fun an_exact_language_tag_selects_its_entry() {
        assertEquals("Slovak", issuerDisplays.localizedOrFirst("sk") { it.locale }?.name)
    }

    @Test
    fun a_region_qualified_tag_matches_on_the_language_subtag_alone() {
        // Android compares Locale.language, so en-GB must resolve the "en" entry rather than fall
        // back to the first one by accident.
        assertEquals("English", issuerDisplays.localizedOrFirst("en-GB") { it.locale }?.name)
        assertEquals("Slovak", issuerDisplays.localizedOrFirst("sk-SK") { it.locale }?.name)
    }

    @Test
    fun an_underscore_separated_tag_is_matched_too() {
        // Locale.toString() uses underscores, and metadata written from one would too.
        assertEquals("Slovak", issuerDisplays.localizedOrFirst("sk_SK") { it.locale }?.name)
    }

    @Test
    fun the_language_subtag_comparison_is_case_insensitive() {
        assertEquals("Slovak", issuerDisplays.localizedOrFirst("SK") { it.locale }?.name)
        assertEquals(
            "English",
            listOf(IssuerMetadata.IssuerDisplay(name = "English", locale = "EN"))
                .localizedOrFirst("en") { it.locale }?.name,
        )
    }

    @Test
    fun an_unmatched_locale_falls_back_to_the_first_entry() {
        assertEquals("English", issuerDisplays.localizedOrFirst("de") { it.locale }?.name)
    }

    @Test
    fun an_entry_without_a_locale_never_matches_but_can_still_be_the_fallback() {
        val unlabelled = listOf(IssuerMetadata.IssuerDisplay(name = "Unlabelled", locale = null))

        // No match on language, so this is the first-entry fallback speaking, not a match.
        assertEquals("Unlabelled", unlabelled.localizedOrFirst("en") { it.locale }?.name)

        val labelledSecond = unlabelled + IssuerMetadata.IssuerDisplay(name = "English", locale = "en")
        assertEquals("English", labelledSecond.localizedOrFirst("en") { it.locale }?.name)
    }

    @Test
    fun a_blank_locale_on_either_side_does_not_match() {
        // Guards against "" == "" resolving the wrong display: a document whose metadata carries an
        // empty locale must fall back, not match every user locale.
        val blank = listOf(
            IssuerMetadata.IssuerDisplay(name = "Blank", locale = ""),
            IssuerMetadata.IssuerDisplay(name = "English", locale = "en"),
        )

        assertEquals("Blank", blank.localizedOrFirst("") { it.locale }?.name)
        assertEquals("English", blank.localizedOrFirst("en") { it.locale }?.name)
    }

    @Test
    fun an_empty_or_null_list_resolves_to_null() {
        assertNull(emptyList<IssuerMetadata.IssuerDisplay>().localizedOrFirst("en") { it.locale })
        assertNull(null.localizedOrFirst<IssuerMetadata.IssuerDisplay>("en") { it.locale })
    }

    //endregion

    // ---- resolving a claim's name for a locale --------------------------------------------------
    //
    // The stored shape keeps every language the issuer published, keyed by claim path, and picks one
    // when the screen is drawn. These pin the fallback order, which is the whole of that decision.

    private fun claim(vararg display: Pair<String?, String>) = IssuerMetadata.Claim(
        path = listOf("eu.europa.ec.eudi.pid.1", "family_name"),
        display = display.map { (locale, name) ->
            IssuerMetadata.Claim.Display(name = name, locale = locale)
        },
    )

    @Test
    fun an_exact_locale_match_wins() {
        val subject = claim("en" to "Family Name(s)", "de" to "Familienname")

        assertEquals("Familienname", subject.displayNameFor("de"))
    }

    @Test
    fun a_region_falls_back_to_its_language() {
        // The screen asks with whatever `NSLocale` gives, which is often regional; issuers publish the
        // bare language. Without this, `en-GB` would miss an `en` entry entirely.
        val subject = claim("en" to "Family Name(s)")

        assertEquals("Family Name(s)", subject.displayNameFor("en-GB"))
        assertEquals("Family Name(s)", subject.displayNameFor("en_US"))
    }

    @Test
    fun an_unlocalized_entry_is_used_when_no_language_matches() {
        val subject = claim(null to "Family Name(s)")

        assertEquals("Family Name(s)", subject.displayNameFor("sk"))
    }

    @Test
    fun an_unmatched_locale_still_returns_something_rather_than_nothing() {
        // The EU dev issuer publishes `en` only, so this is the Slovak case in practice: a name in the
        // wrong language beats a raw data-element identifier on screen.
        val subject = claim("en" to "Family Name(s)")

        assertEquals("Family Name(s)", subject.displayNameFor("sk"))
    }

    @Test
    fun a_claim_the_issuer_never_named_resolves_to_null() {
        // Which is what lets the reader fall back to the identifier instead of showing an empty title.
        val subject = IssuerMetadata.Claim(path = listOf("ns", "family_name"))

        assertEquals(null, subject.displayNameFor("en"))
    }

    @Test
    fun the_wire_shape_the_issuer_actually_sends_round_trips() {
        // Copied from dev.issuer-backend.eudiw.dev's `credential_metadata.claims[0]`, so a change in
        // that shape fails here rather than silently producing unnamed claims.
        val wire = """
            {"path":["eu.europa.ec.eudi.pid.1","family_name"],"mandatory":true,
             "display":[{"name":"Family Name(s)","locale":"en"}]}
        """.trimIndent()
        val parsed = JsonFormatForTest.decodeFromString(IssuerMetadata.Claim.serializer(), wire)

        assertEquals(listOf("eu.europa.ec.eudi.pid.1", "family_name"), parsed.path)
        assertEquals(true, parsed.mandatory)
        assertEquals("Family Name(s)", parsed.displayNameFor("en"))
    }
}
