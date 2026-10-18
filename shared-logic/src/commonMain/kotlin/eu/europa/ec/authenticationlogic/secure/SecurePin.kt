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

// Phase 3b: the PIN *contracts* move to commonMain so `PinViewModel` (and later the Biometric and
// document-offer-code view-models) can live there — a PIN travels from the screen through a view-model
// to an interactor, so every layer in between has to name this type.
//
// Only the interfaces move. `SecurePinImpl` and the concrete [SecurePinData] stay in
// :authentication-logic because their mutual exclusion is `@Synchronized`, which is JVM-only: porting
// it would mean choosing a Kotlin/Native locking strategy for a security primitive, and nothing here
// needs that yet. Keeping [SecurePinData] as an interface is what makes the split possible — the one
// production consumer of `getAndClear()` (PrefsPinStorageProvider) only ever uses `useChars`, `length`
// and `close`, so the concrete class never has to cross a module boundary. Packages are unchanged, so
// no call site moves.
//
// This module must be :shared-logic rather than :shared-ui: :authentication-logic implements these
// interfaces and can see :shared-logic (via :business-logic's `api`) but not :shared-ui.
package eu.europa.ec.authenticationlogic.secure

/**
 * A PIN held as clearable characters rather than a `String`, so it can be zeroed after use instead of
 * lingering in the immutable string pool until GC.
 *
 * Implementations are single-use: [getAndClear] and [getAndClearAsString] hand over the characters and
 * leave the instance cleared, and [close] zeroes them if nobody consumed them.
 */
interface SecurePin : AutoCloseable {

    val length: Int
    val isCleared: Boolean

    fun getAndClear(): SecurePinData

    fun getAndClearAsString(): String

    fun contentEquals(other: SecurePin): Boolean

    override fun close()
}

/**
 * The characters handed over by [SecurePin.getAndClear], scoped so they can be zeroed once used.
 *
 * Consume them through [useChars] and close it — ideally via `use {}` — rather than retaining the
 * array, since [close] zeroes it in place.
 */
interface SecurePinData : AutoCloseable {

    val length: Int

    fun <T> useChars(block: (CharArray) -> T): T

    override fun close()
}
