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

import eu.europa.ec.dashboardfeature.interactor.HomeInteractor
import eu.europa.ec.shared.resources.StringResolver
import eu.europa.ec.shared.wallet.WalletEngine
import eu.europa.ec.shared.wallet.multipaz.IosWalletEngine
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
 */
@Single
fun provideIosWalletEngine(): WalletEngine = IosWalletEngine()

@Single
fun provideIosHomeInteractor(
    walletEngine: WalletEngine,
    stringResolver: StringResolver,
): HomeInteractor = IosHomeInteractor(
    walletEngine = walletEngine,
    stringResolver = stringResolver,
)
