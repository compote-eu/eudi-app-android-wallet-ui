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

import org.multipaz.util.Logger
import platform.Foundation.NSBundle

/**
 * The Keychain access group the app and its document-provider extension share.
 *
 * ## Why this exists, and why it is read rather than derived
 *
 * An app extension has its **own bundle identifier**, so by default it gets its own Keychain access
 * group and cannot see a single item the container app wrote. That is fine while the wallet's
 * documents live in a file inside a shared app-group container — but the moment they live in the
 * Keychain, a Digital Credentials request answered by the extension would find an empty wallet.
 *
 * This is [IosAppGroup] one layer up, and it is deliberately built the same way: **both targets
 * publish the value into their Info.plist from one expression in `project.yml`, and it is read from
 * there.** Deriving it from `NSBundle.mainBundle.bundleIdentifier` is precisely the defect that cost
 * `fd785674` — correct in the app, `…dev.provider` inside the extension, and iOS answers an
 * unentitled group with silence rather than an error.
 *
 * ## The shape of the value
 *
 * A Keychain access group is written in an entitlement as `$(AppIdentifierPrefix)<group>` and used at
 * runtime as the expanded `TEAMID.<group>`. Xcode expands the prefix while building the Info.plist,
 * so the value read here is already complete. If it is not — an unexpanded `$(…)` — it is treated as
 * missing, for the same reason [IosAppGroup] rejects a literal `group.${…}`: a mis-generated project
 * should degrade to something diagnosable, not write items under a nonsense group.
 */
internal object IosKeychainAccessGroup {

    /**
     * The shared group, or null when this build has none.
     *
     * Null is a real outcome, not a defect: an ad-hoc-signed build has no team prefix to expand, and a
     * wallet that never answers Digital Credentials requests does not need the sharing. Callers pass
     * null straight through to `kSecAttrAccessGroup`, which means *this application's own group* —
     * so the app keeps working and only cross-process reads are lost.
     */
    fun identifier(): String? {
        val value = (NSBundle.mainBundle.objectForInfoDictionaryKey(INFO_PLIST_KEY) as? String)
            ?.takeIf { it.isNotBlank() && !it.contains("\$(") }
        if (value == null) {
            Logger.w(
                TAG,
                "no usable $INFO_PLIST_KEY in Info.plist; Keychain items stay private to this " +
                    "process and the document-provider extension will not see the wallet's " +
                    "documents. Regenerate with ./gradlew generateIosProject on a team-signed build.",
            )
        }
        return value
    }

    /**
     * The Info.plist key both targets publish, named once per target in `iosApp/project.yml` and
     * asserted by `AppIdentityParityTest` — so a target that stops publishing it fails a test rather
     * than losing the wallet at runtime.
     */
    const val INFO_PLIST_KEY = "EUDIKeychainAccessGroup"

    private const val TAG = "IosKeychainAccessGroup"
}
