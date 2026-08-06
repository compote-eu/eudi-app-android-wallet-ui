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
            // Compose Multiplatform UI, for the shared screens. On Android these map onto the same
            // androidx.compose artifacts the app already uses, so they add version *requests* that
            // Gradle reconciles with the app's Compose BOM rather than a parallel Compose — verified
            // by diffing :androidApp's resolved compose graph before and after this widening: the
            // resolved versions did not move (everything still converges on the BOM's 1.11.4, which is
            // already how the app reconciles requests from 1.2.1 through 1.11.2).
            // `api`, since these types appear in shared composables' public signatures (Modifier, …).
            // Consumed through the Compose plugin's accessors, not the catalog: the versions do not all
            // track `composeMultiplatform` (org.jetbrains.compose.material3:material3:1.11.1 does not
            // exist).
            api(compose.foundation)
            api(compose.material3)
            api(compose.ui)
            api(compose.animation)
            // `collectAsStateWithLifecycle` — multiplatform since lifecycle 2.11, so the shared screens
            // keep Android's lifecycle-aware collection rather than downgrading to `collectAsState`.
            api(libs.androidx.lifecycle.runtimeCompose)
        }
        // iOS-only: the navigation host's pieces. The Compose UI artifacts moved to commonMain with
        // the first shared screen.
        iosMain.dependencies {
            // For `LocalViewModelStoreOwner`, which the iOS navigation host provides per back-stack
            // entry — the job `rememberViewModelStoreNavEntryDecorator` does on Android. Same 2.11.0
            // version line as the KMP `lifecycle-viewmodel` already in commonMain.
            implementation(libs.androidx.lifecycle.viewModelCompose)
            // The real `NavDisplay`, from JetBrains' multiplatform build of navigation3-ui — androidx
            // publishes navigation3-runtime for iOS but not navigation3-ui.
            implementation(libs.jetbrains.navigation3.ui)
            // Per-entry ViewModelStore decorator, as the Android host uses.
            implementation(libs.androidx.lifecycle.viewmodel.navigation3)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        // Android-only tests, for the handful of assertions that CANNOT be written in common code:
        // anything needing an inhabited `PlatformContext` or `PlatformIntent`. Both are `expect class`
        // with no common constructor — and on iOS they are deliberately uninhabited — so a view-model
        // path that takes one can only be driven where the actual type is the real Android class.
        // Mockito is needed because `android.content.Context` is abstract; `Intent` is instantiable
        // directly. Everything else stays in commonTest so it runs on both targets.
        // No generated accessor for this one (unlike commonTest/iosMain), so it is named explicitly.
        getByName("androidHostTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.mockito.core)
            implementation(libs.mockito.kotlin)
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "eu.europa.ec.shared.resources"
}
