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

// Phase 3b: the base behind the three document-success screens. Its Android leaves were all
// pass-through values, and each crosses the seam the way its kind should: the deep link is a `String`
// (as in `SuccessViewModel`), the pending intent is the opaque `PlatformIntent` handle, and
// `Activity.RESULT_OK` went back to the screen, since a result code is a platform constant and shared
// code has no business naming -1. Package unchanged.
package eu.europa.ec.commonfeature.ui.document_success

import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.navigation.AppRouteCodec
import eu.europa.ec.shared.platform.PlatformIntent
import eu.europa.ec.uilogic.component.AppIconAndTextDataUi
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.ListItemTrailingContentDataUi
import eu.europa.ec.uilogic.component.content.ContentHeaderConfig
import eu.europa.ec.uilogic.component.wrap.ExpandableListItemUi
import eu.europa.ec.uilogic.config.ConfigNavigation
import eu.europa.ec.uilogic.config.NavigationType
import eu.europa.ec.uilogic.extension.toggleExpansionState
import eu.europa.ec.uilogic.mvi.MviViewModel
import eu.europa.ec.uilogic.mvi.ViewEvent
import eu.europa.ec.uilogic.mvi.ViewSideEffect
import eu.europa.ec.uilogic.mvi.ViewState

data class State(
    val isLoading: Boolean = false,
    val headerConfig: ContentHeaderConfig,

    val bannerText: UiText = UiText.Empty,

    val items: List<ExpandableListItemUi.NestedListItem> = emptyList(),
) : ViewState

sealed class Event : ViewEvent {
    data object DoWork : Event()
    data object StickyButtonPressed : Event()

    data class ExpandOrCollapseSuccessDocumentItem(val itemId: String) : Event()
}

sealed class Effect : ViewSideEffect {
    sealed class Navigation : Effect() {
        data class SwitchScreen(
            val route: AppRoute,
            val popUpTo: AppRoute?,
        ) : Navigation()

        data class PopBackStackUpTo(
            val route: AppRoute,
            val inclusive: Boolean,
        ) : Navigation()

        data object Pop : Navigation()

        /**
         * The link stays a `String` rather than an `android.net.Uri`, matching
         * `SuccessViewModel.Effect.Navigation.DeepLink`; the screen parses it at the point of use.
         */
        data class DeepLink(
            val link: String,
            val routeToPop: AppRoute?,
        ) : Navigation()

        /**
         * Finish the hosting activity, handing this intent back as the successful result.
         *
         * The result *code* is no longer carried: it was always `Activity.RESULT_OK`, which is a
         * platform constant, so the screen supplies it rather than shared code naming -1.
         */
        data class FinishWithResult(
            val intent: PlatformIntent,
        ) : Navigation()
    }
}

abstract class DocumentSuccessViewModel : MviViewModel<Event, State, Effect>() {

    abstract fun getNextScreenConfigNavigation(): ConfigNavigation
    abstract fun doWork()
    abstract fun getPendingIntent(): PlatformIntent?

    override fun setInitialState(): State {
        return State(
            headerConfig = ContentHeaderConfig(
                appIconAndTextData = AppIconAndTextDataUi(),
                description = null,
            )
        )
    }

    override fun handleEvents(event: Event) {
        when (event) {
            is Event.DoWork -> doWork()

            is Event.ExpandOrCollapseSuccessDocumentItem -> expandOrCollapseSuccessDocumentItem(id = event.itemId)

            is Event.StickyButtonPressed -> handleStickyButtonPressed()
        }
    }

    private fun handleStickyButtonPressed() {
        val pendingIntent = getPendingIntent()
        if (pendingIntent != null) {
            handleIntent(pendingIntent)
        } else {
            doNavigation(navigation = getNextScreenConfigNavigation())
        }
    }

    private fun expandOrCollapseSuccessDocumentItem(id: String) {
        val currentItems = viewState.value.items

        val updatedItems = currentItems.map { successDocument ->
            val newHeader = if (successDocument.header.itemId == id) {
                val newIsExpanded = !successDocument.isExpanded
                val newCollapsed = successDocument.header.copy(
                    trailingContentData = ListItemTrailingContentDataUi.Icon(
                        iconData = if (newIsExpanded) {
                            AppIcons.KeyboardArrowUp
                        } else {
                            AppIcons.KeyboardArrowDown
                        }
                    )
                )

                successDocument.copy(
                    header = newCollapsed,
                    isExpanded = newIsExpanded
                )
            } else {
                successDocument
            }

            successDocument.copy(
                header = newHeader.header,
                isExpanded = newHeader.isExpanded,
                nestedItems = newHeader.nestedItems.toggleExpansionState(id)
            )
        }

        setState {
            copy(
                items = updatedItems
            )
        }
    }

    private fun handleIntent(intent: PlatformIntent) {
        setEffect {
            Effect.Navigation.FinishWithResult(intent = intent)
        }
    }

    private fun doNavigation(navigation: ConfigNavigation) {

        val navigationEffect: Effect.Navigation = when (val nav = navigation.navigationType) {
            is NavigationType.PopTo -> {
                Effect.Navigation.PopBackStackUpTo(
                    route = nav.route,
                    inclusive = false
                )
            }

            is NavigationType.Deeplink -> Effect.Navigation.DeepLink(
                nav.link,
                AppRouteCodec.decode(nav.routeToPop)
            )

            is NavigationType.Pop, NavigationType.Finish -> Effect.Navigation.Pop

            is NavigationType.PushRoute -> Effect.Navigation.SwitchScreen(
                route = nav.route,
                popUpTo = nav.popUpTo
            )
        }

        setEffect {
            navigationEffect
        }
    }
}