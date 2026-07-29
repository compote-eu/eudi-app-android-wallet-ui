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
 * Platform-neutral quick-PIN validation — a stand-in for the kind of pure business logic
 * that moves to `commonMain` first (Phase 1). No Android/iOS APIs, so it runs identically
 * on both platforms; exercised by [PinValidatorTest] on Android *and* iOS.
 */
object PinValidator {

    /** A PIN is valid when it is exactly [length] characters, all decimal digits. */
    fun isValid(pin: String, length: Int = 6): Boolean =
        pin.length == length && pin.all { it in '0'..'9' }
}
