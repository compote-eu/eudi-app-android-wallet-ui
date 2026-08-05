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
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    // Compose Multiplatform + the (Kotlin-bundled) Compose compiler plugin.
    alias(libs.plugins.jetbrains.compose)
    id("org.jetbrains.kotlin.plugin.compose")
    // @Serializable UI-model data classes (TextConfig, IconDataUi, ContentHeaderConfig, …) that
    // become Nav3 route-argument payloads. Data-class serializers must be generated in this module.
    id("org.jetbrains.kotlin.plugin.serialization")
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
