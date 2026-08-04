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

package eu.europa.ec.dashboardfeature.ui.dashboard

import android.content.Intent
import android.net.Uri
import eu.europa.ec.commonfeature.config.OfferUiConfig
import eu.europa.ec.commonfeature.config.PresentationMode
import eu.europa.ec.commonfeature.config.RequestUriConfig
import eu.europa.ec.commonfeature.model.PinFlow
import eu.europa.ec.corelogic.model.RevokedDocumentDataDomain
import eu.europa.ec.dashboardfeature.interactor.DashboardInteractor
import eu.europa.ec.dashboardfeature.ui.dashboard.model.SideMenuItemUi
import eu.europa.ec.dashboardfeature.ui.dashboard.model.SideMenuTypeUi
import eu.europa.ec.eudi.wallet.document.DocumentId
import eu.europa.ec.resourceslogic.R
import eu.europa.ec.resourceslogic.provider.ResourceProvider
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.shared.navigation.DocumentDetailsRoute
import eu.europa.ec.shared.navigation.DocumentOfferRoute
import eu.europa.ec.shared.navigation.PresentationRequestRoute
import eu.europa.ec.shared.navigation.QuickPinRoute
import eu.europa.ec.shared.navigation.SettingsRoute
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.ModalOptionUi
import eu.europa.ec.uilogic.config.ConfigNavigation
import eu.europa.ec.uilogic.config.NavigationType
import eu.europa.ec.uilogic.mvi.MviViewModel
import eu.europa.ec.uilogic.mvi.ViewEvent
import eu.europa.ec.uilogic.mvi.ViewSideEffect
import eu.europa.ec.uilogic.mvi.ViewState
import eu.europa.ec.uilogic.navigation.helper.DeepLinkType
import eu.europa.ec.uilogic.navigation.helper.IntentAction
import eu.europa.ec.uilogic.navigation.helper.IntentType
import eu.europa.ec.uilogic.navigation.helper.hasDeepLink
import eu.europa.ec.uilogic.navigation.helper.hasIntentAction
import org.koin.core.annotation.KoinViewModel

data class State(

    // side menu
    val isSideMenuVisible: Boolean = false,
    val sideMenuTitle: String,
    val sideMenuOptions: List<SideMenuItemUi>,
    val sideMenuAnimation: SideMenuAnimation = SideMenuAnimation.SLIDE,
    val menuAnimationDuration: Int = 1500,

    val isBottomSheetOpen: Boolean = false,
    val sheetContent: DashboardBottomSheetContent = DashboardBottomSheetContent.DocumentRevocation(
        options = emptyList()
    ),
) : ViewState

sealed class Event : ViewEvent {
    data class Init(
        val intent: Intent?
    ) : Event()

    data object Pop : Event()

    data class DocumentRevocationNotificationReceived(
        val payload: List<RevokedDocumentDataDomain>
    ) : Event()

    // side menu events
    sealed class SideMenu : Event() {
        data object Open : SideMenu()
        data object Close : SideMenu()
        data class ItemClicked(val itemType: SideMenuTypeUi) : SideMenu()
    }

    sealed class BottomSheet : Event() {
        data class UpdateBottomSheetState(val isOpen: Boolean) : BottomSheet()

        sealed class DocumentRevocation : BottomSheet() {
            data class OptionListItemForRevokedDocumentSelected(
                val documentId: String
            ) : DocumentRevocation()
        }
    }
}

sealed class Effect : ViewSideEffect {
    sealed class Navigation : Effect() {
        data object Pop : Navigation()
        data class SwitchScreen(
            val route: AppRoute,
            val popUpTo: AppRoute = DashboardRoute,
            val inclusive: Boolean = false,
        ) : Navigation()

        /**
         * [route] is the destination the link resolves to, fully typed. Non-navigating link types
         * (a broadcast, an external URL) carry `null`.
         */
        data class OpenDeepLinkAction(val deepLinkUri: Uri, val route: AppRoute?) :
            Navigation()

        data class OpenIntentAction(
            val intentAction: IntentAction,
            val route: AppRoute?
        ) : Navigation()

        data object OnAppSettings : Navigation()
        data object OnSystemSettings : Navigation()
        data class OpenUrlExternally(val url: Uri) : Navigation()
    }

    data class ShareLogFile(val intent: Intent, val chooserTitle: String) : Effect()

    data object ShowBottomSheet : Effect()
    data object CloseBottomSheet : Effect()
}

sealed class DashboardBottomSheetContent {
    data class DocumentRevocation(
        val options: List<ModalOptionUi<Event>>,
    ) : DashboardBottomSheetContent()
}

enum class SideMenuAnimation {
    SLIDE, FADE
}

@KoinViewModel
class DashboardViewModel(
    private val dashboardInteractor: DashboardInteractor,
    private val resourceProvider: ResourceProvider,
) : MviViewModel<Event, State, Effect>() {
    override fun setInitialState(): State {
        return State(
            sideMenuTitle = resourceProvider.getString(R.string.dashboard_side_menu_title),
            sideMenuOptions = dashboardInteractor.getSideMenuOptions(),
        )
    }

    override fun handleEvents(event: Event) {
        when (event) {
            is Event.Init -> handleDeepLink(event.intent)

            is Event.Pop -> setEffect { Effect.Navigation.Pop }

            is Event.SideMenu.ItemClicked -> {
                handleSideMenuItemClicked(event.itemType)
            }

            is Event.SideMenu.Close -> {
                setState {
                    copy(
                        isSideMenuVisible = false,
                        sideMenuAnimation = SideMenuAnimation.SLIDE
                    )
                }
            }

            is Event.SideMenu.Open -> {
                setState {
                    copy(
                        isSideMenuVisible = true,
                        sideMenuAnimation = SideMenuAnimation.SLIDE
                    )
                }
            }

            is Event.DocumentRevocationNotificationReceived -> {
                showBottomSheet(
                    sheetContent = DashboardBottomSheetContent.DocumentRevocation(
                        options = getDocumentRevocationBottomSheetOptions(event.payload)
                    )
                )
            }

            is Event.BottomSheet.UpdateBottomSheetState -> {
                setState {
                    copy(isBottomSheetOpen = event.isOpen)
                }
            }

            is Event.BottomSheet.DocumentRevocation.OptionListItemForRevokedDocumentSelected -> {
                hideBottomSheet()
                goToDocumentDetails(docId = event.documentId)
            }

        }
    }

    private fun goToDocumentDetails(docId: DocumentId) {
        setEffect {
            Effect.Navigation.SwitchScreen(
                route = DocumentDetailsRoute(documentId = docId)
            )
        }
    }

    private fun showBottomSheet(sheetContent: DashboardBottomSheetContent) {
        setState {
            copy(
                sheetContent = sheetContent
            )
        }
        setEffect {
            Effect.ShowBottomSheet
        }
    }

    private fun hideBottomSheet() {
        setEffect {
            Effect.CloseBottomSheet
        }
    }

    private fun hideSideMenu() {
        setState {
            copy(
                isSideMenuVisible = false,
                sideMenuAnimation = SideMenuAnimation.FADE
            )
        }
    }

    private fun getDocumentRevocationBottomSheetOptions(revokedDocumentData: List<RevokedDocumentDataDomain>): List<ModalOptionUi<Event>> {
        return revokedDocumentData.map {
            ModalOptionUi(
                title = it.name,
                trailingIcon = AppIcons.KeyboardArrowRight,
                event = Event.BottomSheet.DocumentRevocation.OptionListItemForRevokedDocumentSelected(
                    documentId = it.id
                )
            )
        }
    }

    private fun handleSideMenuItemClicked(itemType: SideMenuTypeUi) {
        when (itemType) {
            SideMenuTypeUi.CHANGE_PIN -> {
                val nextRoute = QuickPinRoute(pinFlow = PinFlow.UPDATE)

                hideSideMenu()
                setEffect { Effect.Navigation.SwitchScreen(route = nextRoute) }
            }

            SideMenuTypeUi.SETTINGS -> {
                hideSideMenu()
                setEffect { Effect.Navigation.SwitchScreen(route = SettingsRoute) }
            }
        }
    }

    private fun handleDeepLink(intent: Intent?) {
        intent?.data?.let { uri ->
            hasDeepLink(uri)?.let {
                val route: AppRoute? = when (it.type) {
                    DeepLinkType.OPENID4VP -> {
                        PresentationRequestRoute(
                            config = RequestUriConfig(
                                PresentationMode.OpenId4Vp(
                                    uri.toString(),
                                    DashboardRoute
                                )
                            )
                        )
                    }

                    DeepLinkType.CREDENTIAL_OFFER -> {
                        DocumentOfferRoute(
                            config = OfferUiConfig(
                                offerUri = it.link.toString(),
                                onSuccessNavigation = ConfigNavigation(
                                    navigationType = NavigationType.PopTo(
                                        route = DashboardRoute
                                    )
                                ),
                                onCancelNavigation = ConfigNavigation(
                                    navigationType = NavigationType.Pop
                                )
                            )
                        )
                    }

                    else -> null
                }
                setEffect {
                    Effect.Navigation.OpenDeepLinkAction(
                        deepLinkUri = uri,
                        route = route
                    )
                }
            }
        } ?: hasIntentAction(intent)?.let { action ->
            when (action.type) {
                IntentType.DC_API -> {
                    val route = PresentationRequestRoute(
                        config = RequestUriConfig(
                            PresentationMode.DcApi(
                                initiatorRoute = DashboardRoute
                            )
                        )
                    )

                    setEffect {
                        Effect.Navigation.OpenIntentAction(
                            intentAction = action,
                            route = route
                        )
                    }
                }
            }
        }
    }
}