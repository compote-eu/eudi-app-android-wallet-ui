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

import kotlinx.cinterop.ExperimentalForeignApi
import org.multipaz.util.Logger
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL

/**
 * The container the wallet app and its document-provider extension both read.
 *
 * Two processes, two private containers: an ExtensionKit extension gets its own, so anything the app
 * writes into its own container is invisible to the extension that answers Digital Credentials
 * requests. An app group is the only place both can reach, which makes this a prerequisite of being
 * a provider rather than a preference.
 *
 * ## 🪤 The identifier is read, never derived
 *
 * The group is published by **both** targets as the `EUDIAppGroupIdentifier` Info.plist key, built
 * from `$(APP_BUNDLE_ID)` — the same expression both targets' `application-groups` entitlement uses.
 * So the app, the extension and the two entitlements all come from one place in `project.yml`.
 *
 * Deriving it instead is the bug this exists to prevent. `group.` +
 * `NSBundle.mainBundle.bundleIdentifier` is the id of the **running** process: correct in the app,
 * but `…dev.provider` inside the extension — a group neither binary is entitled to. iOS answers an
 * unentitled group with **nil rather than an error**, so the extension quietly fell back to its own
 * container and opened an empty wallet. Every Digital Credentials request would then find no
 * documents, on a build where registration, the responder and the consent screen all work.
 *
 * That failure is invisible from the app side, which is why it survived a full round-trip proof
 * driven from the app process.
 */
internal object IosAppGroup {

    /**
     * The shared container, or null if this build has no usable app group.
     *
     * Null is a real outcome rather than a defect: `com.apple.security.application-groups` is not a
     * restricted entitlement and does resolve on ad-hoc-signed simulator builds, but a signing setup
     * that never registered the group returns nil. Callers degrade instead of refusing to start —
     * see [MultipazWalletStore][eu.europa.ec.shared.wallet.multipaz.MultipazWalletStore], whose
     * reasoning this follows.
     */
    @OptIn(ExperimentalForeignApi::class)
    fun containerUrl(): NSURL? {
        val identifier = identifier()
        if (identifier == null) {
            Logger.w(
                TAG,
                "no $INFO_PLIST_KEY in Info.plist; regenerate the Xcode project with " +
                    "./gradlew generateIosProject",
            )
            return null
        }
        val container = NSFileManager.defaultManager
            .containerURLForSecurityApplicationGroupIdentifier(identifier)
        if (container == null) {
            Logger.w(
                TAG,
                "iOS returned no container for $identifier; this build's signing does not grant " +
                    "that group, so the app and its extension cannot share data",
            )
        }
        return container
    }

    /**
     * The group identifier this build was generated with, or null if the key is missing.
     *
     * Blank is treated as missing: XcodeGen writes an unset `${...}` through as literal text, so a
     * mis-generated project yields a string that is present but cannot name a container.
     */
    fun identifier(): String? =
        (NSBundle.mainBundle.objectForInfoDictionaryKey(INFO_PLIST_KEY) as? String)
            ?.takeIf { it.isNotBlank() && !it.startsWith("group.\${") }

    /**
     * The Info.plist key both targets publish. Named in `iosApp/project.yml` twice — once per target
     * — and asserted by `AppIdentityParityTest`, so a target that stops publishing it fails a test
     * rather than losing the wallet at runtime.
     */
    const val INFO_PLIST_KEY = "EUDIAppGroupIdentifier"

    private const val TAG = "IosAppGroup"
}
