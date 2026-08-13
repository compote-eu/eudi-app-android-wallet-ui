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
// See wiki/KMP_FEASIBILITY.md.
plugins {
    // These two MUST stay `id(...)` without a version, and cannot become `alias(...)`: they ship
    // inside kotlin-gradle-plugin / AGP, which `build-logic` puts on the buildscript classpath, and
    // `alias()` always carries the catalog's version. Requesting a version for a plugin already on
    // the classpath fails: "the plugin is already on the classpath with an unknown version, so
    // compatibility cannot be checked". Everything else here uses `alias()`.
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    // @Serializable Nav3 route keys (kotlinx.serialization core is KMP; no Compose involved).
    alias(libs.plugins.kotlin.serialization)
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
            // `api`: LocalDateTime is part of the filter framework's public surface
            // (FilterElement.DateTimeRangeFilterItem / FilterValidator.updateDateFilter), so
            // consumers in other modules must see the type.
            api(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
        }
        // iOS-only: the document layer reads multipaz's DocumentStore directly, because there is no
        // KMP build of eudi-lib-android-wallet-core or of the document manager (see the three-way
        // split in wiki/KMP_FEASIBILITY.md — multipaz IS fully KMP, the EUDI wrappers are not).
        // Deliberately NOT in commonMain: Android reaches the very same multipaz through wallet-core,
        // so putting it in commonMain would add a second version *request* to the app's classpath and
        // couple half the Android app to multipaz for no gain. `implementation`, since no multipaz
        // type appears in this module's public API — the seam is WalletDocument/WalletEngine.
        iosMain.dependencies {
            implementation(libs.multipaz)
            // Ktor's Darwin engine, for fetching status-list tokens. `ktor-client-core` already
            // arrives transitively with multipaz (which uses it itself), so this adds the iOS engine
            // and nothing else — checked with `:shared-logic:dependencies`.
            implementation(libs.ktor.client.darwin)
        }
        iosTest.dependencies {
            // The mock HTTP engine for `MultipazRevocationCheckerTest`; everything else it needs
            // (multipaz's StatusList, crypto, ephemeral storage) is already on the iOS classpath.
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            // `api`, not implementation: these types ARE the androidMain platform handles (actual
            // typealiases), so they appear in this module's public Android signatures —
            // ComponentActivity is `PlatformActivity` and BiometricPrompt.CryptoObject is
            // `PlatformCryptoObject`. Plain `activity`, never activity-compose: this module stays
            // Compose-UI-free.
            api(libs.androidx.activity)
            api(libs.androidx.biometric)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
