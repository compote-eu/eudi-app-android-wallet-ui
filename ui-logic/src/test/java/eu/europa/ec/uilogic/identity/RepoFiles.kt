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

import java.io.File
import java.util.Properties

/**
 * The repository's own build inputs, for the JVM tests that compare what the two platforms are
 * configured with. Shared by [AppIdentityParityTest] and
 * `eu.europa.ec.uilogic.navigation.helper.DeepLinkSchemeParityTest`, which both need to reach
 * `iosApp/project.yml` and the two properties files.
 *
 * These tests are on the JVM precisely because they are the only place that can read *both* sides:
 * Android's values arrive through the generated `BuildConfig`, iOS's through the tracked XcodeGen
 * spec. Neither platform's own test target can see the other's.
 */
internal object RepoFiles {

    /** `version.properties` and `app.properties` — the two files iOS's `${...}` references resolve against. */
    private val identityFiles = listOf("version.properties", "app.properties")

    /**
     * Gradle runs unit tests with the module directory as the working directory, but that is not
     * contractual, so walk up to the build that owns us rather than assuming a depth.
     */
    val root: File by lazy {
        val workingDir = requireNotNull(System.getProperty("user.dir")) { "no user.dir" }
        var dir: File? = File(workingDir).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
        }
        requireNotNull(dir) { "could not find the repository root from $workingDir" }
    }

    /** A tracked file, by its path relative to the repository root. */
    fun file(relativePath: String): File = File(root, relativePath).also {
        require(it.isFile) { "no file at ${it.absolutePath}" }
    }

    /** Every key in [relativePath], read the way Gradle's `getProperty` reads it. */
    fun properties(relativePath: String): Map<String, String> {
        val loaded = Properties().apply { file(relativePath).reader().use { load(it) } }
        return loaded.entries.associate { (key, value) -> key.toString() to value.toString() }
    }

    /** One key, which must be present: an absent key is the failure these tests exist to catch. */
    fun property(relativePath: String, key: String): String =
        requireNotNull(properties(relativePath)[key]) { "$key is missing from $relativePath" }

    /**
     * Resolves the `${NAME}` references XcodeGen expands at generate time, so a test can compare the
     * *value* iOS will be built with rather than the reference text `project.yml` carries. An unknown
     * name fails: XcodeGen would silently write the reference through, which is the bug being fenced.
     */
    fun resolveReferences(value: String): String {
        val identity = identityFiles.flatMap { properties(it).entries }.associate { it.key to it.value }
        return Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)}""").replace(value) { match ->
            val name = match.groupValues[1]
            requireNotNull(identity[name]) {
                "project.yml references $name, which is in none of $identityFiles"
            }
        }
    }

    /**
     * The value `project.yml` gives [key], with comments and surrounding quotes stripped, or null when
     * the key is absent. A flat scan rather than a YAML parse: every key asked for here is unique in
     * the file, and the alternative is a YAML dependency in a module that has no other use for one.
     *
     * Deliberately *not* reference-resolved — several assertions are about whether the spec still
     * references the properties files at all, which resolving would hide.
     */
    fun projectYmlValue(key: String): String? = file("iosApp/project.yml").readLines()
        .asSequence()
        .map { it.substringBefore('#').trim() }
        .firstOrNull { it.startsWith("$key:") }
        ?.removePrefix("$key:")
        ?.trim()
        ?.trim('"', '\'')
}
