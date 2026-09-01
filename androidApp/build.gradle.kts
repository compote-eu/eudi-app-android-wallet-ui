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

import project.convention.logic.AppBuildType
import project.convention.logic.config.LibraryModule
import project.convention.logic.getProperty

plugins {
    id("project.android.application")
    id("project.android.application.compose")
}

android {

    signingConfigs {
        create("release") {

            storeFile = file("${rootProject.projectDir}/sign")

            keyAlias = getProperty("androidKeyAlias") ?: System.getenv("ANDROID_KEY_ALIAS")
            keyPassword = getProperty("androidKeyPassword") ?: System.getenv("ANDROID_KEY_PASSWORD")
            storePassword =
                getProperty("androidKeyPassword") ?: System.getenv("ANDROID_KEY_PASSWORD")

            enableV2Signing = true
        }
    }

    defaultConfig {
        // From app.properties, which iOS's project.yml reads as `${APP_ID}` for its bundle id, so
        // both platforms claim one identity from one line. The per-flavour suffix is applied by
        // build-logic's `configureFlavors`, from the same file.
        applicationId = requireNotNull(getProperty<String>("APP_ID", "app.properties")) {
            "APP_ID is missing from app.properties"
        }
        // From version.properties, the file that also carries VERSION_NAME, so a release pipeline
        // moves Android's versionCode and iOS's CFBundleVersion with one edit. See that file: this
        // makes fastlane's `increment_version_code` lane — which rewrites the literal that used to be
        // here — inert on this fork.
        versionCode = requireNotNull(getProperty<String>("VERSION_CODE", "version.properties")) {
            "VERSION_CODE is missing from version.properties"
        }.toInt()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            applicationIdSuffix = AppBuildType.DEBUG.applicationIdSuffix
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            applicationIdSuffix = AppBuildType.RELEASE.applicationIdSuffix
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Deliberately a literal, not APP_ID: this is the R/BuildConfig package, it is never suffixed,
    // and moving it would move generated classes. See app.properties' closing note.
    namespace = "eu.europa.ec.euidi"
}

dependencies {
    implementation(project(LibraryModule.AssemblyLogic.path))
    "baselineProfile"(project(LibraryModule.BaselineProfileLogic.path))
}
