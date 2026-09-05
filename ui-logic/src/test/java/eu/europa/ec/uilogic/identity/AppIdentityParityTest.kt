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

import eu.europa.ec.uilogic.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the app's name, id and version still come from one place on both platforms.
 *
 * `version.properties` owns the version and `app.properties` owns the name and the id. Android reads
 * them in `build-logic`; iOS reads the same two files through XcodeGen's generate-time `${...}`
 * expansion in `iosApp/project.yml`. Nothing in either build fails when that wiring is quietly
 * replaced by a literal — the app just ships the wrong string, which is how the iOS build came to be
 * named "iosApp" with version "1.0" while Android read `yyyy.mm.v`.
 *
 * ## Why the flavour assertions need no list of flavours
 *
 * Every per-variant key is checked against [BuildConfig.FLAVOR], and `./gradlew test` runs this class
 * once per flavour — so adding a third flavour extends the coverage by itself. That matters because a
 * missing suffix key is silent on iOS: `$(APP_NAME_SUFFIX_$(BUILD_VARIANT))` resolves to nothing at
 * all when the setting is absent, leaving the unsuffixed name rather than an error.
 *
 * ⚠️ If one of these fails, the fix is in the properties file or `project.yml` — not here. Relaxing an
 * assertion re-opens exactly the drift it was added to close.
 */
class AppIdentityParityTest {

    private val versionProperties = "version.properties"
    private val appProperties = "app.properties"
    private val flavour = BuildConfig.FLAVOR

    /** Mirrors `IosAppGroup.INFO_PLIST_KEY`; this module cannot see the iOS source set. */
    private val appGroupKey = "EUDIAppGroupIdentifier"

    /** Mirrors `IosKeychainAccessGroup.INFO_PLIST_KEY`, for the same reason. */
    private val keychainGroupKey = "EUDIKeychainAccessGroup"

    @Test
    fun android_takes_its_version_from_version_properties() {
        // `BuildConfig.APP_VERSION` is what `ConfigLogic.appVersion` shows on the settings screen.
        assertEquals(
            "Android's APP_VERSION is not the value in $versionProperties",
            RepoFiles.property(versionProperties, "VERSION_NAME"),
            BuildConfig.APP_VERSION,
        )
    }

    @Test
    fun ios_takes_the_same_version_from_the_same_file() {
        assertEquals(
            "project.yml no longer reads VERSION_NAME from $versionProperties",
            "\${VERSION_NAME}",
            RepoFiles.projectYmlValue("MARKETING_VERSION"),
        )
        assertEquals(
            "project.yml no longer reads VERSION_CODE from $versionProperties",
            "\${VERSION_CODE}",
            RepoFiles.projectYmlValue("CURRENT_PROJECT_VERSION"),
        )
    }

    @Test
    fun both_platforms_would_display_the_same_version() {
        // The assertion that actually matters to a user: the string under the settings list is the same
        // on both platforms. Resolving iOS's reference is what makes this a value comparison rather
        // than a spelling one.
        assertEquals(
            "the settings screen would show different versions per platform",
            BuildConfig.APP_VERSION,
            RepoFiles.resolveReferences(requireNotNull(RepoFiles.projectYmlValue("MARKETING_VERSION"))),
        )
    }

    @Test
    fun the_ios_plist_reads_the_version_settings_rather_than_a_literal() {
        // `IosSettingsPlatformBridge` reads CFBundleShortVersionString, so a literal here is precisely
        // the bug that showed "1.0" on the settings screen.
        assertEquals(
            "CFBundleShortVersionString is not fed by MARKETING_VERSION",
            "$(MARKETING_VERSION)",
            RepoFiles.projectYmlValue("CFBundleShortVersionString"),
        )
        assertEquals(
            "CFBundleVersion is not fed by CURRENT_PROJECT_VERSION",
            "$(CURRENT_PROJECT_VERSION)",
            RepoFiles.projectYmlValue("CFBundleVersion"),
        )
    }

    @Test
    fun the_launcher_name_has_one_source_on_both_platforms() {
        assertEquals(
            "project.yml no longer reads APP_NAME from $appProperties",
            "\${APP_NAME}",
            RepoFiles.projectYmlValue("APP_NAME"),
        )
        assertEquals(
            "CFBundleDisplayName is not fed by APP_DISPLAY_NAME, so iOS falls back to the target name",
            "$(APP_DISPLAY_NAME)",
            RepoFiles.projectYmlValue("CFBundleDisplayName"),
        )
        assertEquals(
            "APP_DISPLAY_NAME no longer composes the name with the current variant's suffix",
            "$(APP_NAME)$(APP_NAME_SUFFIX_$(BUILD_VARIANT))",
            RepoFiles.projectYmlValue("APP_DISPLAY_NAME"),
        )
    }

    @Test
    fun the_bundle_id_is_composed_from_app_id_and_the_variant_suffix() {
        assertEquals(
            "the bundle id is no longer built from APP_ID plus the current variant's suffix",
            "\${APP_ID}$(BUNDLE_ID_SUFFIX_$(BUILD_VARIANT))",
            RepoFiles.projectYmlValue("APP_BUNDLE_ID"),
        )
    }

    /**
     * The app and its document-provider extension must stay one identity: the extension's bundle id is
     * the app's plus a suffix, and iOS pairs them on exactly that. Both derive from `APP_BUNDLE_ID`
     * rather than restating the expression, so this asserts the derivation rather than the value.
     *
     * This test exists because adding the extension broke the one above in a way worth keeping caught:
     * a second `PRODUCT_BUNDLE_IDENTIFIER` appeared and the helper silently returned it instead of the
     * app's.
     */
    @Test
    fun the_extension_bundle_id_is_derived_from_the_app_s() {
        assertEquals(
            "the app and its extension no longer derive their ids from one place",
            listOf("$(APP_BUNDLE_ID).provider", "$(APP_BUNDLE_ID)"),
            RepoFiles.projectYmlValues("PRODUCT_BUNDLE_IDENTIFIER"),
        )
    }

    @Test
    fun this_flavour_has_a_name_and_an_id_suffix_in_both_builds() {
        // Present, not non-empty: both suffixes are empty on purpose for the name, and for the id on
        // the demo flavour. An absent key is the failure — Android would name the app "null".
        assertTrue(
            "$appProperties has no APP_NAME_SUFFIX_$flavour",
            RepoFiles.properties(appProperties).containsKey("APP_NAME_SUFFIX_$flavour"),
        )
        assertTrue(
            "$appProperties has no APP_ID_SUFFIX_$flavour",
            RepoFiles.properties(appProperties).containsKey("APP_ID_SUFFIX_$flavour"),
        )
        assertEquals(
            "project.yml does not pass APP_NAME_SUFFIX_$flavour through, so iOS drops the suffix",
            "\${APP_NAME_SUFFIX_$flavour}",
            RepoFiles.projectYmlValue("APP_NAME_SUFFIX_$flavour"),
        )
        assertEquals(
            "project.yml does not pass APP_ID_SUFFIX_$flavour through, so iOS drops the suffix",
            "\${APP_ID_SUFFIX_$flavour}",
            RepoFiles.projectYmlValue("BUNDLE_ID_SUFFIX_$flavour"),
        )
    }

    @Test
    fun android_takes_its_application_id_from_app_properties() {
        // A text assertion, unusually, because this module cannot see it any other way: APPLICATION_ID
        // exists only in the *application* module's BuildConfig, and `:androidApp` has no unit tests.
        // The value itself is proven by the build — what can regress silently is the wiring.
        val declarations = RepoFiles.file("androidApp/build.gradle.kts").readLines()
            .map { it.substringBefore("//").trim() }
            // `applicationId =`, not `applicationIdSuffix` — the build types carry their own (null)
            // suffix property, and matching on the prefix alone picks those up too.
            .filter { Regex("""^applicationId\s*=""").containsMatchIn(it) }
        assertEquals("expected exactly one applicationId declaration", 1, declarations.size)
        assertTrue(
            "androidApp declares its applicationId without reading APP_ID: ${declarations.single()}",
            declarations.single().contains("APP_ID"),
        )
    }

    @Test
    fun the_authorization_redirect_scheme_is_shared_but_not_derived_from_the_bundle_id() {
        assertEquals(
            "Android's authorization scheme is not the value in $appProperties",
            RepoFiles.property(appProperties, "OID4VCI_REDIRECT_SCHEME"),
            BuildConfig.ISSUE_AUTHORIZATION_SCHEME,
        )
        // The scheme the authorization server has registered against our client_id. Deriving it from
        // the bundle id would append `.dev` on the dev flavour — the one everything is tested on — and
        // the redirect would land nowhere.
        assertTrue(
            "iOS no longer registers the authorization scheme from $appProperties",
            RepoFiles.file("iosApp/project.yml").readText()
                .contains("- \${OID4VCI_REDIRECT_SCHEME}"),
        )
    }

    @Test
    fun the_app_group_is_published_to_both_processes_and_never_derived() {
        // Two occurrences, one per target. The app and the extension are separate processes with
        // separate containers, and the group is the only place both can read — so a target that stops
        // publishing this key does not misbehave visibly, it just opens an empty wallet.
        assertEquals(
            "both targets must publish $appGroupKey from \$(APP_BUNDLE_ID)",
            listOf("group.\$(APP_BUNDLE_ID)", "group.\$(APP_BUNDLE_ID)"),
            RepoFiles.projectYmlValues(appGroupKey),
        )
        // And the entitlements must name that same expression. `$(PRODUCT_BUNDLE_IDENTIFIER)` is
        // equal to it for the app but NOT for the extension, whose product id carries `.provider`:
        // that mismatch is what made the extension request a group neither binary is entitled to.
        val entitlementGroups = RepoFiles.file("iosApp/project.yml").readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.startsWith("- group.") }
            .map { it.removePrefix("-").trim() }
        assertEquals(
            "both application-groups entitlements must name group.\$(APP_BUNDLE_ID)",
            listOf("group.\$(APP_BUNDLE_ID)", "group.\$(APP_BUNDLE_ID)"),
            entitlementGroups,
        )
    }

    @Test
    fun both_targets_share_one_keychain_group_for_the_documents() {
        // Since the documents became Keychain items, this is the app-group story one layer up and it
        // fails the same silent way: an extension has its own bundle id, so without a *declared*
        // shared group it reads its own keychain, finds nothing, and answers a Digital Credentials
        // request with an empty wallet. Nothing logs a reason.
        val expected = "\$(AppIdentifierPrefix)\$(APP_BUNDLE_ID)"
        assertEquals(
            "both targets must publish $keychainGroupKey from the team prefix and \$(APP_BUNDLE_ID)",
            listOf(expected, expected),
            RepoFiles.projectYmlValues(keychainGroupKey),
        )
        val entitlementKeychainGroups = RepoFiles.file("iosApp/project.yml").readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.startsWith("- \$(AppIdentifierPrefix)") }
            .map { it.removePrefix("-").trim() }
        assertEquals(
            "both keychain-access-groups entitlements must name $expected",
            listOf(expected, expected),
            entitlementKeychainGroups,
        )
    }

    @Test
    fun the_files_were_actually_read() {
        // Cheap insurance against every assertion above passing on empty input.
        assertTrue("no keys read from $versionProperties", RepoFiles.properties(versionProperties).size >= 2)
        assertTrue("no keys read from $appProperties", RepoFiles.properties(appProperties).size >= 7)
        assertTrue("BuildConfig.FLAVOR is empty", flavour.isNotEmpty())
    }
}
