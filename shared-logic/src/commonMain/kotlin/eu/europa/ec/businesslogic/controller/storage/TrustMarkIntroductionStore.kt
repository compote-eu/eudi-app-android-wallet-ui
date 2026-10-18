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

// Whether the Trust Mark welcome has been completed — upstream bf514519 keeps it in Android's `PrefKeys`.
// Shared so the splash and the Trust Mark screen, both shared, can ask it on either platform. The method
// names are upstream's, so Android's `PrefKeys` answers it as it is.
package eu.europa.ec.businesslogic.controller.storage

interface TrustMarkIntroductionStore {
    suspend fun getTrustMarkIntroductionCompleted(): Boolean
    suspend fun setTrustMarkIntroductionCompleted(value: Boolean)
}
