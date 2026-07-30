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

// Phase 1 (date migration, stage 1): moved from :business-logic to the shared KMP module
// and ported from java.time.Instant to kotlin.time.Instant — which is what the underlying
// document credentials already use, so the app-side toJavaInstant() conversions on this
// path go away. Pure arithmetic, no formatting, so it is fully platform-neutral.
package eu.europa.ec.businesslogic.extension

import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

fun Instant.isWithinNextDays(days: Long): Boolean {
    val now = Clock.System.now()
    return this > now && this <= now + days.days
}

fun Instant.isBeyondNextDays(days: Long): Boolean =
    this > Clock.System.now() + days.days

fun Instant.isExpired(): Boolean = this < Clock.System.now()

fun Instant.isValid(): Boolean = this > Clock.System.now()
