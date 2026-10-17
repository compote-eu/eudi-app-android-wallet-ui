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
        namespace = "eu.europa.ec.uxspike"
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
        commonMain.dependencies {
            api(project(":ux-runtime"))
            api(project(":ux-elements"))
            api(project(":ux-theme"))
            api(project(":ux-contract"))
            api(libs.androidx.navigation3.runtime)
            api(libs.jetbrains.compose.runtime)
            api(libs.jetbrains.compose.foundation)
            implementation(libs.jetbrains.compose.ui)
            implementation(libs.jetbrains.compose.components.resources)
            implementation(libs.kotlinx.coroutines)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// boundary §3 / Q14: the fake engine plays the exported scripts (ux-spec/fixtures/scripts, read as data) as
// files/scripts/<name>.json, beside this module's own resources (the M5d Home-probe fragments). ux-spec/ stays
// exporter-only: the scripts are copied into build/, never edited.
val uxSpikeResources = tasks.register<Sync>("uxSpikeComposeResources") {
    from("src/commonMain/composeResources")
    from(rootProject.layout.projectDirectory.dir("ux-spec/fixtures/scripts")) { into("files/scripts") }
    into(layout.buildDirectory.dir("generated/ux-spike-compose-resources"))
}

compose.resources {
    packageOfResClass = "eu.europa.ec.uxspike.resources"
    customDirectory("commonMain", layout.dir(uxSpikeResources.map { it.destinationDir }))
}

