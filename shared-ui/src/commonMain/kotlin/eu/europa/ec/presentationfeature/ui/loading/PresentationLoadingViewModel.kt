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

// Phase 3b: the ninth feature view-model in commonMain, rejoining the proximity twin it was split from
// one commit earlier. Its interactor's partial states were the only thing holding it back, and neither
// payload turned out to need a real abstraction in the view-model: the redirect became a `String` and
// the intent an opaque `PlatformIntent`, both of which this view-model ignores — it matches the branch
// and calls `onSuccess()`. Package unchanged, so `PresentationLoadingScreen` is untouched.
package eu.europa.ec.presentationfeature.ui.loading

import androidx.lifecycle.viewModelScope
import eu.europa.ec.authenticationlogic.controller.authentication.DeviceAuthenticationResult
import eu.europa.ec.commonfeature.config.PresentationMode
import eu.europa.ec.commonfeature.config.RequestUriConfig
import eu.europa.ec.commonfeature.ui.loading.Effect
import eu.europa.ec.commonfeature.ui.loading.Event
import eu.europa.ec.commonfeature.ui.loading.LoadingViewModel
import eu.europa.ec.corelogic.model.AuthenticationData
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.presentationfeature.interactor.PresentationLoadingInteractor
import eu.europa.ec.presentationfeature.interactor.PresentationLoadingObserveResponsePartialState
import eu.europa.ec.presentationfeature.interactor.PresentationLoadingSendRequestedDocumentPartialState
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.navigation.PresentationLoadingRoute
import eu.europa.ec.shared.navigation.PresentationRequestRoute
import eu.europa.ec.shared.navigation.PresentationSuccessRoute
import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.shared.resources.asUiText
import eu.europa.ec.shared.resources.generic_error_message
import eu.europa.ec.uilogic.component.content.ContentErrorConfig
import eu.europa.ec.uilogic.component.content.ContentHeaderConfig
import eu.europa.ec.uilogic.config.NavigationType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.loading_header_description

@KoinViewModel
class PresentationLoadingViewModel(
    private val interactor: PresentationLoadingInteractor,
    @InjectedParam private val presentationScopeId: String
) : LoadingViewModel() {

    override fun getHeaderConfig(): ContentHeaderConfig {
        return ContentHeaderConfig(
            description = UiText.Resource(Res.string.loading_header_description),
        )
    }

    /**
     * The destination to fall back to: the presentation request screen.
     *
     * [PresentationLoadingRoute] only carries the scope id, so the request screen's original
     * [RequestUriConfig] is not reachable from here and the config below is a placeholder. That is
     * harmless because pop targets are matched by *destination*, not by value —
     * [eu.europa.ec.shared.navigation.AppNavigator.popUpTo] compares `route::class`, exactly as
     * navigation-compose's `popUpTo(routePattern)` ignored arguments — so only
     * `PresentationRequestRoute` itself is significant here.
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

    override fun doWork(context: PlatformContext?) {
        viewModelScope.launch {

            interactor.setScopeId(presentationScopeId)

            interactor.observeResponse().collect {
                when (it) {
                    is PresentationLoadingObserveResponsePartialState.Failure -> {
                        setState {
                            copy(
                                error = ContentErrorConfig(
                                    onRetry = { setEvent(Event.DoWork(context)) },
                                    errorSubTitle = it.error.asUiText(),
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
                            route = getPreviousRoute(),
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
                                errorSubTitle = result.error.asUiText(),
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
        context: PlatformContext?,
        popEffect: Effect,
        authenticationDataList: List<AuthenticationData>,
        sendRequestedDocumentsAction: () -> Unit,
        index: Int = 0,
    ) {
        // No platform handle means no way to raise a device-authentication prompt — iOS. Report it
        // instead of proceeding or hanging: the send genuinely cannot continue, and an honest error is
        // what the rest of this screen already does when it has no authentication data.
        if (context == null) {
            setState {
                copy(
                    error = ContentErrorConfig(
                        errorSubTitle = UiText.Resource(Res.string.generic_error_message),
                        onCancel = {
                            setEvent(Event.DismissError)
                            doNavigation(NavigationType.PopTo(getPreviousRoute()))
                        }
                    )
                )
            }
            return
        }
        val authenticationData = authenticationDataList.getOrNull(index)
        if (authenticationData == null) {
            setState {
                copy(
                    error = ContentErrorConfig(
                        errorSubTitle = UiText.Resource(Res.string.generic_error_message),
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