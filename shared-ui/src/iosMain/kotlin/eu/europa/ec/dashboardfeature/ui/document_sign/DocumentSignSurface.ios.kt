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

package eu.europa.ec.dashboardfeature.ui.document_sign

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/**
 * The Swift half of document signing, supplied by the app at launch.
 *
 * **This seam runs the opposite way to every other iOS bridge here, and it has to.** The camera and
 * Bluetooth are Objective-C frameworks, so Kotlin/Native reaches AVFoundation and CoreBluetooth
 * directly through cinterop. `EudiRQESUi` is a *Swift* package: its API is Swift structs, enums and
 * protocols, none of which are visible to Kotlin at all. So instead of Kotlin calling the library,
 * Swift registers an implementation of [IosDocumentSigner] and Kotlin calls that.
 *
 * The consequence worth stating: a build that forgets to register one still compiles and still runs,
 * and signing then reports itself unavailable rather than crashing. That is why [signer] is nullable
 * and why the trigger below has an honest failure path instead of a `requireNotNull`.
 */
object IosDocumentSigning {

    /**
     * Set once from `iOSApp.swift`, before any screen can ask for it.
     *
     * Null until then, and null forever in a build that never wires the SDK. Nothing here retains a
     * view controller: the signer resolves the presenting controller when it is asked to run, so a
     * stale one cannot outlive the screen that caused it.
     */
    var signer: IosDocumentSigner? = null
}

/**
 * What Swift implements: pick a PDF, hand it to `EudiRQESUi.initiate(on:fileUrl:)`.
 *
 * Both halves are one call because the SDK needs the presenting view controller, which only Swift
 * can supply, and because the picked file's URL should never have to cross into Kotlin and back.
 */
interface IosDocumentSigner {

    /**
     * Runs the whole handover.
     *
     * @param onOutcome exactly one call. `cancelled = true` means the user dismissed the picker;
     *   otherwise a null [error] means the SDK took over, and a non-null one is a message already
     *   fit to show.
     */
    fun selectAndSign(onOutcome: (cancelled: Boolean, error: String?) -> Unit)
}

/**
 * iOS's half: delegate to whatever Swift registered, or say so.
 *
 * Nothing is remembered across recompositions except the trigger itself — the signer is read at call
 * time so that a late registration is still picked up.
 */
@Composable
actual fun rememberDocumentSignTrigger(
    onOutcome: (DocumentSignOutcome) -> Unit,
): DocumentSignTrigger {
    val currentOnOutcome by rememberUpdatedState(onOutcome)

    return remember {
        DocumentSignTrigger {
            val signer = IosDocumentSigning.signer
            if (signer == null) {
                currentOnOutcome(
                    DocumentSignOutcome.Failed(reason = NO_SIGNER_REGISTERED)
                )
            } else {
                signer.selectAndSign { cancelled, error ->
                    currentOnOutcome(
                        when {
                            cancelled -> DocumentSignOutcome.Cancelled
                            error != null -> DocumentSignOutcome.Failed(reason = error)
                            else -> DocumentSignOutcome.Started
                        }
                    )
                }
            }
        }
    }
}

/**
 * Deliberately not a localized string.
 *
 * It can only be reached by a build whose `iOSApp.swift` failed to register a signer, which is a
 * wiring mistake rather than something a user can be in a position to fix. Reporting the limit
 * honestly beats a friendly message that hides which half is missing.
 */
private const val NO_SIGNER_REGISTERED =
    "Document signing is not available in this build: no signer was registered at launch."
