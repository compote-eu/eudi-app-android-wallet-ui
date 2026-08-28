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
            // `NavDisplay`, so that `AppNavDisplay` — the host body both platforms compose — can live
            // in commonMain. It comes from JetBrains' multiplatform build of navigation3-ui because
            // androidx publishes navigation3-*runtime* for iOS but not navigation3-*ui*. This is safe
            // to declare commonly rather than per platform: the fork's own Android variant depends on
            // `androidx.navigation3:navigation3-ui`, so Android still resolves Google's build and only
            // the native targets take the fork's own code.
            implementation(libs.jetbrains.navigation3.ui)
            // `LocalViewModelStoreOwner` / `rememberViewModelStoreProvider`, and the per-entry
            // ViewModelStore decorator that clears a store when its entry is popped. Both artifacts
            // are genuinely multiplatform (common + androidJvm + native), on the same 2.11.0 line as
            // the `lifecycle-viewmodel` already here.
            implementation(libs.androidx.lifecycle.viewModelCompose)
            implementation(libs.androidx.lifecycle.viewmodel.navigation3)
            // DI for the shared view-models. `api` because downstream Android modules resolve them
            // (SharedUiModule is referenced from assembly-logic's @KoinApplication) and because the
            // Koin compiler plugin generates `module { viewModelOf(...) }` bodies into this module's
            // compilations, which need koin-core + the KMP viewmodel DSL on the compile classpath.
            api(libs.koin.core)
            api(libs.koin.core.viewmodel)
            // `koinViewModel()` for commonMain, so a shared `entry<Route> { }` can resolve a
            // view-model the same way on both platforms. The definitions are already common:
            // the shared view-models carry `@KoinViewModel` and SharedUiModule scans for them.
            api(libs.koin.compose.viewmodel)
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
            // Compose Multiplatform's own `@Preview`, so a shared component can keep its previews in
            // commonMain. `api`, since `ThemeModePreviews` is annotated with it and every component's
            // preview functions carry that annotation. NB it has neither `uiMode` nor
            // `backgroundColor` — the light/dark preview *pairs* the Android-only annotation used to
            // generate are not expressible here; see ThemePreviews.kt.
            //
            // ⚠️ Deliberately the `org.jetbrains.compose.ui:ui-tooling-preview` module and **not** the
            // `compose.components.uiToolingPreview` accessor this used to use. They are different
            // artifacts: the accessor resolves to
            // `org.jetbrains.compose.components:components-ui-tooling-preview`, whose
            // `org.jetbrains.compose.ui.tooling.preview` package is deprecated in favour of the
            // androidx-named one this module provides. Switching the accessor back re-deprecates every
            // preview annotation in commonMain — 18 warnings' worth.
            api(libs.jetbrains.compose.ui.tooling.preview)
            // The seven Material `ImageVector` icons `AppIconResolvers` falls back to for keys with no
            // drawable of their own (ArrowBack, Close, KeyboardArrow*, DateRange, Info). Via the
            // Compose plugin accessor, since androidx's `material-icons-extended` is Android-only.
            api(compose.materialIconsExtended)
            // Coil 3 is multiplatform — `coil`, `coil-compose` and `coil-svg` all publish iOS
            // variants (checked against Maven Central, not just the local cache) — which is what lets
            // `WrapAsyncImage` be shared. `api`, since `AsyncImage`'s types appear in its signature.
            // The Android modules keep getting these from the `AndroidCompose` convention plugin.
            api(libs.coil.kt)
            api(libs.coil.kt.compose)
            api(libs.coil.kt.svg)
            // `collectAsStateWithLifecycle` — multiplatform since lifecycle 2.11, so the shared screens
            // keep Android's lifecycle-aware collection rather than downgrading to `collectAsState`.
            api(libs.androidx.lifecycle.runtimeCompose)
        }
        // Android-only: the platform half of the seams whose iOS half Compose Multiplatform supplies
        // directly. `androidx.activity.compose.BackHandler` is the Android answer for
        // `PlatformBackHandler` (CMP's own `androidx.compose.ui.backhandler` exists for iOS but not
        // for Android, where CMP maps onto Google's Compose artifacts). Unlike :shared-logic, which
        // deliberately takes plain `activity` only, this module already *is* the Compose UI layer.
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            // Runtime BLE permissions for the proximity flow — the Android half of
            // `EnsureProximityPermissions`. Android-only by nature: iOS asks for Bluetooth on first
            // CoreBluetooth use rather than up front.
            implementation(libs.accompanist.permissions)
            // The pre-API-31 blur fallback behind `sensitiveContentBlur`. Android-only library; iOS
            // blurs through Skia with no fallback needed.
            implementation(libs.compose.cloudy)
            // The QR *encoder* behind `rememberQrPainter`, which the proximity screen shows for device
            // engagement, and the *decoder* behind `QrCodeAnalyzer`, which reads one off the camera.
            // Android-only on purpose: iOS does both with system frameworks — CoreImage to draw,
            // AVFoundation to read — so neither platform carries the other's library.
            implementation(libs.zxing)
            // CameraX, behind the Android half of `QrCameraSurface`. iOS's half is AVFoundation, which
            // needs no dependency at all.
            implementation(libs.androidx.camera.core)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.view)
            implementation(libs.androidx.camera.camera2)
            // The RQES signing SDK, behind the Android half of `rememberDocumentSignTrigger`. It
            // brings its own UI, which is why the shared screen stops at handing over a document.
            // iOS's half is the Swift package `EudiRQESUi`, declared in `iosApp/project.yml` — the
            // Kotlin side there cannot depend on it, so neither platform carries the other's.
            implementation(libs.rqes.ui.sdk)
        }
        // No iosMain.dependencies block: iOS adds nothing of its own any more. The Compose UI
        // artifacts moved to commonMain with the first shared screen, and the navigation host's
        // pieces followed when `AppNavDisplay` became shared.
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        // Holds the Android half of `testPlatformContext()` / `testPlatformIntent()` — the test-only
        // factories that let commonTest reach view-model paths whose events carry a `PlatformContext`
        // or `PlatformIntent`. Mockito lives here and only here, because `android.content.Context` is
        // abstract and needs a mock, and Mockito is JVM-only so common code cannot reference it.
        // The tests themselves are in commonTest and run on BOTH targets; put a test here only if it
        // asserts on what a real Android Context or Intent *does*, rather than passing one through.
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
