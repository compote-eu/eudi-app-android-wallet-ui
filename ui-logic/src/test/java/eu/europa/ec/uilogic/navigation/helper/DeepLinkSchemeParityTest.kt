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

package eu.europa.ec.uilogic.navigation.helper

import eu.europa.ec.uilogic.BuildConfig
import eu.europa.ec.uilogic.identity.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Whether the two platforms register the same URL schemes.
 *
 * ## Why this test is on the JVM, and why that matters
 *
 * It reads **both** platforms' real sources, so neither is restated:
 *
 *  - Android's come from the **generated** [BuildConfig], whose values the convention plugin also feeds
 *    into the `${'$'}{...Scheme}` manifest placeholders. Change a scheme there and this test sees it.
 *  - iOS's come from **`iosApp/project.yml`**, the tracked XcodeGen source. (`Info.plist` is generated
 *    from it and git-ignored, so the plist is not a source anyone can compare against.)
 *
 * A Kotlin/Native test can read neither: `BuildConfig` is Android's, and reaching the repo's own files
 * from a Native test binary is not something to rely on. So `IosDeepLinksTest` had to restate Android's
 * ten schemes as a literal, which meant an **Android-side** change failed nothing — the drift this was
 * meant to catch could still happen in one direction. This test closes that; the iOS one now pins only
 * what iOS itself routes.
 *
 * ⚠️ If this fails after a deliberate change, the fix is to update [deliberatelyNotOnIos] *with a
 * reason*, not to widen the assertion. Registering a scheme the app cannot act on is worse than leaving
 * it out: the link opens the wallet onto a dead end.
 */
class DeepLinkSchemeParityTest {

    /**
     * The two schemes Android registers and iOS deliberately does not — the only literal here, and a
     * *decision* rather than a copy of anything.
     *
     * `eudi-rqes` is RP-initiated document retrieval: the iOS `EudiRQESUi` package exposes only
     * `initiate(on:fileUrl:)` and `resume(on:authorizationCode:)`, so there is no remote-URI entry point
     * to hand the link to. `eudi-wallet` is dead on Android too — `BuildConfig.DEEPLINK` has no
     * consumers and `DeepLinkType` does not list it. Both reasons are spelled out in `project.yml`.
     */
    private val deliberatelyNotOnIos = setOf("eudi-rqes", "eudi-wallet")

    /** Every scheme Android registers, from the generated config rather than a hand-kept list. */
    private fun androidSchemes(): Set<String> = setOf(
        // `DEEPLINK` is the only one stored as a URL rather than a bare scheme.
        BuildConfig.DEEPLINK.removeSuffix("://"),
        BuildConfig.EUDI_OPENID4VP_SCHEME,
        BuildConfig.MDOC_OPENID4VP_SCHEME,
        BuildConfig.OPENID4VP_SCHEME,
        BuildConfig.HAIP_OPENID4VP_SCHEME,
        BuildConfig.CREDENTIAL_OFFER_SCHEME,
        BuildConfig.CREDENTIAL_OFFER_HAIP_SCHEME,
        BuildConfig.ISSUE_AUTHORIZATION_SCHEME,
        BuildConfig.RQES_SCHEME,
        BuildConfig.RQES_DOC_RETRIEVAL_SCHEME,
    )

    /**
     * Every scheme iOS registers, parsed out of `project.yml`'s `CFBundleURLTypes`.
     *
     * Only list items *directly under* a `CFBundleURLSchemes:` key count. That precision is needed
     * because the same block carries a comment naming the two excluded schemes, and a looser scan would
     * read those back as registered — which would make this test agree with itself and prove nothing.
     */
    private fun iosSchemes(): Set<String> {
        val yaml = projectYml().readLines()
        val schemes = mutableSetOf<String>()
        var listIndent: Int? = null

        for (raw in yaml) {
            val line = raw.substringBefore('#').trimEnd()
            if (line.isBlank()) continue
            val indent = line.indexOfFirst { !it.isWhitespace() }
            val content = line.trim()

            if (content == "CFBundleURLSchemes:") {
                listIndent = indent
                continue
            }
            val open = listIndent ?: continue
            if (content.startsWith("- ") && indent > open) {
                // Resolved, because the authorization scheme is a `${...}` reference to
                // `app.properties` — the file Android's convention plugin reads for the same string.
                // Comparing the reference text would fail against Android's resolved value.
                schemes += RepoFiles.resolveReferences(
                    content.removePrefix("- ").trim().trim('"', '\''),
                )
            } else if (indent <= open) {
                // Dedented out of the list: any following `- ` belongs to something else.
                listIndent = null
            }
        }
        return schemes
    }

    /** Shared with `AppIdentityParityTest`, which needs the same file and the same resolution. */
    private fun projectYml() = RepoFiles.file("iosApp/project.yml")

    @Test
    fun ios_registers_every_android_scheme_except_the_documented_exclusions() {
        val android = androidSchemes()
        val ios = iosSchemes()

        assertEquals(
            "iOS is missing Android schemes that are not in the documented exclusion list",
            deliberatelyNotOnIos,
            android - ios,
        )
    }

    @Test
    fun ios_registers_nothing_android_does_not() {
        // The other direction, which no earlier check covered: a scheme added to project.yml alone
        // would open the iOS app for links Android ignores.
        assertEquals(
            "iOS registers schemes Android does not",
            emptySet<String>(),
            iosSchemes() - androidSchemes(),
        )
    }

    @Test
    fun every_exclusion_is_still_a_scheme_android_registers() {
        // Guards the exclusion list itself: if Android drops `eudi-rqes`, the entry becomes a note about
        // nothing and should go rather than sit here looking meaningful.
        assertEquals(
            "the exclusion list names schemes Android no longer registers",
            emptySet<String>(),
            deliberatelyNotOnIos - androidSchemes(),
        )
    }

    @Test
    fun both_sides_were_actually_read() {
        // Cheap insurance against the whole test passing because a parse silently produced nothing —
        // two empty sets satisfy every assertion above.
        assertEquals("unexpected number of Android schemes", 10, androidSchemes().size)
        assertEquals("unexpected number of iOS schemes", 8, iosSchemes().size)
    }
}
