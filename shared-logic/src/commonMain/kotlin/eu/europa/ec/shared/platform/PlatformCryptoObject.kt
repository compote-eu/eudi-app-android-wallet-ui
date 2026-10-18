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

// Phase 3b, platform-handle layer. See PlatformActivity.kt for the pattern.
package eu.europa.ec.shared.platform

/**
 * An opaque handle on a platform biometric crypto object — Android's
 * `BiometricPrompt.CryptoObject`.
 *
 * Shared code never creates or inspects one. It exists because [eu.europa.ec.authenticationlogic.model.BiometricCrypto]
 * travels from a wallet-core controller, through a view-model, into the biometric prompt: the
 * view-model is only a courier, but it has to name the type.
 */
expect class PlatformCryptoObject
