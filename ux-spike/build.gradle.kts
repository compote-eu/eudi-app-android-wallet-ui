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

// M5e: the export's screen probe and the data files the elements read, embedded as Kotlin strings under build/ so the probe
// tests run on both targets (a test cannot read ux-spec/ from disk on iOS). Each file's sha256 must be UX_SPEC.lock's.
abstract class ProbeFixture : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val files: ConfigurableFileCollection

    @get:Input
    abstract val expected: MapProperty<String, String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val q = "\"\"\""
        val entries = files.files.sortedBy { it.name }.map { f ->
            val bytes = f.readBytes()
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            check(sha == expected.get()[f.name]) { "${f.name} sha256 is $sha, UX_SPEC.lock says ${expected.get()[f.name]}" }
            val text = bytes.decodeToString()
            check('$' !in text && q !in text) { "${f.name} cannot be embedded in a raw string" }
            val body = text.chunked(CHUNK).joinToString(",\n") { "            $q$it$q" }
            f.name to "        val ${f.name.substringBefore('.').substringAfter('-').uppercase().replace('-', '_')}: String = listOf(\n$body\n        ).joinToString(\"\")"
        }
        val dir = outputDir.get().asFile.resolve("eu/europa/ec/uxspike/fixture").apply { mkdirs() }
        dir.resolve("ProbeFixture.kt").writeText(
            "// Generated from ux-spec/resources/files (${entries.joinToString { it.first }}). Do not edit.\n" +
                "package eu.europa.ec.uxspike.fixture\n\ninternal object ProbeFixture {\n    object Files {\n" +
                entries.joinToString("\n") { it.second } + "\n    }\n}\n",
        )
    }

    private companion object {
        const val CHUNK = 16_000
    }
}

fun lockedSha256(path: String): Provider<String> =
    providers.fileContents(rootProject.layout.projectDirectory.file("ux-spec/UX_SPEC.lock")).asText.map { lock ->
        val entry = Regex("""\{[^{}]*"path": "${Regex.escape(path)}"[^{}]*}""").find(lock)?.value ?: error("UX_SPEC.lock lists no $path")
        Regex(""""sha256": "([0-9a-f]{64})"""").find(entry)!!.groupValues[1]
    }

val probeFixture = tasks.register<ProbeFixture>("probeFixture") {
    val dir = rootProject.layout.projectDirectory.dir("ux-spec/resources/files")
    val names = dir.asFile.listFiles().orEmpty().map { it.name }
        .filter { it.endsWith(".probe.json") || it in listOf("ux-library.json", "ux-assets.json", "ux-tokens.json") }
    files.from(names.map { dir.file(it) })
    expected.set(providers.provider { names.associateWith { n -> lockedSha256("resources/files/$n").get() } })
    outputDir.set(layout.buildDirectory.dir("generated/probe-fixture/kotlin"))
}

kotlin {
    android {
        namespace = "eu.europa.ec.uxspike"
        compileSdk = 37
        minSdk = 29
        androidResources {
            enable = true
        }
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
            api(project(":ux-runtime"))
            api(project(":ux-elements"))
            api(project(":ux-theme"))
            api(project(":ux-contract"))
            api(libs.androidx.navigation3.runtime)
            api(libs.jetbrains.compose.runtime)
            api(libs.jetbrains.compose.foundation)
            implementation(libs.jetbrains.compose.ui)
            implementation(libs.jetbrains.compose.components.resources)
            implementation(libs.kotlinx.coroutines)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest {
            kotlin.srcDir(probeFixture.map { it.outputDir })
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                // M5e: Compose UI tests of the probe through the generic host, on both targets (the :ux-runtime precedent).
                implementation("org.jetbrains.compose.ui:ui-test:${libs.versions.composeMultiplatform.get()}")
            }
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.robolectric)
            implementation("androidx.compose.ui:ui-test-manifest:1.12.1")
        }
    }
}

// Robolectric 4.17+ reaches jdk.internal.access.SharedSecrets for the SDK 37+ application environment (as in :ux-runtime).
tasks.withType<Test>().configureEach {
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
}

// boundary §3 / Q14: the fake engine plays the exported scripts (ux-spec/fixtures/scripts, read as data) as
// files/scripts/<name>.json, beside this module's own resources (the M5d Home-probe fragments). ux-spec/ stays
// exporter-only: the scripts are copied into build/, never edited.
val uxSpikeResources = tasks.register<Sync>("uxSpikeComposeResources") {
    from("src/commonMain/composeResources")
    from(rootProject.layout.projectDirectory.dir("ux-spec/fixtures/scripts")) { into("files/scripts") }
    // M5e: the export's screen probes (`<screen id>.probe.json`) as files/probes/<name>.probe.json: the name is what follows the
    // id's first dash, so no screen id is spelled here or in the Kotlin that reads them.
    from(rootProject.layout.projectDirectory.dir("ux-spec/resources/files")) {
        include("*.probe.json")
        into("files/probes")
        rename { it.substringAfter('-') }
    }
    into(layout.buildDirectory.dir("generated/ux-spike-compose-resources"))
}

compose.resources {
    packageOfResClass = "eu.europa.ec.uxspike.resources"
    customDirectory("commonMain", layout.dir(uxSpikeResources.map { it.destinationDir }))
}

