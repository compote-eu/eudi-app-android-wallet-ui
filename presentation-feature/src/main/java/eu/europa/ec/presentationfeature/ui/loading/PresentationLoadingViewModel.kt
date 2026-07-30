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

package eu.europa.ec.presentationfeature.ui.loading

import android.content.Context
import androidx.lifecycle.viewModelScope
import eu.europa.ec.authenticationlogic.controller.authentication.DeviceAuthenticationResult
import eu.europa.ec.commonfeature.config.PresentationMode
import eu.europa.ec.commonfeature.config.RequestUriConfig
import eu.europa.ec.commonfeature.ui.loading.Effect
import eu.europa.ec.commonfeature.ui.loading.Event
import eu.europa.ec.commonfeature.ui.loading.LoadingViewModel
import eu.europa.ec.corelogic.model.AuthenticationData
import eu.europa.ec.presentationfeature.interactor.PresentationLoadingInteractor
import eu.europa.ec.presentationfeature.interactor.PresentationLoadingObserveResponsePartialState
import eu.europa.ec.presentationfeature.interactor.PresentationLoadingSendRequestedDocumentPartialState
import eu.europa.ec.resourceslogic.R
import eu.europa.ec.resourceslogic.provider.ResourceProvider
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.navigation.PresentationLoadingRoute
import eu.europa.ec.shared.navigation.PresentationRequestRoute
import eu.europa.ec.shared.navigation.PresentationSuccessRoute
import eu.europa.ec.uilogic.component.content.ContentErrorConfig
import eu.europa.ec.uilogic.component.content.ContentHeaderConfig
import eu.europa.ec.uilogic.config.NavigationType
import eu.europa.ec.uilogic.navigation.PresentationScreens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@KoinViewModel
class PresentationLoadingViewModel(
    private val resourceProvider: ResourceProvider,
    private val interactor: PresentationLoadingInteractor,
    @InjectedParam private val presentationScopeId: String
) : LoadingViewModel() {

    override fun getHeaderConfig(): ContentHeaderConfig {
        return ContentHeaderConfig(
            description = resourceProvider.getString(R.string.loading_header_description),
        )
    }

    /**
     * [PresentationLoadingRoute] only carries the scope id, so the request screen's original
     * [RequestUriConfig] is not reachable from here — the config below is a placeholder.
     *
     * It is inert today: every consumer of this value goes through `toLegacyScreen()`, which maps
     * to `PresentationScreens.PresentationRequest` and discards the config, so Nav2 pops by route
     * *pattern* exactly as before.
     *
     * Stage 5 must not pop by value: `AppNavigator.popUpTo` matches with `==`, so a rebuilt config
     * would silently fail to find the real back-stack entry. That applies to every config-carrying
     * pop target (see the `popUpTo = AddDocumentRoute(...)` sites too), not just this placeholder —
     * the fix is to match pop targets by destination identity/type rather than by value.
     */
    override fun getPreviousRoute(): AppRoute {
        return PresentationRequestRoute(
            RequestUriConfig(
                mode = PresentationMode.OpenId4Vp(
                    uri = "",
                    initiatorRoute = DashboardRoute,
                )
            )
        )
    }

    override fun getCallerRoute(): AppRoute {
        return PresentationLoadingRoute(presentationScopeId)
    }

    private fun getNextRoute(): AppRoute {
        return PresentationSuccessRoute(presentationScopeId)
    }

    override fun getCancellableTimeout(): Duration = 5.toDuration(DurationUnit.SECONDS)

    override fun doWork(context: Context) {
        viewModelScope.launch {

            interactor.setScopeId(presentationScopeId)

            interactor.observeResponse().collect {
                when (it) {
                    is PresentationLoadingObserveResponsePartialState.Failure -> {
                        setState {
                            copy(
                                error = ContentErrorConfig(
                                    onRetry = { setEvent(Event.DoWork(context)) },
                                    errorSubTitle = it.error,
                                    onCancel = {
                                        setEvent(Event.DismissError)
                                        doNavigation(NavigationType.PopTo(getPreviousRoute()))
                                    }
                                )
                            )
                        }
                    }

                    is PresentationLoadingObserveResponsePartialState.Success -> {
                        onSuccess()
                    }

                    is PresentationLoadingObserveResponsePartialState.Redirect -> {
                        onSuccess()
                    }

                    is PresentationLoadingObserveResponsePartialState.RequestReadyToBeSent -> {
                        sendRequestedDocuments(event = Event.DoWork(context))
                    }

                    is PresentationLoadingObserveResponsePartialState.UserAuthenticationRequired -> {
                        val popEffect = Effect.Navigation.PopBackStackUpTo(
                            screenRoute = PresentationScreens.PresentationRequest.screenRoute,
                            inclusive = false
                        )

                        openAuthenticationPrompt(
                            context,
                            popEffect,
                            it.authenticationData,
                            {
                                sendRequestedDocuments(event = Event.DoWork(context))
                            }
                        )
                    }

                    is PresentationLoadingObserveResponsePartialState.IntentToSend -> {
                        onSuccess()
                    }
                }
            }
        }
    }

    private fun sendRequestedDocuments(event: Event) {
        viewModelScope.launch {
            when (val result = interactor.sendRequestedDocuments()) {
                is PresentationLoadingSendRequestedDocumentPartialState.Success -> { /*no op*/
                }

                is PresentationLoadingSendRequestedDocumentPartialState.Failure -> {
                    setState {
                        copy(
                            error = ContentErrorConfig(
                                onRetry = { setEvent(event) },
                                errorSubTitle = result.error,
                                onCancel = {
                                    setEvent(Event.DismissError)
                                    doNavigation(
                                        NavigationType.PopTo(
                                            getPreviousRoute()
                                        )
                                    )
                                }
                            )
                        )
                    }
                }
            }
        }
    }

    private fun openAuthenticationPrompt(
        context: Context,
        popEffect: Effect,
        authenticationDataList: List<AuthenticationData>,
        sendRequestedDocumentsAction: () -> Unit,
        index: Int = 0,
    ) {
        val authenticationData = authenticationDataList.getOrNull(index)
        if (authenticationData == null) {
            setState {
                copy(
                    error = ContentErrorConfig(
                        errorSubTitle = resourceProvider.genericErrorMessage(),
                        onCancel = {
                            setEvent(Event.DismissError)
                            doNavigation(NavigationType.PopTo(getPreviousRoute()))
                        },
                        onRetry = null,
                    )
                )
            }
            return
        }
        val isFinalAuthentication = index == authenticationDataList.lastIndex
        interactor.handleUserAuthentication(
            context = context,
            crypto = authenticationData.crypto,
            notifyOnAuthenticationFailure = viewState.value.notifyOnAuthenticationFailure,
            resultHandler = DeviceAuthenticationResult(
                onAuthenticationSuccess = {
                    authenticationData.onAuthenticationSuccess()
                    if (isFinalAuthentication) {
                        sendRequestedDocumentsAction()
                    } else {
                        delay(500)
                        openAuthenticationPrompt(
                            context,
                            popEffect,
                            authenticationDataList,
                            sendRequestedDocumentsAction,
                            index + 1
                        )
                    }
                },
                onAuthenticationError = { setEffect { popEffect } }
            )
        )
    }

    private fun onSuccess() {
        setState {
            copy(
                error = null
            )
        }
        doNavigation(NavigationType.PushRoute(getNextRoute()))
    }
}