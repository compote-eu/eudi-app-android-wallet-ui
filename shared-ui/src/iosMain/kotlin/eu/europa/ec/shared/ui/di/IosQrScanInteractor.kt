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

package eu.europa.ec.shared.ui.di

import eu.europa.ec.commonfeature.interactor.QrScanInteractor
import eu.europa.ec.shared.platform.PlatformContext
import platform.Foundation.NSURL
import platform.Foundation.NSURLComponents

/**
 * iOS's [QrScanInteractor].
 *
 * The validity rule means what Android's means, built from `NSURLComponents` rather than from
 * `FormValidator`: a scheme and a query, with host and path unchecked. That combination is not arbitrary
 * — a wallet link identifies itself by scheme and carries its payload in the query, and
 * `openid4vp://?request_uri=…` has no host at all, so a stricter rule would reject exactly the codes
 * this screen exists to read.
 */
internal class IosQrScanInteractor : QrScanInteractor {

    override suspend fun isScannedQrValid(qr: String): Boolean {
        val components = NSURLComponents(uRL = NSURL.URLWithString(qr) ?: return false, resolvingAgainstBaseURL = false)
        return !components.scheme.isNullOrBlank() && !components.query.isNullOrBlank()
    }

    /**
     * Nothing to launch, and it cannot be reached anyway.
     *
     * The RQES SDK is an Android library, so iOS offers no signature flow — and
     * [eu.europa.ec.commonfeature.config.QrScanFlow.Signature] is the only route to this call. Saying so
     * rather than failing silently is the standing rule for iOS bridges.
     */
    override fun launchRqesSdk(context: PlatformContext, uri: String) {
        println("$TAG: iOS has no remote signing, so a signature QR cannot be acted on.")
    }
}

private const val TAG = "IosQrScanInteractor"
