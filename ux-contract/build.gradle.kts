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

// M9r regen-smoke: -PuxSpecDir=<dir> points this build at a scratch copy of an export (default: the tracked ux-spec/).
val uxSpec: Directory = rootProject.layout.projectDirectory.dir(providers.gradleProperty("uxSpecDir").getOrElse("ux-spec"))
// ...and keeps that build apart from the tracked one (its own build directory), so the two never clobber each other.
if (providers.gradleProperty("uxSpecDir").isPresent) layout.buildDirectory.set(layout.projectDirectory.dir("build/regen-smoke"))

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
            // boundary §3: the generated contract (ux-spec/ is exporter-only, never edited here).
            kotlin.srcDir(uxSpec.dir("contract/kotlin"))
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
    // boundary §3 / V29: the export's resources (values/strings.xml, files/<flow>.bundle.json) are this module's
    // commonMain compose resources; the Res package stays eu.europa.ec.ux.contract.resources.
    customDirectory("commonMain", provider { uxSpec.dir("resources") })
}

// M9r: the host tests that read the export from disk read the same directory the build embeds.
tasks.withType<Test>().configureEach {
    systemProperty("ux.spec.dir", uxSpec.asFile.absolutePath)
}
