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

// Phase 3b: the device-authentication *contract*, moved so the authenticating view-models can name it
// from commonMain. `DeviceAuthenticationInteractorImpl` stays in :common-feature with its Koin
// provider, since it delegates to the Android DeviceAuthenticationController. Every type here is now
// platform-neutral: `PlatformContext` and the crypto handle are opaque, and the availability and result
// types are plain Kotlin. Package unchanged.
package eu.europa.ec.commonfeature.interactor

import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAvailability
import eu.europa.ec.authenticationlogic.controller.authentication.DeviceAuthenticationResult
import eu.europa.ec.authenticationlogic.model.BiometricCrypto
import eu.europa.ec.shared.platform.PlatformContext

interface DeviceAuthenticationInteractor {
    fun getBiometricsAvailability(): BiometricsAvailability
    fun authenticateWithBiometrics(
        context: PlatformContext,
        crypto: BiometricCrypto,
        notifyOnAuthenticationFailure: Boolean,
        resultHandler: DeviceAuthenticationResult
    )

    fun launchBiometricSystemScreen()
}
