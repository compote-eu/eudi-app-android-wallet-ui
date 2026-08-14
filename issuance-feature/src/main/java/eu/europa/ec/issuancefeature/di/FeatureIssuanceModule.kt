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

package eu.europa.ec.issuancefeature.di

import eu.europa.ec.businesslogic.config.ConfigLogic
import eu.europa.ec.commonfeature.interactor.DeviceAuthenticationInteractor
import eu.europa.ec.corelogic.controller.WalletCoreDocumentsController
import eu.europa.ec.dashboardfeature.interactor.DocumentDetailsPlatformBridge
import eu.europa.ec.shared.resources.StringCatalog
import eu.europa.ec.shared.wallet.WalletEngine
import eu.europa.ec.issuancefeature.interactor.AddDocumentInteractor
import eu.europa.ec.issuancefeature.interactor.AddDocumentInteractorImpl
import eu.europa.ec.issuancefeature.interactor.AddDocumentPlatformBridge
import eu.europa.ec.issuancefeature.interactor.AndroidAddDocumentPlatformBridge
import eu.europa.ec.issuancefeature.interactor.DocumentIssuanceSuccessInteractor
import eu.europa.ec.issuancefeature.interactor.DocumentIssuanceSuccessInteractorImpl
import eu.europa.ec.issuancefeature.interactor.DocumentOfferInteractor
import eu.europa.ec.issuancefeature.interactor.AndroidDocumentOfferPlatformBridge
import eu.europa.ec.issuancefeature.interactor.DocumentOfferInteractorImpl
import eu.europa.ec.issuancefeature.interactor.DocumentOfferPlatformBridge
import eu.europa.ec.resourceslogic.provider.ResourceProvider
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Module
import org.koin.core.annotation.Scope
import org.koin.core.annotation.Scoped


@Module
@Configuration
@ComponentScan("eu.europa.ec.issuancefeature")
class FeatureIssuanceModule

@Factory
fun provideAddDocumentPlatformBridge(
    walletCoreDocumentsController: WalletCoreDocumentsController,
    deviceAuthenticationInteractor: DeviceAuthenticationInteractor,
    resourceProvider: ResourceProvider,
): AddDocumentPlatformBridge = AndroidAddDocumentPlatformBridge(
    walletCoreDocumentsController,
    deviceAuthenticationInteractor,
    resourceProvider,
)

// Shared implementation; only the wallet-core call surface above is Android's.
@Factory
fun provideAddDocumentInteractor(
    strings: StringCatalog,
    platformBridge: AddDocumentPlatformBridge,
): AddDocumentInteractor = AddDocumentInteractorImpl(
    strings,
    platformBridge,
)

// Shared implementation: the platform half is `DocumentDetailsPlatformBridge`, which the dashboard
// feature already provides.
@Factory
fun provideDocumentIssuanceSuccessInteractor(
    strings: StringCatalog,
    platformBridge: DocumentDetailsPlatformBridge,
): DocumentIssuanceSuccessInteractor = DocumentIssuanceSuccessInteractorImpl(
    strings,
    platformBridge,
)

// Scoped, not a factory: this holds the resolved offer that issuance then needs.
@Scope(CredentialOfferIssuanceScope::class)
@Scoped
fun provideDocumentOfferPlatformBridge(
    walletCoreDocumentsController: WalletCoreDocumentsController,
    deviceAuthenticationInteractor: DeviceAuthenticationInteractor,
    resourceProvider: ResourceProvider,
    configLogic: ConfigLogic,
): DocumentOfferPlatformBridge = AndroidDocumentOfferPlatformBridge(
    walletCoreDocumentsController,
    deviceAuthenticationInteractor,
    resourceProvider,
    configLogic,
)

@Scope(CredentialOfferIssuanceScope::class)
@Scoped
fun provideDocumentOfferInteractor(
    strings: StringCatalog,
    walletEngine: WalletEngine,
    platformBridge: DocumentOfferPlatformBridge,
): DocumentOfferInteractor = DocumentOfferInteractorImpl(
    strings,
    walletEngine,
    platformBridge,
)
