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

// Phase-0 KMP spike module. Deliberately does NOT use the Android-only convention
// plugins (they force every module to `com.android.library`, which AGP 9 no longer
// combines with Kotlin Multiplatform). It applies the Kotlin Multiplatform plugin plus
// the dedicated `com.android.kotlin.multiplatform.library` plugin so it can compile for
// both Android and iOS. See wiki/KMP_FEASIBILITY.md.
plugins {
    // Applied by id without a version: both the Kotlin Gradle plugin and AGP are already on
    // the build classpath via the `build-logic` included build, so a versioned request
    // conflicts ("already on the classpath with an unknown version").
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    // Compose Multiplatform — used here for compose-resources (Phase 3a: KMP string/resource
    // resolution). The compose compiler plugin is bundled with Kotlin, so applied by id.
    alias(libs.plugins.jetbrains.compose)
    id("org.jetbrains.kotlin.plugin.compose")
    // @Serializable Nav3 route keys (type-safe navigation, Phase 3c prototype).
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    // AGP 9's KMP-aware Android target (replaces androidTarget() + a `com.android.library`
    // block). Produces an AAR consumable by the existing Android modules. `withHostTest`
    // lets the shared commonTest also run as an Android (JVM) unit test. NB: this block is
    // `android {}`, NOT `androidLibrary {}` — the latter is deprecated as of AGP 9.3.x
    // ("The 'androidLibrary' block is deprecated. Please use 'android' instead").
    android {
        namespace = "eu.europa.ec.shared"
        compileSdk = 37
        minSdk = 29
        withHostTest {}
        // Pin to JVM 17 like every other module (KotlinAndroid convention). Without this the
        // Android target follows the running JDK (e.g. Android Studio's JBR 21), and the app —
        // built at 17 — fails to inline :shared's inline funs (safeLet/safeAsync): "Cannot
        // inline bytecode built with JVM target 21 into bytecode being built with JVM target 17".
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // iOS targets: device (arm64) + Apple-Silicon simulator. Produces a static framework that
    // the iosApp/ Xcode target consumes. iosX64 (Intel-Mac simulator) is intentionally omitted:
    // Apple no longer ships Intel Macs, and our KMP deps (Compose MP 1.11+, androidx.lifecycle
    // 2.11+, and AndroidX libs generally) have stopped publishing iosX64 artifacts.
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "SharedKit"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Coroutines (KMP). `api` because safeAsync exposes Flow / CoroutineDispatcher
            // in its public signature.
            api(libs.kotlinx.coroutines)
            // compose-resources: KMP-shared strings/drawables (Res.string.*), for view-model
            // string resolution and the future Compose Multiplatform UI. The Compose compiler
            // plugin requires the Compose runtime on the classpath; referenced by explicit
            // coordinate (the `compose.runtime` DSL accessor is deprecated in CMP 1.10+).
            implementation(libs.jetbrains.compose.runtime)
            implementation(libs.jetbrains.compose.components.resources)
            // androidx.lifecycle ViewModel (KMP) — base for the shared MviViewModel. `api`
            // so feature view-models that extend MviViewModel see androidx.lifecycle.ViewModel.
            api(libs.androidx.lifecycle.viewmodel)
            // Nav3 (type-safe navigation) prototype: NavKey routes + kotlinx-serialization,
            // the KMP replacement for the legacy string-route + UiSerializer approach.
            api(libs.androidx.navigation3.runtime)
            implementation(libs.kotlinx.serialization.json)
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
