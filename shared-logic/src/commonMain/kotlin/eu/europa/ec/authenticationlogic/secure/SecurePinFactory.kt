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

package eu.europa.ec.authenticationlogic.secure

/**
 * Wraps typed characters in a [SecurePin], using whichever implementation the platform has.
 *
 * This exists because the PIN field is shared UI: `WrapSecurePinTextField` is the one place that turns
 * keystrokes into a [SecurePin], and it now compiles for both platforms, so it cannot name a concrete
 * class. Constructing the implementation directly is still fine for callers that are Android-only.
 *
 * The two implementations differ only in how they lock — `@Synchronized` on Android, an atomicfu
 * `ReentrantLock` on iOS — and are held to the same behaviour by the shared
 * [eu.europa.ec.authenticationlogic.secure.SecurePinTest].
 */
expect fun securePinOf(text: CharSequence): SecurePin
