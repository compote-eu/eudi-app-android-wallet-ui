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

package eu.europa.ec.shared.ui.di

import eu.europa.ec.businesslogic.validator.FilterValidator
import eu.europa.ec.businesslogic.validator.FilterValidatorImpl
import eu.europa.ec.dashboardfeature.interactor.DocumentDetailsInteractor
import eu.europa.ec.dashboardfeature.interactor.DocumentDetailsInteractorImpl
import eu.europa.ec.dashboardfeature.interactor.DocumentDetailsPlatformBridge
import eu.europa.ec.dashboardfeature.interactor.DocumentsInteractor
import eu.europa.ec.dashboardfeature.interactor.DocumentsInteractorImpl
import eu.europa.ec.dashboardfeature.interactor.TransactionsInteractor
import eu.europa.ec.dashboardfeature.interactor.TransactionsInteractorImpl
import eu.europa.ec.dashboardfeature.interactor.TransactionsPlatformBridge
import eu.europa.ec.dashboardfeature.interactor.DocumentsPlatformBridge
import eu.europa.ec.dashboardfeature.interactor.HomeInteractor
import eu.europa.ec.shared.resources.StringCatalog
import eu.europa.ec.uilogic.navigation.helper.DeepLinkClassifier
import eu.europa.ec.shared.resources.StringResolver
import eu.europa.ec.shared.wallet.WalletEngine
import eu.europa.ec.shared.wallet.multipaz.IosWalletEngine
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Single

/**
 * The iOS half of the DI graph, alongside the shared definitions in `SharedUiModule`.
 *
 * These live in **iosMain** and are picked up automatically: Koin's compiler plugin runs per
 * *compilation*, and `SharedUiModule`'s `@ComponentScan("eu.europa.ec")` therefore sees commonMain +
 * iosMain when compiling for iOS and commonMain + androidMain when compiling for Android. So Android
 * keeps its own `WalletEngine` (core-logic's wallet-core-backed one) and never sees these, without any
 * conditional wiring.
 *
 * `@Single` rather than `@Factory` for the engine: it lazily opens the multipaz document store, and one
 * store per process is the point.
 *
 * The engine is bound under **both** types from one instance — the concrete class for what is not on the
 * `WalletEngine` contract (deletion), the interface for everything else.
 */
@Single
fun provideIosWalletEngineImpl(): IosWalletEngine = IosWalletEngine()

@Single
fun provideIosWalletEngine(engine: IosWalletEngine): WalletEngine = engine

@Single
fun provideIosFilterValidator(): FilterValidator = FilterValidatorImpl()

@Single
fun provideIosDocumentsPlatformBridge(engine: IosWalletEngine): DocumentsPlatformBridge =
    IosDocumentsPlatformBridge(
        deleteDocument = { documentId -> engine.deleteDocument(documentId) },
        hasAnyDocument = { engine.hasAnyDocument() },
    )

@Single
fun provideIosDocumentsInteractor(
    strings: StringCatalog,
    walletEngine: WalletEngine,
    filterValidator: FilterValidator,
    platform: DocumentsPlatformBridge,
): DocumentsInteractor = DocumentsInteractorImpl(
    strings = strings,
    walletEngine = walletEngine,
    filterValidator = filterValidator,
    platform = platform,
)

@Single
fun provideIosDeepLinkClassifier(): DeepLinkClassifier = IosDeepLinkClassifier()

@Single
fun provideIosDocumentDetailsPlatformBridge(engine: IosWalletEngine): DocumentDetailsPlatformBridge =
    IosDocumentDetailsPlatformBridge(engine = engine)

/**
 * `@Factory`, not `@Single`, mirroring Android: the details interactor is scoped to one document's
 * screen, so a new one per view-model is correct.
 */
@Factory
fun provideIosDocumentDetailsInteractor(
    strings: StringCatalog,
    walletEngine: WalletEngine,
    platform: DocumentDetailsPlatformBridge,
): DocumentDetailsInteractor = DocumentDetailsInteractorImpl(
    strings = strings,
    walletEngine = walletEngine,
    platform = platform,
)

@Single
fun provideIosTransactionsPlatformBridge(): TransactionsPlatformBridge =
    IosTransactionsPlatformBridge()

@Factory
fun provideIosTransactionsInteractor(
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

@Single
fun provideIosHomeInteractor(
    walletEngine: WalletEngine,
    stringResolver: StringResolver,
): HomeInteractor = IosHomeInteractor(
    walletEngine = walletEngine,
    stringResolver = stringResolver,
)
