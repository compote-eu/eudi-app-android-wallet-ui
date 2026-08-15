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

package eu.europa.ec.authenticationlogic.storage

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.create
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAErrorBiometryNotEnrolled
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics
import platform.Security.SecAccessControlCreateWithFlags
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAccessControlBiometryCurrentSet
import platform.Security.kSecAttrAccessControl
import platform.Security.kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecReturnAttributes
import platform.Security.kSecReturnData
import platform.Security.kSecUseOperationPrompt
import platform.Security.kSecValueData
import platform.posix.arc4random_buf

/** Whether this device can do biometrics at all, and if not, why not. */
enum class IosBiometricAvailability { Available, NotEnrolled, Unavailable }

/** How a biometric prompt ended. */
sealed interface IosBiometricOutcome {
    data object Success : IosBiometricOutcome
    data object Cancelled : IosBiometricOutcome
    data class Failed(val message: String) : IosBiometricOutcome
}

/**
 * Biometric login on iOS, enforced by the Secure Enclave rather than by this app.
 *
 * **What makes this worth having rather than theatre.** Raising a Face ID prompt with
 * `LAContext.evaluatePolicy` and believing its answer would put the security decision inside the app: a
 * boolean the app checks is a boolean the app could get wrong. So instead, enabling biometric login
 * writes a random secret into the Keychain under an access-control policy the *system* enforces, and
 * "authenticate" means reading that secret back. The read cannot succeed without the OS having
 * satisfied the policy first, and the app never sees the decision — only the bytes, or nothing.
 *
 * This is the iOS counterpart of what Android does with a Keystore key and a `BiometricPrompt`
 * `CryptoObject`, and it is the same argument in both places: bind the prompt to a key, not to a flag.
 *
 * Two policy choices worth stating:
 * - **`kSecAccessControlBiometryCurrentSet`**, not `biometryAny`: the item is destroyed if the enrolled
 *   biometrics change. Someone who adds their own face to an unlocked phone does not thereby gain the
 *   wallet — they get an item that no longer decrypts, and the wallet falls back to the PIN.
 * - **`kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly`**: no passcode, no item; and it never leaves
 *   this device, so a backup restored elsewhere cannot carry the wallet's biometric unlock with it.
 *
 * The enrolment decision is not stored as a preference anywhere. The presence of the item *is* the
 * decision, which is one fewer thing that can disagree with reality — and one that cannot be flipped by
 * editing a plist.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosBiometricGate(
    private val service: String,
    private val account: String = DEFAULT_ACCOUNT,
) {

    /**
     * Whether the device can do biometrics.
     *
     * [IosBiometricAvailability.NotEnrolled] is separated out because the shared screen says something
     * different for it — "you have no biometrics set up" invites a fix, where a flat "unavailable" does
     * not.
     */
    fun availability(): IosBiometricAvailability = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val canEvaluate = LAContext().canEvaluatePolicy(
            LAPolicyDeviceOwnerAuthenticationWithBiometrics,
            error.ptr,
        )
        when {
            canEvaluate -> IosBiometricAvailability.Available
            error.value?.code == LAErrorBiometryNotEnrolled -> IosBiometricAvailability.NotEnrolled
            else -> IosBiometricAvailability.Unavailable
        }
    }

    /**
     * Whether the user has turned biometric login on.
     *
     * Asks for the item's *attributes* and not its data, which is what keeps this from raising a
     * prompt: the access-control policy guards the secret, not the fact that a secret exists. A version
     * of this that requested data would put a Face ID prompt in front of every launch of the settings
     * screen.
     */
    fun isEnabled(): Boolean = memScoped {
        val query = CFDictionaryCreateMutable(kCFAllocatorDefault, 5, null, null)
        CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionarySetValue(query, kSecAttrService, CFBridgingRetain(service))
        CFDictionarySetValue(query, kSecAttrAccount, CFBridgingRetain(account))
        CFDictionarySetValue(query, kSecReturnAttributes, kCFBooleanTrue)

        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, result.ptr)
        result.value?.let { CFBridgingRelease(it) }
        status == errSecSuccess
    }

    /**
     * Turns biometric login on, by creating the item whose existence is that decision.
     *
     * @return false when the system refused the policy — no passcode set, or no biometrics enrolled.
     *   The caller reports that rather than leaving a switch on that protects nothing.
     */
    fun enable(): Boolean = memScoped {
        disable()

        val accessControl = SecAccessControlCreateWithFlags(
            kCFAllocatorDefault,
            kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
            kSecAccessControlBiometryCurrentSet,
            null,
        ) ?: return false

        try {
            val attributes = CFDictionaryCreateMutable(kCFAllocatorDefault, 5, null, null)
            CFDictionarySetValue(attributes, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(attributes, kSecAttrService, CFBridgingRetain(service))
            CFDictionarySetValue(attributes, kSecAttrAccount, CFBridgingRetain(account))
            CFDictionarySetValue(attributes, kSecValueData, CFBridgingRetain(randomSecret().toNSData()))
            CFDictionarySetValue(attributes, kSecAttrAccessControl, accessControl)

            SecItemAdd(attributes, null) == errSecSuccess
        } finally {
            CFRelease(accessControl)
        }
    }

    /** Turns biometric login off. Deleting the item is the whole of it; there is no flag to clear. */
    fun disable() {
        val query = CFDictionaryCreateMutable(kCFAllocatorDefault, 3, null, null)
        CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionarySetValue(query, kSecAttrService, CFBridgingRetain(service))
        CFDictionarySetValue(query, kSecAttrAccount, CFBridgingRetain(account))
        SecItemDelete(query)
    }

    /**
     * Raises the biometric prompt and reports what the system decided.
     *
     * The prompt is a side effect of reading the item: there is no separate "authenticate" call whose
     * answer the app could mishandle. [reason] is what iOS shows in the prompt, and is required — an
     * empty one gives a prompt that does not say what it is for.
     *
     * Off the main thread deliberately. `SecItemCopyMatching` on a biometry-gated item blocks until the
     * user answers, which on the main thread is a frozen UI behind the system prompt.
     */
    suspend fun authenticate(reason: String): IosBiometricOutcome = withContext(Dispatchers.Default) {
        memScoped {
            val query = CFDictionaryCreateMutable(kCFAllocatorDefault, 6, null, null)
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(query, kSecAttrService, CFBridgingRetain(service))
            CFDictionarySetValue(query, kSecAttrAccount, CFBridgingRetain(account))
            CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
            CFDictionarySetValue(query, kSecUseOperationPrompt, CFBridgingRetain(reason))

            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            result.value?.let { CFBridgingRelease(it) }

            when (status) {
                errSecSuccess -> IosBiometricOutcome.Success
                // Cancelling is an answer, not a failure: the screen keeps its PIN field and says
                // nothing, where an error message would read as something having gone wrong.
                ERR_SEC_USER_CANCELED -> IosBiometricOutcome.Cancelled
                // The item is gone because the enrolled biometrics changed — which is the policy
                // working, not a malfunction, so it is worth its own message.
                ERR_SEC_ITEM_NOT_FOUND -> IosBiometricOutcome.Failed(ENROLMENT_CHANGED)
                else -> IosBiometricOutcome.Failed(NOT_RECOGNISED)
            }
        }
    }

    private fun randomSecret(): ByteArray = ByteArray(SECRET_BYTES).also { bytes ->
        bytes.usePinned { arc4random_buf(it.addressOf(0), SECRET_BYTES.toULong()) }
    }

    companion object {
        /** One item, since there is one decision to record. */
        const val DEFAULT_ACCOUNT = "biometric-unlock"

        /** Never read for its contents — only for whether the system will hand it over at all. */
        private const val SECRET_BYTES = 32

        // Security framework status codes, named because a bare -128 in a `when` says nothing.
        // `errSecUserCanceled` is what a dismissed system prompt returns; `errSecItemNotFound` is what
        // an item invalidated by a biometric re-enrolment returns, since the system deleted it.
        private const val ERR_SEC_USER_CANCELED = -128
        private const val ERR_SEC_ITEM_NOT_FOUND = -25300

        const val ENROLMENT_CHANGED =
            "Biometric login was turned off because this device's biometrics changed. Use your PIN, " +
                    "then switch it back on."

        const val NOT_RECOGNISED = "Not recognised. Use your PIN instead."
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
}
