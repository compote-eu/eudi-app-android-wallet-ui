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

@Suppress("DSL_SCOPE_VIOLATION")
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.gms) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.secrets) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.owasp.dependencycheck) apply false
    alias(libs.plugins.kotlinx.kover) apply false
    alias(libs.plugins.sonar) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.androidx.room) apply false
    alias(libs.plugins.koin.compiler) apply false
    alias(libs.plugins.dependencyGraph)
    // `apply false` puts these on the build's classpath without applying them here; the convention
    // plugin below is what applies and configures them.
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
    // Applied to the root project only: it configures detekt here and ktlint across every
    // project, so a new module is covered by the gate without having to remember it.
    id("project.code.quality")
}

moduleGraphConfig {
    readmePath.set("wiki/dependency-graph.md")
}

// ── iOS project generation ────────────────────────────────────────────────────────────────────────
//
// `iosApp/project.yml` takes the app's name, id and version from `app.properties` and
// `version.properties` — the same two files the Android build reads — through XcodeGen's
// generate-time `${...}` expansion, which reads the *environment*. This task is what puts them there,
// and is therefore the only supported way to regenerate the Xcode project: a bare
// `xcodegen generate` leaves every reference as literal text, with no warning at all.
tasks.register("generateIosProject") {
    group = "ios"
    description = "Regenerates iosApp/iosApp.xcodeproj from project.yml and the app/version properties"

    // Locals rather than script-level properties: the configuration cache cannot serialize a
    // reference to the script object, so everything the action closes over has to be a plain value.
    val identityFiles = listOf("version.properties", "app.properties")
    val repoRoot = layout.projectDirectory.asFile
    inputs.files(identityFiles.map { File(repoRoot, it) } + File(repoRoot, "iosApp/project.yml"))

    doLast {
        // A `${NAME}` XcodeGen should have expanded. `${NAME:-default}` is excluded on purpose: that
        // form is shell, and the script phases in project.yml legitimately use it. A script phase
        // needing a plain `${NAME}` would trip this check — write `$NAME` there instead.
        val unexpandedReference = Regex("""\$\{[A-Za-z_][A-Za-z0-9_]*}""")
        val environment = linkedMapOf<String, String>()
        identityFiles.forEach { name ->
            val file = File(repoRoot, name)
            if (!file.isFile) throw GradleException("$name is missing from the repository root")
            val properties = java.util.Properties().apply { file.reader().use { load(it) } }
            properties.forEach { key, value ->
                val previous = environment.put(key.toString(), value.toString())
                if (previous != null) {
                    throw GradleException("${key} is defined in more than one of $identityFiles")
                }
            }
        }

        val builder = ProcessBuilder("xcodegen", "generate")
            .directory(File(repoRoot, "iosApp"))
            .redirectErrorStream(true)
        builder.environment().putAll(environment)
        val process = try {
            builder.start()
        } catch (_: java.io.IOException) {
            throw GradleException("xcodegen is not on PATH — install it with `brew install xcodegen`")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val status = process.waitFor()
        logger.lifecycle(output.trim())
        if (status != 0) throw GradleException("`xcodegen generate` failed with exit code $status")

        // XcodeGen does not report an unset variable, it just writes the reference through — so the
        // generated project is the only place a missing or mistyped key shows up. Left unchecked it
        // would reach a device as an app literally named "${APP_NAME}".
        val generated = listOf(
            File(repoRoot, "iosApp/iosApp.xcodeproj/project.pbxproj"),
            File(repoRoot, "iosApp/iosApp/Info.plist"),
        )
        val leftovers = generated.filter { it.isFile }.flatMap { file ->
            file.readLines().withIndex().filter { (_, line) ->
                unexpandedReference.containsMatchIn(line)
            }.map { (index, line) -> "  ${file.name}:${index + 1}: ${line.trim()}" }
        }
        if (leftovers.isNotEmpty()) {
            throw GradleException(
                "XcodeGen left references unexpanded, so a key is missing from $identityFiles:\n" +
                    leftovers.joinToString("\n")
            )
        }
    }
}

true
