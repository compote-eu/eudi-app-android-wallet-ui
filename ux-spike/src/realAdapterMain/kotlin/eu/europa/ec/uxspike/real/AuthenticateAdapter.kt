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

package eu.europa.ec.uxspike.real

import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAuthenticate
import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAvailability
import eu.europa.ec.commonfeature.interactor.BiometricInteractor
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.ux.contract.pres.AuthenticateResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * `authenticate`: the app's biometric prompt ([BiometricInteractor], the one the app's own biometric screen raises).
 * The user's PIN path has no counterpart in the contract (G-PIN), and the presentation's KEY-bound prompt stays where
 * the app has it, inside the send (Android `UserAuthenticationRequired`, iOS Secure Enclave) (G-AUTH-ORDER).
 */
internal suspend fun authenticateWith(
    biometrics: BiometricInteractor,
    context: PlatformContext?,
    log: (String) -> Unit,
): AuthenticateResult {
    if (!biometrics.getBiometricUserSelection()) {
        log("GAP authenticate: the user chose PIN, not biometrics; the contract has no PIN path -> Error")
        return AuthenticateResult.Error(reason = AdapterGaps.PIN_ONLY_USER)
    }
    return when (val availability = biometrics.getBiometricsAvailability()) {
        BiometricsAvailability.NonEnrolled -> AuthenticateResult.NotEnrolled
        is BiometricsAvailability.Failure -> AuthenticateResult.Error(reason = availability.errorMessage)
        BiometricsAvailability.CanAuthenticate -> when (val outcome = prompt(biometrics, context)) {
            BiometricsAuthenticate.Success -> AuthenticateResult.Authenticated
            is BiometricsAuthenticate.Failed -> AuthenticateResult.Failed
            // Lossy: the contract has no Cancelled result for authenticate; the user dismissing the prompt is a failure.
            BiometricsAuthenticate.Cancelled -> {
                log("GAP authenticate: prompt Cancelled -> Failed (no Cancelled result in the contract)")
                AuthenticateResult.Failed
            }
        }
    }
}

/** The callback API as one suspension. `notifyOnAuthenticationFailure = true`: one rejected scan ends the prompt. */
private suspend fun prompt(biometrics: BiometricInteractor, context: PlatformContext?): BiometricsAuthenticate =
    suspendCancellableCoroutine { continuation ->
        biometrics.authenticateWithBiometrics(context, notifyOnAuthenticationFailure = true) { outcome ->
            if (continuation.isActive) continuation.resume(outcome)
        }
    }
