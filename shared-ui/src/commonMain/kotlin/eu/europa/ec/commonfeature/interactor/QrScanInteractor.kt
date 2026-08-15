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

// The QR scanner's interactor contract, moved to commonMain with `QrScanViewModel`.
//
// It used to extend `FormValidator` and take an `android.content.Context`, which is what kept the
// view-model Android-side. Neither survives the move, and for different reasons: the validator is 433
// lines of `android.net.Uri`, `android.util.Patterns` and libphonenumber, so what crosses is its
// *answer* rather than the framework; and the context existed only for the RQES hand-off, which is now
// the opaque `PlatformContext`.
package eu.europa.ec.commonfeature.interactor

import eu.europa.ec.shared.platform.PlatformContext

interface QrScanInteractor {

    /**
     * Whether a scanned string is a link this wallet could act on.
     *
     * Deliberately a boolean rather than a validation framework: the view-model's interest is entirely
     * "did that scan give me something usable", and it counts the failures itself. Android answers with
     * `FormValidator`'s URL rule — scheme and query required, host and path not — and other platforms
     * are expected to mean the same thing by it.
     */
    suspend fun isScannedQrValid(qr: String): Boolean

    /**
     * Hands a signing link to the RQES SDK.
     *
     * Only reachable from [eu.europa.ec.commonfeature.config.QrScanFlow.Signature], which only Android
     * offers; a platform without remote signing reports that rather than pretending to start one.
     */
    fun launchRqesSdk(context: PlatformContext, uri: String)
}
