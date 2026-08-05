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

// KMP Compose Multiplatform UI shared by both platforms: composables/theme, compose-resources
// (Res/strings), and the compose-resources-backed StringResolver. Depends on (and re-exports) the
// Compose-free :shared-logic. This is the module that produces the SharedKit iOS framework — it
// exports :shared-logic so iOS gets a single umbrella framework with logic + UI. See
// wiki/KMP_FEASIBILITY.md.
plugins {
    // `id(...)` without a version is REQUIRED for these two — see the note in
    // :shared-logic's build file. They come from kotlin-gradle-plugin / AGP, which are already on
    // the buildscript classpath via `build-logic`, so the version that `alias()` would carry makes
    // the plugin request unsatisfiable. The serialization and Compose plugins below are separate
    // artifacts, not on that classpath, so they can and do use `alias()`.
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    // Compose Multiplatform + the (Kotlin-bundled) Compose compiler plugin.
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose.compiler)
    // @Serializable UI-model data classes (TextConfig, IconDataUi, ContentHeaderConfig, …) that
    // become Nav3 route-argument payloads. Data-class serializers must be generated in this module.
    alias(libs.plugins.kotlin.serialization)
    // Koin annotations for the shared view-models (Phase 3b). Unlike the Android modules, which get
    // this via the `project.android.koin` convention plugin, a KMP module wires it by hand — the
    // convention plugin also adds Android-only artifacts (koin-android, workmanager). Koin 1.1.0's
    // plugin is a *Kotlin compiler* plugin (not KSP) and applies to every compilation, iOS included.
    alias(libs.plugins.koin.compiler)
}

kotlin {
    android {
        namespace = "eu.europa.ec.shared.ui"
        compileSdk = 37
        minSdk = 29
        androidResources {
            enable = true
        }
        withHostTest {}
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // iOS targets + the SharedKit framework. `export(:shared-logic)` makes the logic module's
    // public API (Platform, PinValidator, MVI base, WalletEngine, AppRoute, …) visible in the
    // framework's Swift/Obj-C header, so iosApp consumes one framework for both layers.
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "SharedKit"
            isStatic = true
            export(project(":shared-logic"))
            // Pinned explicitly because the bundle ID can no longer be inferred: the Koin compiler
            // plugin generates its definition hints into `org.koin.plugin.hints`, so this module's
            // classes no longer share one package prefix. Without this the linker warns and falls
            // back to the bundle *name* ("SharedKit").
            binaryOption("bundleId", "eu.europa.ec.shared.ui")
        }
    }

    sourceSets {
        commonMain.dependencies {
            // `api` (not implementation) so the framework can `export` it and downstream modules
            // that depend on :shared-ui also see :shared-logic.
            api(project(":shared-logic"))
            // `api`, not `implementation`: both types surface in this module's public API —
            // `StringResource`/`PluralStringResource` in `StringCatalog` and `UiText`, and
            // `@Composable` on `UiText.resolve()`. Consumers (:resources-logic, :ui-logic, the
            // feature modules) name those types directly.
            api(libs.jetbrains.compose.runtime)
            api(libs.jetbrains.compose.components.resources)
            implementation(libs.kotlinx.serialization.json)
            // Nav3 route model (AppRoute : NavKey, AppNavigator) lives here: config-carrying routes
            // reference the shared-ui UI-model, and AppRoute is sealed so its subtypes must co-locate.
            api(libs.androidx.navigation3.runtime)
            // DI for the shared view-models. `api` because downstream Android modules resolve them
            // (SharedUiModule is referenced from assembly-logic's @KoinApplication) and because the
            // Koin compiler plugin generates `module { viewModelOf(...) }` bodies into this module's
            // compilations, which need koin-core + the KMP viewmodel DSL on the compile classpath.
            api(libs.koin.core)
            api(libs.koin.core.viewmodel)
            api(libs.koin.annotations)
        }
        // Compose Multiplatform UI, iOS-only for now. This is the spike that proves the shared
        // view-models can drive Compose UI on iOS (Skiko rendering, Koin on native, compose-resources
        // at render time). Kept OUT of commonMain deliberately: on Android these artifacts map onto
        // AndroidX Compose, so they would join the shipping app's classpath next to its own Compose
        // BOM. Widen to commonMain when the real screens move, and revisit versions then.
        // Via the Compose plugin's accessors rather than the version catalog: these artifacts do not
        // all track `composeMultiplatform` (material3 has its own version line, so
        // org.jetbrains.compose.material3:material3:1.11.1 does not exist). The plugin resolves a
        // self-consistent set.
        iosMain.dependencies {
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "eu.europa.ec.shared.resources"
}
