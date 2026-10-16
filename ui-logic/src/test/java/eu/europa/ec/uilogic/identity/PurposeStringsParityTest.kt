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

package eu.europa.ec.uilogic.identity

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Whether the text of iOS's permission alerts still comes from the shared Compose string catalogue.
 *
 * iOS reads each purpose string (`NSCameraUsageDescription`, `NSFaceIDUsageDescription`, …) from the
 * bundle's Info.plist, and its translations from `<locale>.lproj/InfoPlist.strings`, before any app code
 * runs. `./gradlew generateIosProject` fills both from the catalogue's `ios_permission_` strings, through
 * `${IOS_PERMISSION_...}` references in `iosApp/project.yml`. Nothing in the build fails when a reference
 * is replaced by a literal, or when a locale lacks a translation — the alert just shows English, which
 * is how the Slovak build came to ask for the camera in English while every screen spoke Slovak.
 *
 * ⚠️ If one of these fails, the fix is in `project.yml` or the catalogue — not here.
 */
class PurposeStringsParityTest {

    private val catalogue = "shared-ui/src/commonMain/composeResources"
    private val purposeKey = Regex("""^\s+([A-Za-z][A-Za-z0-9]*UsageDescription):\s*(.+)$""")
    private val reference = Regex("""^\$\{(IOS_PERMISSION_[A-Z0-9_]+)}$""")

    /** Every purpose string project.yml sets, as (key, value), comments excluded. */
    private fun purposeStrings(): List<Pair<String, String>> =
        RepoFiles.file("iosApp/project.yml").readLines()
            .filterNot { it.trimStart().startsWith("#") }
            .mapNotNull { line ->
                purposeKey.find(line)?.destructured?.let { (key, value) -> key to value.trim() }
            }

    /** The catalogue strings project.yml refers to, by resource name. */
    private fun referencedStrings(): Set<String> = purposeStrings()
        .mapNotNull { (_, value) -> reference.find(value)?.groupValues?.get(1) }
        .map { "ios_permission_" + it.removePrefix("IOS_PERMISSION_").lowercase() }
        .toSet()

    private fun permissionStringsIn(directory: File): Set<String> =
        Regex("""<string name="(ios_permission_[a-z0-9_]+)"""")
            .findAll(File(directory, "strings.xml").readText())
            .map { it.groupValues[1] }
            .toSet()

    @Test
    fun every_purpose_string_is_a_reference_to_the_catalogue() {
        val purposeStrings = purposeStrings()
        assertTrue("found no purpose strings in project.yml — has it moved?", purposeStrings.isNotEmpty())

        val literals = purposeStrings.filterNot { (_, value) -> reference.matches(value) }
        assertTrue(
            "these purpose strings are literals, so no locale can translate them; name an " +
                "ios_permission_ string as \${IOS_PERMISSION_<NAME>} instead: $literals",
            literals.isEmpty(),
        )
    }

    @Test
    fun every_reference_has_its_english_string() {
        val english = permissionStringsIn(File(RepoFiles.root, "$catalogue/values"))
        val missing = referencedStrings() - english

        assertTrue(
            "project.yml refers to strings $catalogue/values/strings.xml does not have: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun every_locale_translates_every_purpose_string() {
        val locales = File(RepoFiles.root, catalogue).listFiles().orEmpty()
            .filter { it.name.startsWith("values-") && File(it, "strings.xml").isFile }
        val untranslated = locales.associate { locale ->
            locale.name to (referencedStrings() - permissionStringsIn(locale))
        }.filterValues { it.isNotEmpty() }

        assertTrue(
            "these locales would show the English permission alerts: $untranslated",
            untranslated.isEmpty(),
        )
    }
}
