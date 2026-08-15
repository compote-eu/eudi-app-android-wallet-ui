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
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * A place to keep a few small blobs by name. Exists so [IosPinStorage]'s hashing and comparison can be
 * tested without a Keychain: a unit-test binary has no keychain-access entitlement, and
 * `SecItemAdd` answers `-34018` there. The real Keychain is exercised in-app instead, by the wallet probe.
 */
interface IosSecretStore {
    fun read(account: String): ByteArray?
    fun write(account: String, value: ByteArray)
    fun delete(account: String)
}

/**
 * The few Keychain operations this wallet needs: read, write and delete a blob under a name.
 *
 * Items are generic passwords scoped to one `service`, stored
 * `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` — off backups, off other devices, unreadable while the
 * phone is locked. That last part is what makes this the right home for a PIN verifier and the wrong home
 * for anything a background task needs.
 *
 * Writes replace rather than merge: `SecItemAdd` refuses a duplicate, so an existing item is deleted first.
 * That is one syscall more than `SecItemUpdate` and avoids the case where an update silently leaves stale
 * attributes behind.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosKeychain(private val service: String) : IosSecretStore {

    override fun read(account: String): ByteArray? = memScoped {
        val query = CFDictionaryCreateMutable(kCFAllocatorDefault, 4, null, null)
        CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionarySetValue(query, kSecAttrService, CFBridgingRetain(service))
        CFDictionarySetValue(query, kSecAttrAccount, CFBridgingRetain(account))
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)

        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, result.ptr)
        if (status != errSecSuccess) {
            if (status != errSecItemNotFound) {
                println("$TAG: reading '$account' failed with status $status")
            }
            return null
        }
        val data = CFBridgingRelease(result.value) as? NSData ?: return null
        data.toByteArray()
    }

    override fun write(account: String, value: ByteArray) {
        delete(account)
        val attributes = CFDictionaryCreateMutable(kCFAllocatorDefault, 5, null, null)
        CFDictionarySetValue(attributes, kSecClass, kSecClassGenericPassword)
        CFDictionarySetValue(attributes, kSecAttrService, CFBridgingRetain(service))
        CFDictionarySetValue(attributes, kSecAttrAccount, CFBridgingRetain(account))
        CFDictionarySetValue(attributes, kSecValueData, CFBridgingRetain(value.toNSData()))
        CFDictionarySetValue(
            attributes,
            kSecAttrAccessible,
            kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
        )

        val status = SecItemAdd(attributes, null)
        check(status == errSecSuccess) { "storing '$account' in the keychain failed: $status" }
    }

    override fun delete(account: String) {
        val query = CFDictionaryCreateMutable(kCFAllocatorDefault, 3, null, null)
        CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionarySetValue(query, kSecAttrService, CFBridgingRetain(service))
        CFDictionarySetValue(query, kSecAttrAccount, CFBridgingRetain(account))
        SecItemDelete(query)
    }

    private companion object {
        const val TAG = "IosKeychain"
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val bytes = ByteArray(size)
    if (size > 0) {
        bytes.usePinned { pinned ->
            platform.posix.memcpy(pinned.addressOf(0), this.bytes, length)
        }
    }
    return bytes
}
