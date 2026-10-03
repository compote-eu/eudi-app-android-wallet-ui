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

// Upstream bf514519's TestTrustMarkExtensions, converted: the shared `localizedTrustMarkText` takes the user's
// language as a tag where upstream took a `java.util.Locale` (FRENCH = "fr", CANADA_FRENCH = "fr-CA", …), and
// the translations map where upstream took wallet-core's `TrustMarkResource.Text`. Case bodies are upstream's.
package eu.europa.ec.corelogic.extension

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrustMarkLocalizationTest {

    // Case 1:
    // 1. The requested language is available after another language.
    // Case 1 Expected Result:
    // The requested translation is selected.
    @Test
    fun given_case_1_when_getLocalizedText_is_called_then_case_1_expected_result_is_returned() {
        assertEquals(mockedFrenchText, mockedLocalisations.localizedTrustMarkText(userLanguageTag = "fr"))
    }

    // Case 2:
    // 1. The matching language tag has mixed case and an underscore.
    // Case 2 Expected Result:
    // The regional translation is selected with its whitespace unchanged.
    @Test
    fun given_case_2_when_getLocalizedText_is_called_then_case_2_expected_result_is_returned() {
        val localisations = mapOf("en" to mockedEnglishText, "FR_ca" to mockedRegionalText)

        assertEquals(mockedRegionalText, localisations.localizedTrustMarkText(userLanguageTag = "fr-CA"))
    }

    // Case 3:
    // 1. A base-language translation precedes an exact regional translation.
    // Case 3 Expected Result:
    // The shared language-matching rule selects the first matching entry.
    @Test
    fun given_case_3_when_getLocalizedText_is_called_then_case_3_expected_result_is_returned() {
        val localisations = mapOf("fr" to mockedFrenchText, "fr-CA" to mockedRegionalText)

        assertEquals(mockedFrenchText, localisations.localizedTrustMarkText(userLanguageTag = "fr-CA"))
    }

    // Case 4:
    // 1. The first matching translation is blank, and another nonblank match exists.
    // Case 4 Expected Result:
    // The nonblank matching translation is selected.
    @Test
    fun given_case_4_when_getLocalizedText_is_called_then_case_4_expected_result_is_returned() {
        val localisations = mapOf("en" to mockedEnglishText, "fr-CA" to " ", "fr" to mockedFrenchText)

        assertEquals(mockedFrenchText, localisations.localizedTrustMarkText(userLanguageTag = "fr-CA"))
    }

    // Case 5:
    // 1. The requested language is absent; a blank entry precedes French and English.
    // Case 5 Expected Result:
    // The first nonblank translation is selected without preferring English.
    @Test
    fun given_case_5_when_getLocalizedText_is_called_then_case_5_expected_result_is_returned() {
        val localisations = mapOf("de" to " ", "fr" to mockedFrenchText, "en" to mockedEnglishText)

        assertEquals(mockedFrenchText, localisations.localizedTrustMarkText(userLanguageTag = "it"))
    }

    // Case 6:
    // 1. Translations are empty or all blank, and the resource has a name.
    // Case 6 Expected Result:
    // Null is returned without using the resource name as certification text.
    @Test
    fun given_case_6_when_getLocalizedText_is_called_then_case_6_expected_result_is_returned() {
        listOf(emptyMap(), mapOf("en" to " ", "fr" to "")).forEach { localisations ->
            assertNull(localisations.localizedTrustMarkText(userLanguageTag = "en"))
        }
    }

    // Case 7:
    // 1. Translation keys include regions, scripts, variants, extensions and mixed case.
    // Case 7 Expected Result:
    // Valid keys remain usable by the shared language-matching helper.
    @Test
    fun given_case_7_when_getLocalizedText_is_called_then_case_7_expected_result_is_returned() {
        listOf(
            "fr-CA" to "fr-CA",
            "FR-ca" to "fr-CA",
            "sr-Latn-RS" to "sr-RS",
            "de-DE-1996" to "de",
            "fr-CA-u-nu-latn" to "fr-CA",
            "fr-CA-x-wallet" to "fr-CA",
        ).forEach { (languageTag, userLanguageTag) ->
            val localisations = mapOf("en" to mockedEnglishText, languageTag to mockedLocalizedText)

            assertEquals(mockedLocalizedText, localisations.localizedTrustMarkText(userLanguageTag = userLanguageTag))
        }
    }

    // Case 8:
    // 1. An unreadable language key precedes a usable matching translation.
    // Case 8 Expected Result:
    // The matching translation is still selected.
    @Test
    fun given_case_8_when_getLocalizedText_is_called_then_case_8_expected_result_is_returned() {
        listOf("", "???", "_").forEach { languageTag ->
            val localisations = mapOf(languageTag to mockedEnglishText, "fr" to mockedFrenchText)

            assertEquals(mockedFrenchText, localisations.localizedTrustMarkText(userLanguageTag = "fr"))
        }
    }

    private val mockedEnglishText = "Certification text"
    private val mockedFrenchText = "Texte de certification"
    private val mockedRegionalText = "  Texte régional  "
    private val mockedLocalizedText = "Selected translation"
    private val mockedLocalisations = mapOf("en" to mockedEnglishText, "fr" to mockedFrenchText)
}
