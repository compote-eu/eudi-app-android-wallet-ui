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

package eu.europa.ec.shared

/**
 * Platform-neutral entry point for OS identity. Exposed as an object (not a top-level
 * function) so it gets a clean Swift name — `Platform.shared.name` — rather than the
 * file-derived facade (`Platform_iosKt`) Kotlin/Native generates for top-level functions.
 */
object Platform {
    val name: String get() = platformName()
}

/**
 * The per-platform hook behind [Platform]; each platform supplies its own OS identity
 * while callers stay platform-neutral. This is the seam every platform binding (secure
 * area, biometrics, camera, …) will follow. Kept `internal` to stay out of the public
 * Obj-C/Swift surface.
 */
internal expect fun platformName(): String
