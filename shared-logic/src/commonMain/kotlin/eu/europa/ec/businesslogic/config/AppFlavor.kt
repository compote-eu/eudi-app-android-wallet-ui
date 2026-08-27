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

// Package `eu.europa.ec.businesslogic.config` deliberately, though the file lives in :shared-logic —
// this enum was declared inside `business-logic`'s `ConfigLogic.kt` and every Android import of it
// stays valid. The same trick shared-ui uses for `eu.europa.ec.corelogic.model`.
package eu.europa.ec.businesslogic.config

/**
 * Which build this is, on both platforms.
 *
 * There were two of these until 2026-08-27: this one and an `IosAppFlavor` in `:shared-logic`'s
 * `iosMain`, identical down to the entry names and kept in step by hand. Nothing enforced that they
 * agreed, and a flavour is not the kind of thing where the two platforms may quietly diverge — it
 * decides which issuers and wallet provider the app talks to.
 *
 * ⚠️ **Not the same enum as `project.convention.logic.AppFlavor`** in `build-logic`. That one is a
 * Gradle convention-plugin type that declares the product flavours and their application-id suffixes
 * at *build* time; this one is what the running app reports about itself. They share a name and
 * nothing else, so neither should be made to depend on the other.
 */
enum class AppFlavor {
    /** Talks to the EU **dev** deployments. Android suffixes its application id with `.dev`. */
    DEV,

    /** Talks to the EU **production-shaped** deployments. Android's un-suffixed flavour. */
    DEMO,
}
