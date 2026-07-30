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
        }
    }

    sourceSets {
        commonMain.dependencies {
            // `api` (not implementation) so the framework can `export` it and downstream modules
            // that depend on :shared-ui also see :shared-logic.
            api(project(":shared-logic"))
            implementation(libs.jetbrains.compose.runtime)
            implementation(libs.jetbrains.compose.components.resources)
            implementation(libs.kotlinx.serialization.json)
            // Nav3 route model (AppRoute : NavKey, AppNavigator) lives here: config-carrying routes
            // reference the shared-ui UI-model, and AppRoute is sealed so its subtypes must co-locate.
            api(libs.androidx.navigation3.runtime)
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
