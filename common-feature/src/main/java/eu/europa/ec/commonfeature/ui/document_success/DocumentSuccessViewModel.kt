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

package eu.europa.ec.commonfeature.ui.document_success

import android.app.Activity
import android.content.Intent
import android.net.Uri
import eu.europa.ec.businesslogic.extension.toUri
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.navigation.AppRouteCodec
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

        data class DeepLink(
            val link: Uri,
            val routeToPop: AppRoute?,
        ) : Navigation()

        data class FinishWithResult(
            val intent: Intent,
            val resultCode: Int,
        ) : Navigation()
    }
}

abstract class DocumentSuccessViewModel : MviViewModel<Event, State, Effect>() {

    abstract fun getNextScreenConfigNavigation(): ConfigNavigation
    abstract fun doWork()
    abstract fun getPendingIntent(): Intent?

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

    private fun handleIntent(intent: Intent) {
        setEffect {
            Effect.Navigation.FinishWithResult(
                intent = intent,
                resultCode = Activity.RESULT_OK,
            )
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
                nav.link.toUri(),
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