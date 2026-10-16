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

kotlin {
    android {
        namespace = "eu.europa.ec.ux.contract"
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

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain {
            // PROVISIONAL (boundary §6.3): the hand copy of the contract, until ux-spec/ brings the generated one (M6
            // deletes the directory and this conditional).
            if (!rootProject.file("ux-spec/UX_SPEC.lock").exists()) kotlin.srcDir("provisional/kotlin")
            dependencies {
                api(libs.jetbrains.compose.runtime)
                api(libs.jetbrains.compose.components.resources)
                api(libs.kotlinx.serialization.json)
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "eu.europa.ec.ux.contract.resources"
}
