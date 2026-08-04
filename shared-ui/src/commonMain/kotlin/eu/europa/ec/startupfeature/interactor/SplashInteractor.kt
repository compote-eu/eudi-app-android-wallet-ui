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

// Phase 3b: the *interface* is KMP (a suspend function returning a typed AppRoute), so it lives in
// commonMain next to `SplashViewModel`, which is the only thing that depends on it. The
// implementation stays in :startup-feature — it needs Android-side collaborators (ResourceProvider,
// ConfigLogic, QuickPinInteractor); iOS will supply its own. Package unchanged.
package eu.europa.ec.startupfeature.interactor

import eu.europa.ec.shared.navigation.AppRoute

interface SplashInteractor {
    suspend fun getAfterSplashRoute(): AppRoute
}
