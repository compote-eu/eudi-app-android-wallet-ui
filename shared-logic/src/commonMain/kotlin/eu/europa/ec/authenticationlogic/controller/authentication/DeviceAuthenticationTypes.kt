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

// Phase 3b: the device-authentication *result* types, split out of the Android
// BiometricAuthenticationController.kt / DeviceAuthenticationController.kt they were co-located with.
// All three are plain Kotlin — two sealed hierarchies and a bundle of callbacks — and view-models hold
// and construct them directly, so they have to be commonMain for the authenticating view-models to
// move. The controllers that produce them stay in :authentication-logic. Packages unchanged.
package eu.europa.ec.authenticationlogic.controller.authentication

/** Whether the device can do biometrics at all. */
sealed class BiometricsAvailability {
    data object CanAuthenticate : BiometricsAvailability()
    data object NonEnrolled : BiometricsAvailability()
    data class Failure(val errorMessage: String) : BiometricsAvailability()
}

/** The outcome of a biometric prompt. */
sealed class BiometricsAuthenticate {
    data object Success : BiometricsAuthenticate()
    data class Failed(val errorMessage: String) : BiometricsAuthenticate()
    data object Cancelled : BiometricsAuthenticate()
}

/**
 * The callbacks a caller hands to device authentication.
 *
 * `onAuthenticationSuccess` is `suspend` because callers chain further work onto it — the loading
 * screens authenticate once per document and delay between prompts.
 */
data class DeviceAuthenticationResult(
    val onAuthenticationSuccess: suspend () -> Unit = {},
    val onAuthenticationError: () -> Unit = {},
    val onAuthenticationFailure: () -> Unit = {},
)
