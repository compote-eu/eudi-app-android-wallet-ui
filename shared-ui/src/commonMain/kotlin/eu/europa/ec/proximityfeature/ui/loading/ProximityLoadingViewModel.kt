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

// Phase 3b: the seventh feature view-model in commonMain, and the first that authenticates the user —
// it drives one biometric prompt per document being shared, which is the whole reason the device-auth
// handle exists. The `Context` it forwards is now an opaque `PlatformContext`; everything else it
// touches (the callbacks, the crypto courier, the partial states) became platform-neutral with the
// device-auth types.
//
// Its presentation twin stays Android-side until the intent/URI seam lands, because
// `PresentationLoadingObserveResponsePartialState` carries a `java.net.URI` redirect and an
// `android.content.Intent`. Package unchanged, so `ProximityLoadingScreen` is untouched.
package eu.europa.ec.proximityfeature.ui.loading

import androidx.lifecycle.viewModelScope
import eu.europa.ec.authenticationlogic.controller.authentication.DeviceAuthenticationResult
import eu.europa.ec.commonfeature.ui.loading.Effect
import eu.europa.ec.commonfeature.ui.loading.Event
import eu.europa.ec.commonfeature.ui.loading.LoadingViewModel
import eu.europa.ec.corelogic.model.AuthenticationData
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.proximityfeature.interactor.ProximityLoadingInteractor
import eu.europa.ec.proximityfeature.interactor.ProximityLoadingObserveResponsePartialState
import eu.europa.ec.proximityfeature.interactor.ProximityLoadingSendRequestedDocumentPartialState
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.navigation.ProximityLoadingRoute
import eu.europa.ec.shared.navigation.ProximityRequestRoute
import eu.europa.ec.shared.navigation.ProximitySuccessRoute
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
class ProximityLoadingViewModel(
    private val interactor: ProximityLoadingInteractor,
    @InjectedParam private val presentationScopeId: String
) : LoadingViewModel() {

    override fun getHeaderConfig(): ContentHeaderConfig {
        return ContentHeaderConfig(
            description = UiText.Resource(Res.string.loading_header_description),
        )
    }

    override fun getPreviousRoute(): AppRoute {
        return ProximityRequestRoute(presentationScopeId)
    }

    override fun getCallerRoute(): AppRoute {
        return ProximityLoadingRoute(presentationScopeId)
    }

    private fun getNextRoute(): AppRoute {
        return ProximitySuccessRoute(presentationScopeId)
    }

    override fun getCancellableTimeout(): Duration = 5.toDuration(DurationUnit.SECONDS)

    override fun doWork(context: PlatformContext?) {
        viewModelScope.launch {

            interactor.setScopeId(presentationScopeId)

            interactor.observeResponse().collect {
                when (it) {
                    is ProximityLoadingObserveResponsePartialState.Failure -> {
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

                    is ProximityLoadingObserveResponsePartialState.Success -> {
                        onSuccess()
                    }

                    is ProximityLoadingObserveResponsePartialState.RequestReadyToBeSent -> {
                        sendRequestedDocuments(event = Event.DoWork(context))
                    }

                    is ProximityLoadingObserveResponsePartialState.UserAuthenticationRequired -> {
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
                }
            }
        }
    }

    private fun sendRequestedDocuments(event: Event) {
        viewModelScope.launch {
            when (val result = interactor.sendRequestedDocuments()) {
                is ProximityLoadingSendRequestedDocumentPartialState.Success -> { /*no op*/
                }

                is ProximityLoadingSendRequestedDocumentPartialState.Failure -> {
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