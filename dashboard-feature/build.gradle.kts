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
import project.convention.logic.kover.KoverExclusionRules
import project.convention.logic.kover.excludeFromKoverReport

plugins {
    id("project.android.feature")
    id("project.rqes.sdk")
}

extensions.configure<LibraryExtension>("android") {
    namespace = "eu.europa.ec.dashboardfeature"
}

moduleConfig {
    module = LibraryModule.DashboardFeature
}

dependencies {
    // Legacy navigation-compose survives ONLY for the dashboard's nested bottom-navigation NavHost
    // (self-contained tab state with saveState/restoreState, unrelated to the app's Nav3 back stack).
    // App routing is Navigation 3 — see RouterHostImpl.StartFlow. Migrating the tabs off this would
    // change per-tab back behaviour, so it is deliberately left as a separate follow-up. This is the
    // only module that still needs it; it used to be added to every Compose module by
    // AndroidCompose.kt.
    implementation(libs.androidx.navigation.compose)
}

excludeFromKoverReport(
    excludedClasses = KoverExclusionRules.DashboardFeature.classes,
    excludedPackages = KoverExclusionRules.DashboardFeature.packages,
)