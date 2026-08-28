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

package eu.europa.ec.commonfeature.ui.success

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.europa.ec.commonfeature.config.SuccessUIConfig
import eu.europa.ec.commonfeature.util.TestTag
import eu.europa.ec.resourceslogic.theme.values.success
import eu.europa.ec.shared.navigation.AppNavigator
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.navigation.SplashRoute
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.shared.resources.asUiText
import eu.europa.ec.shared.resources.generic_success
import eu.europa.ec.shared.resources.issuance_add_document_deferred_success_description
import eu.europa.ec.shared.resources.issuance_add_document_deferred_success_text
import eu.europa.ec.shared.resources.quick_pin_change_success_description
import eu.europa.ec.shared.resources.resolve
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.content.ContentHeader
import eu.europa.ec.uilogic.component.content.ContentScreen
import eu.europa.ec.uilogic.component.content.ScreenNavigateAction
import eu.europa.ec.uilogic.component.preview.PreviewTheme
import eu.europa.ec.uilogic.component.preview.ThemeModePreviews
import eu.europa.ec.uilogic.component.utils.PERCENTAGE_25
import eu.europa.ec.uilogic.component.utils.SIZE_MEDIUM
import eu.europa.ec.uilogic.component.utils.SPACING_SMALL
import eu.europa.ec.uilogic.component.wrap.ButtonConfig
import eu.europa.ec.uilogic.component.wrap.ButtonType
import eu.europa.ec.uilogic.component.wrap.ColorKey
import eu.europa.ec.uilogic.component.wrap.WrapButton
import eu.europa.ec.uilogic.component.wrap.WrapImage
import eu.europa.ec.uilogic.component.wrap.toColor
import eu.europa.ec.uilogic.config.ConfigNavigation
import eu.europa.ec.uilogic.config.NavigationType
import eu.europa.ec.uilogic.extension.applyTestTag
import eu.europa.ec.uilogic.navigation.helper.navigateToRoute
import eu.europa.ec.uilogic.navigation.helper.popBackStackTo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow

@Composable
fun SuccessScreen(
    navigator: AppNavigator,
    viewModel: SuccessViewModel,
    /**
     * Parks an external deep link for [AppRoute] and goes back to it, or pops when there is no route.
     *
     * Injected rather than reached through a seam because Android's answer — caching the `Uri` in the
     * one-shot activity slot via `Context.cacheUri` — lives in `:ui-logic`, which depends on this
     * module. The same lambda [DocumentSuccessScreen][eu.europa.ec.commonfeature.ui.document_success.DocumentSuccessScreen]
     * takes, and for the same reason. The iOS default just pops: the only route to this screen there is
     * the PIN flow, which carries no deep link.
     */
    onExternalDeepLink: (link: String, routeToPop: AppRoute?) -> Unit = { _, routeToPop ->
        routeToPop?.let { navigator.popBackStackTo(route = it, inclusive = false) } ?: navigator.pop()
    },
) {
    val state: State by viewModel.viewState.collectAsStateWithLifecycle()

    ContentScreen(
        isLoading = false,
        onBack = { viewModel.setEvent(Event.BackPressed) },
        navigatableAction = ScreenNavigateAction.NONE
    ) { paddingValues ->
        SuccessScreenView(
            state = state,
            effectFlow = viewModel.effect,
            onEventSent = { event -> viewModel.setEvent(event) },
            onNavigationRequested = { navigationEffect ->
                when (navigationEffect) {
                    is Effect.Navigation.SwitchScreen -> {
                        navigator.navigateToRoute(
                            route = navigationEffect.route,
                            popUpTo = navigationEffect.popUpTo,
                        )
                    }

                    is Effect.Navigation.PopBackStackUpTo -> {
                        navigator.popBackStackTo(
                            route = navigationEffect.route,
                            inclusive = navigationEffect.inclusive
                        )
                    }

                    is Effect.Navigation.DeepLink -> onExternalDeepLink(
                        navigationEffect.link,
                        navigationEffect.routeToPop,
                    )

                    is Effect.Navigation.Pop -> navigator.pop()
                }
            },
            paddingValues = paddingValues
        )
    }
}

@Composable
private fun SuccessScreenView(
    state: State,
    effectFlow: Flow<Effect>,
    onEventSent: (Event) -> Unit,
    onNavigationRequested: (Effect.Navigation) -> Unit,
    paddingValues: PaddingValues
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
    ) {
        ContentHeader(
            modifier = Modifier.fillMaxWidth(),
            config = state.successConfig.headerConfig,
        )

        val imageConfig = state.successConfig.imageConfig
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val imageType = imageConfig.type) {
                is SuccessUIConfig.ImageConfig.Type.Default -> WrapImage(
                    modifier = Modifier.fillMaxWidth(imageConfig.screenPercentageSize),
                    iconData = AppIcons.Success,
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.success),
                    contentScale = ContentScale.FillWidth
                )

                is SuccessUIConfig.ImageConfig.Type.Drawable -> WrapImage(
                    modifier = Modifier.fillMaxWidth(imageConfig.screenPercentageSize),
                    iconData = imageType.icon,
                    colorFilter = imageConfig.tint?.let { safeImageColorTint ->
                        ColorFilter.tint(safeImageColorTint.toColor())
                    },
                    contentScale = ContentScale.FillWidth
                )
            }

            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = SPACING_SMALL.dp),
                text = state.successConfig.textElementsConfig.text.resolve(),
                style = MaterialTheme.typography.headlineLarge.copy(
                    color = state.successConfig.textElementsConfig.color.toColor()
                ),
                textAlign = TextAlign.Center,
            )

            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = SPACING_SMALL.dp),
                text = state.successConfig.textElementsConfig.description.resolve(),
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                textAlign = TextAlign.Center,
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            state.successConfig.buttonConfig.forEach { buttonConfig ->
                Button(
                    onEventSent = onEventSent,
                    config = buttonConfig
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        effectFlow.onEach { effect ->
            when (effect) {
                is Effect.Navigation -> onNavigationRequested(effect)
            }
        }.collect()
    }
}

@Composable
private fun Button(
    onEventSent: (Event) -> Unit,
    config: SuccessUIConfig.ButtonConfig
) {
    when (config.style) {
        SuccessUIConfig.ButtonConfig.Style.PRIMARY -> {
            WrapButton(
                buttonConfig = ButtonConfig(
                    type = ButtonType.PRIMARY,
                    onClick = { onEventSent(Event.ButtonClicked(config)) },
                ),
                modifier = Modifier
                    .applyTestTag(TestTag.SuccessScreen.PRIMARY_BUTTON)
                    .fillMaxWidth(),
            ) {
                ButtonRow(text = config.text.resolve())
            }
        }

        SuccessUIConfig.ButtonConfig.Style.OUTLINE -> {
            WrapButton(
                buttonConfig = ButtonConfig(
                    type = ButtonType.SECONDARY,
                    onClick = { onEventSent(Event.ButtonClicked(config)) },
                ),
                modifier = Modifier
                    .applyTestTag(TestTag.SuccessScreen.SECONDARY_BUTTON)
                    .fillMaxWidth(),
            ) {
                ButtonRow(text = config.text.resolve())
            }
        }
    }
}

@Composable
private fun ButtonRow(text: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = text,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f)
        )
    }
}

@ThemeModePreviews
@Composable
private fun SuccessDefaultPreview() {
    PreviewTheme {
        SuccessScreenView(
            state = State(
                successConfig = SuccessUIConfig(
                    textElementsConfig = SuccessUIConfig.TextElementsConfig(
                        text = UiText.Resource(Res.string.generic_success),
                        description = UiText.Resource(Res.string.quick_pin_change_success_description),
                    ),
                    imageConfig = SuccessUIConfig.ImageConfig(),
                    buttonConfig = listOf(
                        SuccessUIConfig.ButtonConfig(
                            text = "Back".asUiText(),
                            style = SuccessUIConfig.ButtonConfig.Style.PRIMARY,
                            navigation = ConfigNavigation(
                                navigationType = NavigationType.PopTo(SplashRoute),
                            )
                        )
                    ),
                    onBackScreenToNavigate = ConfigNavigation(
                        navigationType = NavigationType.PopTo(SplashRoute),
                    ),
                )
            ),
            effectFlow = Channel<Effect>().receiveAsFlow(),
            onEventSent = {},
            onNavigationRequested = {},
            paddingValues = PaddingValues(SIZE_MEDIUM.dp)
        )
    }
}

@ThemeModePreviews
@Composable
private fun SuccessPendingPreview() {
    PreviewTheme {
        SuccessScreenView(
            state = State(
                successConfig = SuccessUIConfig(
                    textElementsConfig = SuccessUIConfig.TextElementsConfig(
                        text = UiText.Resource(Res.string.issuance_add_document_deferred_success_text),
                        description = UiText.Resource(Res.string.issuance_add_document_deferred_success_description),
                        color = ColorKey.Pending,
                    ),
                    imageConfig = SuccessUIConfig.ImageConfig(
                        type = SuccessUIConfig.ImageConfig.Type.Drawable(icon = AppIcons.InProgress),
                        tint = ColorKey.Primary,
                        screenPercentageSize = PERCENTAGE_25,
                    ),
                    buttonConfig = listOf(
                        SuccessUIConfig.ButtonConfig(
                            text = "back".asUiText(),
                            style = SuccessUIConfig.ButtonConfig.Style.PRIMARY,
                            navigation = ConfigNavigation(
                                navigationType = NavigationType.PopTo(SplashRoute),
                            )
                        )
                    ),
                    onBackScreenToNavigate = ConfigNavigation(
                        navigationType = NavigationType.PopTo(SplashRoute),
                    ),
                )
            ),
            effectFlow = Channel<Effect>().receiveAsFlow(),
            onEventSent = {},
            onNavigationRequested = {},
            paddingValues = PaddingValues(SIZE_MEDIUM.dp)
        )
    }
}