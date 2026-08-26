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

package eu.europa.ec.dashboardfeature.di

import eu.europa.ec.businesslogic.config.ConfigLogic
import eu.europa.ec.businesslogic.controller.log.LogController
import eu.europa.ec.businesslogic.controller.storage.PrefKeys
import eu.europa.ec.corelogic.provider.RegistrationCheckProvider
import eu.europa.ec.businesslogic.provider.UuidProvider
import eu.europa.ec.businesslogic.validator.FilterValidator
import eu.europa.ec.commonfeature.interactor.BiometricInteractor
import eu.europa.ec.commonfeature.interactor.DeviceAuthenticationInteractor
import eu.europa.ec.corelogic.config.WalletCoreConfig
import eu.europa.ec.corelogic.controller.WalletCoreDocumentsController
import eu.europa.ec.shared.resources.StringCatalog
import eu.europa.ec.shared.wallet.WalletEngine
import eu.europa.ec.dashboardfeature.interactor.DashboardInteractor
import eu.europa.ec.dashboardfeature.interactor.DashboardInteractorImpl
import eu.europa.ec.dashboardfeature.interactor.DocumentDetailsInteractor
import eu.europa.ec.dashboardfeature.interactor.AndroidDocumentDetailsPlatformBridge
import eu.europa.ec.dashboardfeature.interactor.DocumentDetailsInteractorImpl
import eu.europa.ec.dashboardfeature.interactor.DocumentDetailsPlatformBridge
import eu.europa.ec.dashboardfeature.interactor.DocumentSignInteractor
import eu.europa.ec.dashboardfeature.interactor.DocumentSignInteractorImpl
import eu.europa.ec.dashboardfeature.interactor.DocumentsInteractor
import eu.europa.ec.dashboardfeature.interactor.AndroidDocumentsPlatformBridge
import eu.europa.ec.dashboardfeature.interactor.DocumentsInteractorImpl
import eu.europa.ec.dashboardfeature.interactor.DocumentsPlatformBridge
import eu.europa.ec.dashboardfeature.interactor.HomeInteractor
import eu.europa.ec.dashboardfeature.interactor.HomeInteractorImpl
import eu.europa.ec.dashboardfeature.interactor.AndroidSettingsPlatformBridge
import eu.europa.ec.dashboardfeature.interactor.SettingsInteractor
import eu.europa.ec.dashboardfeature.interactor.SettingsInteractorImpl
import eu.europa.ec.dashboardfeature.interactor.SettingsPlatformBridge
import eu.europa.ec.dashboardfeature.interactor.TransactionDetailsInteractor
import eu.europa.ec.dashboardfeature.interactor.TransactionDetailsInteractorImpl
import eu.europa.ec.dashboardfeature.interactor.TransactionsInteractor
import eu.europa.ec.dashboardfeature.interactor.AndroidTransactionsPlatformBridge
import eu.europa.ec.dashboardfeature.interactor.TransactionsInteractorImpl
import eu.europa.ec.dashboardfeature.interactor.TransactionsPlatformBridge
import eu.europa.ec.resourceslogic.provider.ResourceProvider
import eu.europa.ec.shared.resources.StringResolver
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Module

@Module
@Configuration
@ComponentScan("eu.europa.ec.dashboardfeature")
class FeatureDashboardModule

@Factory
fun provideDashboardInteractor(
    strings: StringCatalog,
): DashboardInteractor = DashboardInteractorImpl(
    strings = strings,
)

/**
 * Android's half of the settings screen: the build's version and changelog URL, the log files, the
 * batch-counter preference and the real biometric prompt.
 */
@Factory
fun provideSettingsPlatformBridge(
    biometricInteractor: BiometricInteractor,
    configLogic: ConfigLogic,
    logController: LogController,
    prefKeys: PrefKeys,
    registrationCheckProvider: RegistrationCheckProvider,
): SettingsPlatformBridge = AndroidSettingsPlatformBridge(
    biometricInteractor = biometricInteractor,
    configLogic = configLogic,
    logController = logController,
    prefKeys = prefKeys,
    registrationCheckProvider = registrationCheckProvider,
)

@Factory
fun provideSettingsInteractor(
    strings: StringCatalog,
    platform: SettingsPlatformBridge,
): SettingsInteractor = SettingsInteractorImpl(
    strings = strings,
    platform = platform,
)

@Factory
fun provideHomeInteractor(
    resourceProvider: ResourceProvider,
    walletEngine: WalletEngine,
    walletCoreConfig: WalletCoreConfig,
): HomeInteractor = HomeInteractorImpl(
    resourceProvider,
    walletEngine,
    walletCoreConfig,
)

/**
 * Android's half of the documents feature: wallet-core deferred issuance and deletion, plus the
 * preferences and build config the shared interactor cannot read for itself.
 */
@Factory
fun provideDocumentsPlatformBridge(
    resourceProvider: ResourceProvider,
    documentsController: WalletCoreDocumentsController,
    walletCoreConfig: WalletCoreConfig,
    configLogic: ConfigLogic,
    prefKeys: PrefKeys,
): DocumentsPlatformBridge =
    AndroidDocumentsPlatformBridge(
        resourceProvider = resourceProvider,
        walletCoreDocumentsController = documentsController,
        walletCoreConfig = walletCoreConfig,
        configLogic = configLogic,
        prefKeys = prefKeys,
    )

@Factory
fun provideDocumentsInteractor(
    strings: StringCatalog,
    walletEngine: WalletEngine,
    filterValidator: FilterValidator,
    platform: DocumentsPlatformBridge,
): DocumentsInteractor =
    DocumentsInteractorImpl(
        strings = strings,
        walletEngine = walletEngine,
        filterValidator = filterValidator,
        platform = platform,
    )

/** Android's transaction-log reader; see `AndroidTransactionsPlatformBridge` for the mapping. */
@Factory
fun provideTransactionsPlatformBridge(
    walletCoreDocumentsController: WalletCoreDocumentsController,
    resourceProvider: ResourceProvider,
): TransactionsPlatformBridge = AndroidTransactionsPlatformBridge(
    walletCoreDocumentsController = walletCoreDocumentsController,
    resourceProvider = resourceProvider,
)

@Factory
fun provideTransactionInteractor(
    strings: StringCatalog,
    stringResolver: StringResolver,
    filterValidator: FilterValidator,
    platform: TransactionsPlatformBridge,
): TransactionsInteractor = TransactionsInteractorImpl(
    strings = strings,
    stringResolver = stringResolver,
    filterValidator = filterValidator,
    platform = platform,
)

@Factory
fun provideDocumentSignInteractor(
    stringCatalog: StringCatalog,
): DocumentSignInteractor = DocumentSignInteractorImpl(
    stringCatalog,
)

/**
 * Android's half of the details feature: the wallet-core claim tree, deletion, re-issuance and the
 * biometric prompt.
 */
@Factory
fun provideDocumentDetailsPlatformBridge(
    walletCoreDocumentsController: WalletCoreDocumentsController,
    walletEngine: WalletEngine,
    deviceAuthenticationInteractor: DeviceAuthenticationInteractor,
    resourceProvider: ResourceProvider,
    uuidProvider: UuidProvider,
    configLogic: ConfigLogic,
    prefKeys: PrefKeys,
): DocumentDetailsPlatformBridge =
    AndroidDocumentDetailsPlatformBridge(
        walletCoreDocumentsController = walletCoreDocumentsController,
        walletEngine = walletEngine,
        deviceAuthenticationInteractor = deviceAuthenticationInteractor,
        resourceProvider = resourceProvider,
        uuidProvider = uuidProvider,
        configLogic = configLogic,
        prefKeys = prefKeys,
    )

@Factory
fun provideDocumentDetailsInteractor(
    strings: StringCatalog,
    walletEngine: WalletEngine,
    platform: DocumentDetailsPlatformBridge,
): DocumentDetailsInteractor =
    DocumentDetailsInteractorImpl(
        strings = strings,
        walletEngine = walletEngine,
        platform = platform,
    )

@Factory
fun provideTransactionDetailsInteractor(
    walletCoreDocumentsController: WalletCoreDocumentsController,
    resourceProvider: ResourceProvider,
    uuidProvider: UuidProvider
): TransactionDetailsInteractor =
    TransactionDetailsInteractorImpl(
        walletCoreDocumentsController,
        resourceProvider,
        uuidProvider
    )