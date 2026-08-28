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

package eu.europa.ec.uilogic.container

import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import eu.europa.ec.businesslogic.controller.storage.PrefKeys
import eu.europa.ec.businesslogic.provider.UuidProvider
import eu.europa.ec.corelogic.di.getOrNullKoinScope
import eu.europa.ec.resourceslogic.theme.ThemeManager
import eu.europa.ec.shared.navigation.AddDocumentRoute
import eu.europa.ec.shared.navigation.AppNavigator
import eu.europa.ec.shared.navigation.DocumentDetailsRoute
import eu.europa.ec.shared.navigation.DocumentOfferRoute
import eu.europa.ec.uilogic.extension.exposeTestTagsAsResourceId
import eu.europa.ec.shared.navigation.LocalNavPlatformActions
import eu.europa.ec.uilogic.navigation.AndroidNavPlatformActions
import eu.europa.ec.uilogic.navigation.RouterHost
import eu.europa.ec.uilogic.navigation.helper.DeepLinkAction
import eu.europa.ec.uilogic.navigation.helper.DeepLinkType
import eu.europa.ec.uilogic.navigation.helper.IntentAction
import eu.europa.ec.uilogic.navigation.helper.IntentType
import eu.europa.ec.uilogic.navigation.helper.handleDeepLinkAction
import eu.europa.ec.uilogic.navigation.helper.hasDeepLink
import eu.europa.ec.uilogic.navigation.helper.hasIntentAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.annotation.KoinViewModel

open class EudiComponentActivity : FragmentActivity() {

    private val routerHost: RouterHost by inject()
    private val viewModel: EudiComponentActivityViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.onCreate()
    }

    internal fun cacheIntent(intent: Intent?) {
        viewModel.cacheIntent(intent)
    }

    internal fun getCachedIntent(): Intent? = viewModel.getCachedIntent()

    internal fun cacheIntentAction(intentAction: IntentAction) {
        viewModel.cacheIntentAction(intentAction)
    }

    internal fun consumePendingIntentAction(): IntentAction? = viewModel.consumePendingIntentAction()

    @Composable
    protected fun Content(
        intent: Intent?,
        entries: EntryProviderScope<NavKey>.(AppNavigator) -> Unit,
    ) {
        ThemeManager.instance.Theme {
            Surface(
                modifier = Modifier
                    .exposeTestTagsAsResourceId()
                    .fillMaxSize(),
                color = MaterialTheme.colorScheme.surface
            ) {
                // Android's answers for the shared entries. Provided here because this is the
                // activity whose one-shot intent slots they read, and everything the host composes
                // sits underneath. `LocalContext` is that activity, which `findActivity()` and the
                // slot extensions both rely on.
                val platformActions = remember(this) { AndroidNavPlatformActions(this) }
                CompositionLocalProvider(LocalNavPlatformActions provides platformActions) {
                    routerHost.StartFlow { navigator ->
                        entries(navigator)
                    }
                }
                LaunchedEffect(Unit) {
                    viewModel.onFlowStart()
                    handleDeepLink(intent, coldBoot = true)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (viewModel.hasFlowStarted()) {
            handleDeepLink(intent)
        } else {
            runPendingDeepLink(intent)
        }
    }

    private fun runPendingDeepLink(intent: Intent?) {
        lifecycleScope.launch {
            var count = 0
            while (!viewModel.hasFlowStarted() && count <= 10) {
                count++
                delay(500)
            }
            if (count <= 10) {
                handleDeepLink(intent)
            }
        }
    }

    private fun handleDeepLink(intent: Intent?, coldBoot: Boolean = false) {
        hasDeepLink(intent?.data)?.let {
            if (it.type == DeepLinkType.ISSUANCE && !coldBoot) {
                handleDeepLinkAction(
                    navigator = routerHost.getNavigator(),
                    context = this,
                    uri = it.link
                )
            } else if (
                it.type == DeepLinkType.CREDENTIAL_OFFER
                && !routerHost.userIsLoggedInWithDocuments()
                && routerHost.userIsLoggedInWithNoDocuments()
            ) {
                cacheIntent(intent)
                routerHost.popToIssuanceOnboardingScreen()
            } else if (it.type == DeepLinkType.OPENID4VP
                && routerHost.userIsLoggedInWithDocuments()
                && (routerHost.isRouteOnBackStackOrForeground(AddDocumentRoute::class)
                        || routerHost.isRouteOnBackStackOrForeground(DocumentOfferRoute::class)
                        || routerHost.isRouteOnBackStackOrForeground(DocumentDetailsRoute::class))
            ) {
                handleDeepLinkAction(
                    navigator = routerHost.getNavigator(),
                    context = this,
                    action = DeepLinkAction(it.link, DeepLinkType.DYNAMIC_PRESENTATION)
                )
            } else if (it.type != DeepLinkType.ISSUANCE) {
                cacheIntent(intent)
                if (routerHost.userIsLoggedInWithDocuments()) {
                    routerHost.popToDashboardScreen()
                }
            }
            setIntent(Intent())
        } ?: hasIntentAction(intent)?.let {
            when (it.type) {
                IntentType.DC_API -> {
                    cacheIntent(it.intent)
                    if (routerHost.userIsLoggedInWithDocuments()) {
                        routerHost.popToDashboardScreen()
                    }
                }
            }
            setIntent(Intent())
        }
    }
}

@KoinViewModel
internal class EudiComponentActivityViewModel(
    private val prefKeys: PrefKeys,
    uuidProvider: UuidProvider
) : ViewModel() {

    private val sessionId: String = uuidProvider.provideUuid()

    private var flowStarted: Boolean = false
    private var pendingIntent: Intent? = null
    private var pendingIntentAction: IntentAction? = null

    override fun onCleared() {
        getOrNullKoinScope(sessionId)?.close()
        super.onCleared()
    }

    fun onCreate() {
        setSessionId()
    }

    fun onResume() {
        setSessionId()
    }

    fun onFlowStart() {
        flowStarted = true
    }

    fun cacheIntent(intent: Intent?) {
        pendingIntent = intent
    }

    fun getCachedIntent(): Intent? = pendingIntent

    fun cacheIntentAction(intentAction: IntentAction) {
        pendingIntentAction = intentAction
    }

    /** One-shot: the destination that was pushed alongside the action reads it exactly once. */
    fun consumePendingIntentAction(): IntentAction? =
        pendingIntentAction.also { pendingIntentAction = null }

    fun hasFlowStarted(): Boolean = flowStarted

    private fun setSessionId() {
        runBlocking(Dispatchers.IO) {
            prefKeys.setSessionId(sessionId)
        }
    }
}