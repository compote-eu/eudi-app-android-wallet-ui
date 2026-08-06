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

// Split out of :issuance-feature's FeatureIssuanceModule.kt so `DocumentOfferViewModel` can resolve
// and close its scope from commonMain — the same move `eu.europa.ec.corelogic.di.KoinScopes` already
// made for the presentation scope. Already KMP: koin-core, `KoinPlatform` and koin-annotations are
// all multiplatform, and only the file this lived in was Android.
//
// The scope's *contents* stay in :issuance-feature: `provideDocumentOfferInteractor` builds an
// Android interactor and must not follow. Package unchanged.
package eu.europa.ec.issuancefeature.di

import org.koin.core.annotation.Scope
import org.koin.core.scope.Scope as KoinScope
import org.koin.mp.KoinPlatform

internal const val CREDENTIAL_OFFER_ISSUANCE_SCOPE_ID = "credential_offer_scope_id"

/** Qualifier for the scope a credential-offer issuance flow lives in. */
@Scope
class CredentialOfferIssuanceScope

fun getOrCreateCredentialOfferScope(
    scopeId: String = CREDENTIAL_OFFER_ISSUANCE_SCOPE_ID,
): KoinScope = KoinPlatform.getKoin().getOrCreateScope<CredentialOfferIssuanceScope>(scopeId)

fun getOrNullCredentialOfferScope(
    scopeId: String = CREDENTIAL_OFFER_ISSUANCE_SCOPE_ID,
): KoinScope? = KoinPlatform.getKoin().getScopeOrNull(scopeId)
