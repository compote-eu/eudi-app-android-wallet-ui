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

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest

// Spike module (SPIKE-PLAN.md §1.1). Same plugin block and android { } block as :shared-ui; no Koin.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

// M5c (OR-2 (c)), M6c: the library data of the export (ux-spec/resources/files/ux-library.json, spec commit recorded
// inside it; was the M5c snapshot of the library YAML subset) is embedded as a Kotlin string under build/, so the
// registry-vs-library test runs on both targets. The task refuses a file whose sha256 is not UX_SPEC.lock's.
abstract class SpecSnapshotFixture : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val snapshot: RegularFileProperty

    @get:Input
    abstract val expectedSha256: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val bytes = snapshot.get().asFile.readBytes()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(sha == expectedSha256.get()) { "ux-library.json sha256 is $sha, UX_SPEC.lock says ${expectedSha256.get()}" }
        val text = bytes.decodeToString()
        check("\"source_commit\"" in text) { "the snapshot must record its source commit" }
        check('$' !in text && "\"\"\"" !in text) { "the snapshot cannot be embedded in a raw string" }
        val q = "\"\"\""
        val dir = outputDir.get().asFile.resolve("eu/europa/ec/ux/elements/fixture").apply { mkdirs() }
        dir.resolve("UxLibrarySnapshot.kt").writeText(
            "// Generated from ux-spec/resources/files/ux-library.json. Do not edit.\n" +
                "package eu.europa.ec.ux.elements.fixture\n\n" +
                "internal object UxLibrarySnapshot {\n    const val JSON: String = $q$text$q\n}\n",
        )
    }
}

val specSnapshotFixture = tasks.register<SpecSnapshotFixture>("specSnapshotFixture") {
    snapshot.set(rootProject.layout.projectDirectory.file("ux-spec/resources/files/ux-library.json"))
    expectedSha256.set(
        providers.fileContents(rootProject.layout.projectDirectory.file("ux-spec/UX_SPEC.lock")).asText.map { lock ->
            val entry = Regex("""\{[^{}]*"path": "resources/files/ux-library.json"[^{}]*}""").find(lock)?.value
                ?: error("UX_SPEC.lock lists no resources/files/ux-library.json")
            Regex(""""sha256": "([0-9a-f]{64})"""").find(entry)!!.groupValues[1]
        },
    )
    outputDir.set(layout.buildDirectory.dir("generated/spec-snapshot/kotlin"))
}

// M5e: the `comp.*` values of the parts the Home screen draws (ux-spec/resources/files/ux-tokens.json: type and value of
// the groups below, per brand and mode) as a Kotlin string under build/, so the part tests read the export's own values on
// both targets. The task refuses a file whose sha256 is not UX_SPEC.lock's. Chunked: a string constant has a size limit.
abstract class TokensFixture : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val tokens: RegularFileProperty

    @get:Input
    abstract val expectedSha256: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val assets: RegularFileProperty

    @get:Input
    abstract val expectedAssetsSha256: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    @Suppress("UNCHECKED_CAST")
    fun generate() {
        val bytes = tokens.get().asFile.readBytes()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(sha == expectedSha256.get()) { "ux-tokens.json sha256 is $sha, UX_SPEC.lock says ${expectedSha256.get()}" }
        val root = groovy.json.JsonSlurper().parseText(bytes.decodeToString()) as Map<String, Any?>
        val groups = listOf("app-bar", "attribute-item", "bottom-bar", "document-card", "rp-identity", "screen", "segmented-switch", "status-chip", "trust-mark")
            .map { "comp.$it." }
        val values = (root["values"] as Map<String, Map<String, Map<String, Any?>>>).mapValues { (_, modes) ->
            modes.mapValues { (_, all) ->
                all.filterKeys { key -> groups.any { key.startsWith(it) } }
                    .mapValues { (_, token) -> (token as Map<String, Any?>).filterKeys { it == "type" || it == "value" } }
            }
        }
        val text = groovy.json.JsonOutput.toJson(mapOf("values" to values))
        check('$' !in text && "\"\"\"" !in text) { "the tokens cannot be embedded in a raw string" }
        val q = "\"\"\""
        val body = text.chunked(CHUNK).joinToString(",\n") { "        $q$it$q" }
        val dir = outputDir.get().asFile.resolve("eu/europa/ec/ux/elements/fixture").apply { mkdirs() }
        val assetBytes = assets.get().asFile.readBytes()
        val assetSha = MessageDigest.getInstance("SHA-256").digest(assetBytes).joinToString("") { "%02x".format(it) }
        check(assetSha == expectedAssetsSha256.get()) { "ux-assets.json sha256 is $assetSha, UX_SPEC.lock says ${expectedAssetsSha256.get()}" }
        dir.resolve("UxAssetsSnapshot.kt").writeText(
            "// Generated from ux-spec/resources/files/ux-assets.json (sha256 $assetSha). Do not edit.\n" +
                "package eu.europa.ec.ux.elements.fixture\n\n" +
                "internal object UxAssetsSnapshot {\n    const val JSON: String = $q${assetBytes.decodeToString()}$q\n}\n",
        )
        dir.resolve("UxTokensSnapshot.kt").writeText(
            "// Generated from ux-spec/resources/files/ux-tokens.json (sha256 $sha). Do not edit.\n" +
                "package eu.europa.ec.ux.elements.fixture\n\n" +
                "internal object UxTokensSnapshot {\n    val JSON: String = listOf(\n$body\n    ).joinToString(\"\")\n}\n",
        )
    }

    private companion object {
        const val CHUNK = 16_000
    }
}

val tokensFixture = tasks.register<TokensFixture>("tokensFixture") {
    tokens.set(rootProject.layout.projectDirectory.file("ux-spec/resources/files/ux-tokens.json"))
    expectedSha256.set(
        providers.fileContents(rootProject.layout.projectDirectory.file("ux-spec/UX_SPEC.lock")).asText.map { lock ->
            val entry = Regex("""\{[^{}]*"path": "resources/files/ux-tokens.json"[^{}]*}""").find(lock)?.value
                ?: error("UX_SPEC.lock lists no resources/files/ux-tokens.json")
            Regex(""""sha256": "([0-9a-f]{64})"""").find(entry)!!.groupValues[1]
        },
    )
    assets.set(rootProject.layout.projectDirectory.file("ux-spec/resources/files/ux-assets.json"))
    expectedAssetsSha256.set(
        providers.fileContents(rootProject.layout.projectDirectory.file("ux-spec/UX_SPEC.lock")).asText.map { lock ->
            val entry = Regex("""\{[^{}]*"path": "resources/files/ux-assets.json"[^{}]*}""").find(lock)?.value
                ?: error("UX_SPEC.lock lists no resources/files/ux-assets.json")
            Regex(""""sha256": "([0-9a-f]{64})"""").find(entry)!!.groupValues[1]
        },
    )
    outputDir.set(layout.buildDirectory.dir("generated/tokens-snapshot/kotlin"))
}

kotlin {
    android {
        namespace = "eu.europa.ec.ux.elements"
        compileSdk = 37
        minSdk = 29
        withHostTest {
            isIncludeAndroidResources = true
        }
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":ux-theme"))
            api(libs.jetbrains.compose.runtime)
            api(libs.jetbrains.compose.foundation)
            implementation(libs.jetbrains.compose.ui)
            // M8r: EL-rp-identity `logo` (a remote image URL) through the app's own image loader, Coil 3 (multiplatform,
            // already in the catalog and in :shared-ui). The network fetchers come from the app: okhttp on Android
            // (convention plugin), ktor3 on iOS (:shared-ui iosMain).
            implementation(libs.coil.kt.compose)
        }
        commonTest {
            kotlin.srcDir(specSnapshotFixture.map { it.outputDir })
            kotlin.srcDir(tokensFixture.map { it.outputDir })
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.serialization.json)
                // Direct coordinate, no catalog touch (SPIKE-PLAN §1.1).
                implementation("org.jetbrains.compose.ui:ui-test:${libs.versions.composeMultiplatform.get()}")
            }
        }
        // runComposeUiTest on the Android host needs Robolectric (the precedent of :ux-runtime's P4 round trip).
        getByName("androidHostTest").dependencies {
            implementation(libs.robolectric)
            // Registers the ComponentActivity that ActivityScenario launches (the artifact has no catalog entry with a version).
            implementation("androidx.compose.ui:ui-test-manifest:1.12.1")
        }
    }
}

// Robolectric 4.17+ reaches jdk.internal.access.SharedSecrets for the SDK 37+ application environment (the same flag
// the convention plugin gives every Android unit-test task, KotlinAndroid.kt).
tasks.withType<Test>().configureEach {
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
}
