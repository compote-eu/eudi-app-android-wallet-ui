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
 * Uninhabited for the same reason as [PlatformActivity]: iOS has no Context, and nothing on that
 * platform produces one.
 *
 * It used to say "when iOS grows biometric prompts this becomes whatever they need". iOS grew them —
 * `IosBiometricGate` — and needed nothing here: `LAContext` is created where it is used, so the prompt
 * never had to be passed a platform context. Left uninhabited rather than deleted because the shared
 * signatures that mention it are Android's, and they still need the type to exist.
 */
actual abstract class PlatformContext private constructor()
