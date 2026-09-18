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

import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.commonfeature.config.PresentationMode
import eu.europa.ec.commonfeature.config.RequestUriConfig
import eu.europa.ec.shared.wallet.multipaz.IosRemotePresentationState
import eu.europa.ec.shared.wallet.multipaz.IosRemotePresenter
import eu.europa.ec.shared.wallet.multipaz.IosWalletEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * A screen tearing down must not end somebody else's exchange.
 *
 * 🚨 Watched twice on a simulator on 2026-09-17, and the reason this test exists at all: a presentation
 * arriving while a previous presentation screen was still up was cancelled **~265 ms after asking for
 * consent** — the shared `onCleared()` calls `stopPresentation()` as the outgoing screen is replaced,
 * and on iOS the presenter and coordinator are `@Single`, so that teardown reached the *newcomer*. The
 * decline path then correctly told the verifier the user had refused a request they were never shown.
 *
 * ⛔ Android cannot have this: its controller lives in a per-presentation Koin scope, so `onCleared()`
 * can only ever reach its own. The hazard is created by our DI choice, so the fix belongs here too —
 * and it is an identity, not a scope, because three screens really are views onto one exchange.
 */
class IosPresentationExchangeIdTest {

    /** Unroutable on purpose: the exchange must *start* here, not get anywhere. */
    private fun config(uri: String = "openid4vp://?request_uri=http://127.0.0.1:1/never") =
        RequestUriConfig(
            mode = PresentationMode.OpenId4Vp(uri = uri, initiatorRoute = DashboardRoute),
        )

    private fun coordinator(): Pair<IosRemotePresentationCoordinator, IosRemotePresenter> {
        val presenter = IosRemotePresenter(walletEngine = IosWalletEngine())
        return IosRemotePresentationCoordinator(presenter, SilentStringCatalog) to presenter
    }

    @Test
    fun each_start_is_a_new_exchange() = runTest {
        val (coordinator, _) = coordinator()

        val first = coordinator.exchangeId
        coordinator.start(config())
        val second = coordinator.exchangeId
        coordinator.start(config())

        assertNotEquals(first, second)
        assertNotEquals(second, coordinator.exchangeId)
    }

    @Test
    fun a_teardown_from_an_older_exchange_does_not_end_the_current_one() = runTest {
        val (coordinator, presenter) = coordinator()
        coordinator.start(config())
        val olderExchange = coordinator.exchangeId

        // A second link arrives and its screen starts the new exchange…
        coordinator.start(config())
        // …and only then does the first screen's `onCleared()` run.
        coordinator.cancel(ownedExchangeId = olderExchange)

        // The newcomer survives. Before this it was killed here, and the verifier was told the user
        // had declined a request nobody had seen.
        assertNotEquals(
            IosRemotePresentationState.Idle,
            presenter.state.value,
            "a stale teardown must not cancel the running exchange",
        )
    }

    @Test
    fun a_teardown_from_the_current_exchange_still_ends_it() = runTest {
        val (coordinator, presenter) = coordinator()
        coordinator.start(config())

        coordinator.cancel(ownedExchangeId = coordinator.exchangeId)

        // The ordinary case — back button, or the stop the request screen offers — must keep working.
        assertEquals(IosRemotePresentationState.Idle, presenter.state.value)
    }

    @Test
    fun a_caller_that_names_no_exchange_still_ends_whatever_is_running() = runTest {
        val (coordinator, presenter) = coordinator()
        coordinator.start(config())

        coordinator.cancel()

        // The default is kept for callers that genuinely mean "stop everything", such as the presenter
        // being torn down with the app.
        assertEquals(IosRemotePresentationState.Idle, presenter.state.value)
    }
}

/** Nothing here reads a string; the coordinator simply requires one. */
private object SilentStringCatalog : eu.europa.ec.shared.resources.StringCatalog {
    override fun get(resource: org.jetbrains.compose.resources.StringResource): String = ""
    override fun get(resource: org.jetbrains.compose.resources.StringResource, vararg args: Any): String = ""
    override suspend fun warm() = Unit
}
