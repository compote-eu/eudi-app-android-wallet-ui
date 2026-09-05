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

import eu.europa.ec.shared.wallet.multipaz.MultipazWalletStore
import org.multipaz.util.Logger
import platform.Foundation.NSUserDefaults

/**
 * Clears the Keychain items a previous install of this app left behind, once per install.
 *
 * ## Why this is needed at all, and only on iOS
 *
 * Deleting an iOS app removes its containers; it does **not** remove what the app put in the Keychain.
 * Android has no equivalent problem — an uninstall takes SharedPreferences, the databases and the
 * KeyStore entries with it — so this has no counterpart in the shared code and none is wanted.
 *
 * Measured on 2026-09-05 rather than assumed, because the two halves come apart:
 *
 *  - the **app-group container is removed** on uninstall, so `wallet.db` and the wallet's own tables
 *    — bookmarks, revoked flags, the transaction log — go with it;
 *  - a **Keychain item survives** — written by one install, still readable by the next.
 *
 * So without this, deleting and reinstalling the wallet leaves the *previous owner's PIN* guarding it:
 * the app finds a stored hash, routes to the unlock screen, and asks a new user for a PIN they never
 * set. Biometric login is the same shape, one item along.
 *
 * ⛔ **And since Option D, the documents are Keychain items too**, which turns this from a nuisance
 * into the thing that matters: without this, deleting the wallet would leave the previous owner's
 * credentials on the device for whoever installs it next. Clearing them is now the first thing this
 * function does.
 *
 * ## How it knows it is a new install
 *
 * The same asymmetry, used in reverse. [NSUserDefaults] lives in the app container and dies with it, so
 * a missing flag *is* the signal that this install has never run — no date arithmetic, nothing to keep
 * in sync. That is the mechanism the official EUDI iOS wallet uses
 * (`StartupInteractor.manageStorageForFirstRun`), and it is worth copying exactly: it is eleven lines
 * and it cannot drift out of step with reality.
 *
 * ## Ordering
 *
 * Called from `application(_:didFinishLaunchingWithOptions:)` **before anything reads the PIN**, and
 * deliberately not from a coroutine: these are Keychain deletes with no I/O worth yielding for, and
 * making them synchronous is what guarantees the splash cannot ask [IosPinStorage.hasPin] first, or
 * the document store hand out a credential the previous owner left. 🪤 If this is ever made
 * suspending, that race comes back.
 *
 * @return whether it wiped on this call — false when this install has run before.
 */
fun clearSecretsLeftByAPreviousInstall(): Boolean = clearSecretsLeftByAPreviousInstall(
    defaults = NSUserDefaults.standardUserDefaults,
    pinSecrets = IosKeychain(service = IosPinStorage.KEYCHAIN_SERVICE),
    forgetBiometricEnrolment = {
        IosBiometricGate(service = IosBiometricGate.DEFAULT_SERVICE).disable()
    },
    discardDocuments = { MultipazWalletStore.discardEverythingInTheKeychain() },
)

/**
 * The seam the tests drive, with the two Keychain-backed collaborators supplied.
 *
 * 🪤 Separate from the entry point above rather than expressed as default arguments, because
 * **Kotlin default arguments do not cross into Swift** — the generated header carries every parameter,
 * so `clearSecretsLeftByAPreviousInstall()` would not compile on the Swift side. Keeping the seam
 * `internal` also keeps three test-only parameters out of the framework's public surface.
 */
internal fun clearSecretsLeftByAPreviousInstall(
    defaults: NSUserDefaults,
    pinSecrets: IosSecretStore,
    forgetBiometricEnrolment: () -> Unit,
    discardDocuments: () -> Int,
): Boolean {
    if (defaults.boolForKey(RUN_AT_LEAST_ONCE)) return false

    // Documents first: they are the part whose survival would actually harm someone.
    val documents = discardDocuments()
    if (documents > 0) {
        Logger.i(TAG, "discarded $documents document store item(s) left by a previous install")
    }
    PIN_ACCOUNTS.forEach(pinSecrets::delete)
    // Deleting the item is the whole of turning biometrics off — its presence *is* the enrolment — and
    // deletion does not raise the ACL, so this costs no prompt on a launch the user did not ask for.
    forgetBiometricEnrolment()

    // Verify rather than assume, and only then record it. A delete that silently failed would leave a
    // PIN no new user can match, so the honest response is to try again next launch instead of marking
    // the install clean. This mirrors the official app, which likewise sets its flag only on success.
    // A second pass over the document store has to come back empty, which is what proves the first
    // one took: the delete is idempotent by construction.
    val cleared = PIN_ACCOUNTS.none { pinSecrets.read(it) != null } && discardDocuments() == 0
    if (cleared) {
        defaults.setBool(true, forKey = RUN_AT_LEAST_ONCE)
    }
    return cleared
}

/**
 * Namespaced because [NSUserDefaults.standardUserDefaults] is shared with every other preference the
 * app and its frameworks keep.
 */
internal const val RUN_AT_LEAST_ONCE = "eu.europa.ec.eudi.wallet.runAtLeastOnce"

private const val TAG = "IosFirstRunWipe"

/** Everything [IosPinStorage] writes. Listed once, so a new secret cannot be forgotten here. */
private val PIN_ACCOUNTS = listOf(
    IosPinStorage.KEY_SALT,
    IosPinStorage.KEY_HASH,
    IosPinStorage.KEY_ITERATIONS,
)
