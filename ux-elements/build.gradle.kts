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

// Spike module (SPIKE-PLAN.md §1.1). Same plugin block and android { } block as :shared-ui; no Koin.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

// M5c (OR-2 (c)): the snapshot of the library YAML subset (spec repo commit recorded inside it) is embedded as a Kotlin
// string under build/, so the registry-vs-library test runs on both targets and the spike repo builds without the spec
// repo. Refresh: spike-evidence/M5c/tools/snapshot-library.py.
abstract class SpecSnapshotFixture : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val snapshot: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val text = snapshot.get().asFile.readText()
        check("\"source_commit\"" in text) { "the snapshot must record its source commit" }
        check('$' !in text && "\"\"\"" !in text) { "the snapshot cannot be embedded in a raw string" }
        val q = "\"\"\""
        val dir = outputDir.get().asFile.resolve("eu/europa/ec/ux/elements/fixture").apply { mkdirs() }
        dir.resolve("UxLibrarySnapshot.kt").writeText(
            "// Generated from ux-elements/spec-snapshot/ux-library-subset.json. Do not edit.\n" +
                "package eu.europa.ec.ux.elements.fixture\n\n" +
                "internal object UxLibrarySnapshot {\n    const val JSON: String = $q$text$q\n}\n",
        )
    }
}

val specSnapshotFixture = tasks.register<SpecSnapshotFixture>("specSnapshotFixture") {
    snapshot.set(layout.projectDirectory.file("spec-snapshot/ux-library-subset.json"))
    outputDir.set(layout.buildDirectory.dir("generated/spec-snapshot/kotlin"))
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
