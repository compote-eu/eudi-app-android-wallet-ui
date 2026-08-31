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

package eu.europa.ec.commonfeature.interactor

import android.content.Context
import eu.europa.ec.businesslogic.validator.Form
import eu.europa.ec.businesslogic.validator.FormValidator
import eu.europa.ec.businesslogic.validator.Rule
import eu.europa.ec.eudi.rqesui.domain.extension.toUriOrEmpty
import eu.europa.ec.eudi.rqesui.infrastructure.EudiRQESUi
import eu.europa.ec.eudi.rqesui.infrastructure.RemoteUri

/**
 * Android's [QrScanInteractor]: the URL rule, and the RQES hand-off.
 *
 * The `Form`/`Rule` construction moved here from `QrScanViewModel` along with the validator it needs.
 * That is the point of the narrower contract — the view-model asked one question and built twenty lines
 * of validation framework to ask it, and the framework is Android-only.
 */
class QrScanInteractorImpl(
    private val formValidator: FormValidator
) : QrScanInteractor {

    override suspend fun isScannedQrValid(qr: String): Boolean = formValidator.validateForm(
        form = Form(
            inputs = mapOf(
                listOf(
                    Rule.ValidateUrl(
                        errorMessage = "",
                        shouldValidateSchema = true,
                        shouldValidateHost = false,
                        shouldValidatePath = false,
                        shouldValidateQuery = true,
                    )
                ) to qr
            )
        )
    ).isValid

    override fun launchRqesSdk(context: Context?, uri: String) {
        // Nullable only so the shared signature admits iOS, which has no host context and no RQES SDK
        // to hand one to. On Android the scanner always has one, so this cannot happen; the SDK simply
        // cannot be started without it. Not logged because nothing in these feature modules logs, and
        // an unreachable branch is a poor reason to introduce the first one.
        if (context == null) return
        EudiRQESUi.initiate(
            context = context,
            remoteUri = RemoteUri(uri.toUriOrEmpty())
        )
    }
}
