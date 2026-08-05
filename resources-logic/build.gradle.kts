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
}

moduleConfig {
    module = LibraryModule.ResourcesLogic
}

dependencies {
    // Phase 3a: `ResourceProvider`'s string methods take compose-resources `StringResource` and
    // delegate to the shared `StringCatalog`, so the shared corpus is the single source of truth
    // for Android too. `api` because the type appears in this module's public signatures.
    // No cycle: :shared-ui does not depend on :resources-logic.
    api(project(":shared-ui"))
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.material.icons.extended)
    api(libs.androidx.compose.material3.windowSizeClass)
    api(libs.material)
}