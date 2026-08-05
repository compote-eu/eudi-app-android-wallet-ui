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

// Phase 3b: moved to commonMain because it rides inside `AuthenticationData` through the loading
// view-models. The `cryptoObject` is now the opaque `PlatformCryptoObject` handle instead of naming
// `BiometricPrompt.CryptoObject`; on Android that is an actual typealias for exactly that type, so
// every producer and consumer is unchanged. Package unchanged.
package eu.europa.ec.authenticationlogic.model

import eu.europa.ec.shared.platform.PlatformCryptoObject

data class BiometricCrypto(val cryptoObject: PlatformCryptoObject?)
