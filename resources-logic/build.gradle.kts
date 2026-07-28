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

import com.android.build.api.dsl.LibraryExtension
import project.convention.logic.config.LibraryModule

plugins {
    id("project.android.library")
    id("project.android.library.compose")
}

extensions.configure<LibraryExtension>("android") {
    namespace = "eu.europa.ec.resourceslogic"

    // The default theme (ThemeColors/ThemeTypography/ThemeShapes) lives in a shared source
    // directory used by every flavor EXCEPT `sk`, which overrides it under src/sk. Kotlin
    // classes cannot be overridden per source set while also present in `main` (duplicate
    // class), so these files are removed from `main` and added here for all non-sk flavors.
    // See wiki/SK_THEME.md.
    for (flavor in productFlavors.names) {
        if (flavor != "sk") {
            sourceSets.getByName(flavor).kotlin.directories.add("src/defaultTheme/java")
        }
    }
}

moduleConfig {
    module = LibraryModule.ResourcesLogic
}

dependencies {
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.material.icons.extended)
    api(libs.androidx.compose.material3.windowSizeClass)
    api(libs.material)
}