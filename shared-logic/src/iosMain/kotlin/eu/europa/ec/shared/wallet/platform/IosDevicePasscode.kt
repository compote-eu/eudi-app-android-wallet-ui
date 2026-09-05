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

package eu.europa.ec.shared.wallet.platform

import eu.europa.ec.shared.wallet.multipaz.MultipazWalletStore
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSError
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAErrorPasscodeNotSet
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication

/**
 * Whether this device has a passcode set.
 *
 * ## Why the wallet has to care
 *
 * Since the documents moved into the Keychain they carry
 * `kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly`, and iOS will not create an item in that class on
 * a device with no passcode. **A wallet on such a device cannot store a credential at all** — every
 * `SecItemAdd` is refused, and the refusal arrives as an opaque `OSStatus` from deep inside a storage
 * call, which is no use to the person holding the phone.
 *
 * ⚠️ **This is the explanation, not the test.** Whether the wallet can store anything is decided by
 * actually trying — see [iosWalletBlockedByMissingPasscode]. This answers the *second* question, the
 * one that turns a refusal into something a user can act on.
 *
 * The official EUDI iOS wallet uses the same protection class and does **not** do either: its
 * `StorageError` is a description-and-code struct and its save path rethrows the raw status, so the
 * same device would show a generic storage failure there. This is one of the few places it is cheap
 * to be better rather than equal.
 *
 * ## 🪤 Why `LAContext` rather than a status code
 *
 * The obvious implementation is to attempt a write and interpret what comes back. Don't: the status
 * for "no passcode" is not documented as a single value, reports disagree between iOS versions, and
 * **it cannot be measured here** — the simulator enforces no data-protection class at all and happily
 * creates a `WhenPasscodeSet` item without a passcode (measured 2026-09-05), while the test iPhone is
 * managed and refuses to have its passcode turned off. Inferring a cause from an unverifiable code
 * would be exactly the kind of guess this codebase keeps having to retract.
 *
 * `LAPolicyDeviceOwnerAuthentication` is the documented question instead: it means *passcode, or any
 * biometry that falls back to passcode*, and it fails with [LAErrorPasscodeNotSet] precisely when
 * there is none. 🪤 Not `…WithBiometrics`, which is a different question and answers `false` on a
 * perfectly good passcode-only device.
 */
@OptIn(ExperimentalForeignApi::class)
object IosDevicePasscode {

    /**
     * True when a passcode is set, and **true when the answer cannot be established**.
     *
     * Failing open is deliberate. This gate exists to explain a specific, recoverable situation; if
     * `LAContext` reports something unexpected, blocking the wallet would turn a diagnostic into an
     * outage. The storage layer still refuses to write in that case, and
     * [eu.europa.ec.shared.wallet.multipaz.KeychainWalletStorage] reports why.
     */
    fun isSet(): Boolean = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val canEvaluate = LAContext().canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, error.ptr)
        if (canEvaluate) return@memScoped true
        // Only this one code means "there is no passcode". Everything else — hardware unavailable,
        // a transient error, a code this OS version invented — leaves the question open, and an open
        // question must not lock the user out.
        error.value?.code != LAErrorPasscodeNotSet
    }
}

/**
 * Whether the wallet should refuse to open and explain why, because this device has no passcode.
 *
 * The whole question, answered in one place so the UI layer needs neither the Keychain service
 * namespace nor the access group — both of which belong to the store and are internal to it.
 *
 * ⛔ **Both halves are required, and the order matters.**
 *
 *  - [MultipazWalletStore.canStoreDocuments] asks whether the Keychain will *actually* take a
 *    document. That is the real question, and it is asked first because it is the one that decides
 *    whether the wallet works.
 *  - [IosDevicePasscode.isSet] then decides whether the refusal is the *explainable* one. A refusal
 *    for any other reason is a bug, not something a user can fix, and locking them out of the wallet
 *    with a message about passcodes would be worse than letting the failure surface where it happens.
 *
 * 🪤 **Gating on the passcode alone would block the simulator**, which has none and stores the item
 * anyway. That mistake was made and caught before it shipped.
 */
fun iosWalletBlockedByMissingPasscode(): Boolean =
    !MultipazWalletStore.canStoreDocuments() && !IosDevicePasscode.isSet()
