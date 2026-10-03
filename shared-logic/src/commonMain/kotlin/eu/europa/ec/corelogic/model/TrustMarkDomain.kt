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

// Upstream bf514519's Trust Mark domain, in shared code: Android reads it through wallet-core's
// `trustMarkManager`, iOS fetches the same EC-hosted resource itself.
package eu.europa.ec.corelogic.model

data class TrustMarkDomain(
    val resourceUrl: String,
    val imageName: String,
    val imageUrl: String,
    val localisedText: String?,
    val certifiedWalletsUrl: String,
    val walletSolutionUrl: String,
)

/**
 * Where a wallet's Trust Mark lives — wallet-core's `TrustMarkInformation`, platform-neutral: the
 * EC-hosted resource with the logo and localised text, and the two pages the screen links to.
 */
data class TrustMarkInformationDomain(
    val resourceUrl: String,
    val certifiedWalletsUrl: String,
    val walletSolutionUrl: String,
)
