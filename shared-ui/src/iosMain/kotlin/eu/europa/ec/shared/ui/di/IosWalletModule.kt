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
import eu.europa.ec.dashboardfeature.interactor.DashboardInteractor
import eu.europa.ec.dashboardfeature.interactor.DashboardInteractorImpl
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
import eu.europa.ec.authenticationlogic.config.AuthenticationConfig
import eu.europa.ec.authenticationlogic.controller.storage.PinStorageController
import eu.europa.ec.authenticationlogic.controller.throttle.PinThrottleController
import eu.europa.ec.authenticationlogic.storage.IosAuthenticationConfig
import eu.europa.ec.authenticationlogic.storage.IosPinStorage
import eu.europa.ec.authenticationlogic.storage.IosPinThrottle
import eu.europa.ec.commonfeature.interactor.BiometricInteractor
import eu.europa.ec.commonfeature.interactor.QuickPinInteractor
import eu.europa.ec.commonfeature.interactor.QuickPinInteractorImpl
import eu.europa.ec.startupfeature.interactor.SplashInteractor
import eu.europa.ec.startupfeature.interactor.SplashInteractorImpl
import eu.europa.ec.shared.wallet.multipaz.IosCredentialIssuer
import eu.europa.ec.shared.wallet.multipaz.IosCredentialOfferReader
import eu.europa.ec.shared.wallet.multipaz.IosOfferableCredentialsReader
import eu.europa.ec.shared.wallet.multipaz.IosProximityPresenter
import eu.europa.ec.proximityfeature.interactor.ProximityLoadingInteractor
import eu.europa.ec.proximityfeature.interactor.ProximityQRInteractor
import eu.europa.ec.proximityfeature.interactor.ProximityRequestInteractor
import eu.europa.ec.proximityfeature.interactor.ProximitySuccessInteractor
import eu.europa.ec.issuancefeature.interactor.AddDocumentInteractor
import eu.europa.ec.issuancefeature.interactor.AddDocumentInteractorImpl
import eu.europa.ec.issuancefeature.interactor.AddDocumentPlatformBridge
import eu.europa.ec.issuancefeature.interactor.DocumentIssuanceSuccessInteractor
import eu.europa.ec.issuancefeature.interactor.DocumentOfferInteractor
import eu.europa.ec.issuancefeature.interactor.DocumentOfferInteractorImpl
import eu.europa.ec.issuancefeature.interactor.DocumentOfferPlatformBridge
import eu.europa.ec.issuancefeature.interactor.DocumentIssuanceSuccessInteractorImpl
import eu.europa.ec.dashboardfeature.interactor.SettingsInteractor
import eu.europa.ec.dashboardfeature.interactor.SettingsInteractorImpl
import eu.europa.ec.dashboardfeature.interactor.SettingsPlatformBridge
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

/**
 * No bridge and no iOS-specific implementation: the dashboard interactor only builds the side menu's
 * list items out of strings and icons, so `DashboardInteractorImpl` in commonMain serves both platforms
 * unchanged. This definition exists purely because the Android graph declares its own in
 * `FeatureDashboardModule`, which iOS does not load.
 */
@Factory
fun provideIosDashboardInteractor(strings: StringCatalog): DashboardInteractor =
    DashboardInteractorImpl(strings = strings)

/**
 * iOS's settings screen. The bridge is where the platform's honest answers live — no biometrics, no
 * logs, no changelog, but a real app version and a real batch-counter preference.
 */
@Single
fun provideIosSettingsPlatformBridge(): SettingsPlatformBridge = IosSettingsPlatformBridge()

@Factory
fun provideIosSettingsInteractor(
    strings: StringCatalog,
    platform: SettingsPlatformBridge,
): SettingsInteractor = SettingsInteractorImpl(
    strings = strings,
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

/**
 * The screen the issuance flow ends on. Its implementation is shared, and the platform half it needs —
 * reading a document's claims and issuer display — is the document-details bridge, which iOS answers
 * from multipaz. So this needs nothing iOS-specific beyond being declared.
 */
@Factory
fun provideIosDocumentIssuanceSuccessInteractor(
    strings: StringCatalog,
    platform: DocumentDetailsPlatformBridge,
): DocumentIssuanceSuccessInteractor = DocumentIssuanceSuccessInteractorImpl(
    strings = strings,
    platform = platform,
)

/**
 * Which issuers iOS offers documents from, and what each one can issue. A `@Single` because the read is a
 * network round trip per issuer and multipaz caches the parsed metadata per client-preferences instance.
 */
@Single
fun provideIosOfferableCredentialsReader(): IosOfferableCredentialsReader =
    IosOfferableCredentialsReader()

/**
 * iOS issuance. Takes the engine rather than a store so credentials land in the same `DocumentStore` the
 * Documents list reads from.
 */
@Single
fun provideIosCredentialIssuer(engine: IosWalletEngine): IosCredentialIssuer =
    IosCredentialIssuer(walletEngine = engine)

/**
 * The add-document screen. Its list-building and routing are shared; the bridge reads the real catalogue
 * and issues for real — see [IosAddDocumentPlatformBridge].
 */
@Single
fun provideIosAddDocumentPlatformBridge(
    offerableCredentials: IosOfferableCredentialsReader,
    credentialIssuer: IosCredentialIssuer,
): AddDocumentPlatformBridge = IosAddDocumentPlatformBridge(
    offerableCredentials = offerableCredentials,
    credentialIssuer = credentialIssuer,
)

@Factory
fun provideIosAddDocumentInteractor(
    strings: StringCatalog,
    platform: AddDocumentPlatformBridge,
): AddDocumentInteractor = AddDocumentInteractorImpl(
    strings = strings,
    platform = platform,
)

/**
 * The credential-offer screens. As with add-document, the rules are shared and the bridge is where iOS
 * says what it cannot do yet — see [IosDocumentOfferPlatformBridge].
 */
@Single
fun provideIosCredentialOfferReader(): IosCredentialOfferReader = IosCredentialOfferReader()

// `@Single`, not a factory: it remembers the offers it resolved, which issuance then requires.
@Single
fun provideIosDocumentOfferPlatformBridge(
    offers: IosCredentialOfferReader,
    credentialIssuer: IosCredentialIssuer,
): DocumentOfferPlatformBridge = IosDocumentOfferPlatformBridge(
    offers = offers,
    credentialIssuer = credentialIssuer,
)

@Factory
fun provideIosDocumentOfferInteractor(
    strings: StringCatalog,
    walletEngine: WalletEngine,
    platform: DocumentOfferPlatformBridge,
): DocumentOfferInteractor = DocumentOfferInteractorImpl(
    strings = strings,
    walletEngine = walletEngine,
    platform = platform,
)

/**
 * The login gate. The PIN policy is shared; what iOS brings is where the verifier lives (the Keychain) and
 * how long a wrong PIN costs (`NSUserDefaults`, since a lockout is not a secret).
 */
@Single
fun provideIosAuthenticationConfig(): AuthenticationConfig = IosAuthenticationConfig()

@Single
fun provideIosPinStorage(): PinStorageController = IosPinStorage()

@Single
fun provideIosPinThrottle(config: AuthenticationConfig): PinThrottleController =
    IosPinThrottle(authenticationConfig = config)

@Factory
fun provideIosQuickPinInteractor(
    strings: StringCatalog,
    pinStorage: PinStorageController,
    pinThrottle: PinThrottleController,
    config: AuthenticationConfig,
): QuickPinInteractor = QuickPinInteractorImpl(
    pinStorageController = pinStorage,
    strings = strings,
    pinThrottleController = pinThrottle,
    authenticationConfig = config,
)

@Factory
fun provideIosBiometricInteractor(quickPinInteractor: QuickPinInteractor): BiometricInteractor =
    IosBiometricInteractor(quickPinInteractor = quickPinInteractor)

/**
 * The real splash routing, replacing the stand-in that always went to the dashboard: no PIN yet sends the
 * user to create one, an existing PIN sends them to unlock. `forcePidActivation` is false here — iOS has no
 * configuration layer, so it does not insist on a PID before anything else.
 */
@Factory
fun provideIosSplashInteractor(
    quickPinInteractor: QuickPinInteractor,
    walletEngine: WalletEngine,
): SplashInteractor = SplashInteractorImpl(
    quickPinInteractor = quickPinInteractor,
    walletEngine = walletEngine,
    forcePidActivation = { false },
)

/**
 * ISO 18013-5 proximity sharing.
 *
 * `@Single` for both the presenter and the coordinator: one Bluetooth radio means one exchange, and the
 * four screens have to be looking at the same one. The interactors are `@Factory` like Android's, since
 * each is a per-view-model view onto that shared state and holds nothing but its scope id.
 */
@Single
fun provideIosProximityPresenter(engine: IosWalletEngine): IosProximityPresenter =
    IosProximityPresenter(walletEngine = engine)

@Single
internal fun provideIosProximityCoordinator(
    presenter: IosProximityPresenter,
    strings: StringCatalog,
): IosProximityCoordinator = IosProximityCoordinator(
    presenter = presenter,
    strings = strings,
)

@Factory
internal fun provideIosProximityQRInteractor(
    coordinator: IosProximityCoordinator,
): ProximityQRInteractor = IosProximityQRInteractor(coordinator = coordinator)

@Factory
internal fun provideIosProximityRequestInteractor(
    coordinator: IosProximityCoordinator,
): ProximityRequestInteractor = IosProximityRequestInteractor(coordinator = coordinator)

@Factory
internal fun provideIosProximityLoadingInteractor(
    coordinator: IosProximityCoordinator,
): ProximityLoadingInteractor = IosProximityLoadingInteractor(coordinator = coordinator)

@Factory
internal fun provideIosProximitySuccessInteractor(
    coordinator: IosProximityCoordinator,
): ProximitySuccessInteractor = IosProximitySuccessInteractor(coordinator = coordinator)
