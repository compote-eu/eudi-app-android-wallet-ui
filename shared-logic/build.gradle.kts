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

// KMP business/presentation LOGIC shared by both platforms — deliberately Compose-UI-free
// (no compose-ui/foundation/material on the classpath) so a future partial native-SwiftUI iOS
// path can consume it directly. The Compose Multiplatform UI lives in the sibling :shared-ui.
// See wiki/KMP_FEASIBILITY.md. Plugins are applied by id without a version: Kotlin/AGP are
// already on the build classpath via the `build-logic` included build.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    // @Serializable Nav3 route keys (kotlinx.serialization core is KMP; no Compose involved).
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    // AGP 9's KMP-aware Android target. NB: `android {}`, NOT `androidLibrary {}` — the latter is
    // deprecated as of AGP 9.3.x ("Please use 'android' instead").
    android {
        namespace = "eu.europa.ec.shared"
        compileSdk = 37
        minSdk = 29
        withHostTest {}
        // Pin to JVM 17 like every other module so the app (built at 17) can inline this module's
        // inline funs (safeLet/safeAsync).
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // iOS targets: device (arm64) + Apple-Silicon simulator. No framework binary here — this is a
    // library (klib) consumed by :shared-ui, whose SharedKit framework re-exports it. iosX64
    // (Intel-Mac simulator) is intentionally omitted (Apple ships only Apple Silicon).
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // `api` where the type appears in public signatures (safeAsync exposes Flow/Dispatcher;
            // MviViewModel extends androidx.lifecycle.ViewModel; AppRoute uses NavKey).
            api(libs.kotlinx.coroutines)
            api(libs.androidx.lifecycle.viewmodel)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
