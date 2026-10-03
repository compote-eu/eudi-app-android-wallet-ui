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

// The contract of upstream bf514519's controller, shared: Android implements it over wallet-core's
// `trustMarkManager` (`WalletCoreTrustMarkControllerImpl`), iOS by fetching the resource itself
// (`IosTrustMarkController`).
package eu.europa.ec.corelogic.controller

import eu.europa.ec.corelogic.model.TrustMarkDomain

interface WalletCoreTrustMarkController {
    suspend fun getTrustMark(): Result<TrustMarkDomain>
}
