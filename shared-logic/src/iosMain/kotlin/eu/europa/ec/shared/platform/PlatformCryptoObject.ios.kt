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

package eu.europa.ec.shared.platform

/**
 * Uninhabited, and it stays that way even though iOS unlocks with Face ID.
 *
 * This is Android's `BiometricPrompt.CryptoObject` — a cipher handed to the prompt so the key is
 * released by the authentication itself. iOS expresses the same guarantee differently: the key lives in
 * the Keychain behind `kSecAccessControlBiometryCurrentSet` and the Secure Enclave releases it, so
 * there is no object to pass and nothing for this type to hold. See `IosBiometricGate`.
 */
actual class PlatformCryptoObject private constructor()
