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

package eu.europa.ec.issuancefeature.ui.add

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.shared.resources.resolve
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.europa.ec.commonfeature.config.IssuanceFlowType
import eu.europa.ec.commonfeature.config.IssuanceUiConfig
import eu.europa.ec.commonfeature.ui.issuance.IssuerNotTrustedSheetContent
import eu.europa.ec.corelogic.controller.IssuanceMethod
import eu.europa.ec.corelogic.util.CoreActions
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.shared.platform.platformAction
import eu.europa.ec.shared.platform.platformStringExtra
import eu.europa.ec.issuancefeature.ui.add.model.AddDocumentUi
import eu.europa.ec.issuancefeature.util.TestTag
import eu.europa.ec.shared.navigation.AppNavigator
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.ErrorInfo
import eu.europa.ec.uilogic.component.ListItemDataUi
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import eu.europa.ec.uilogic.component.ListItemTrailingContentDataUi
import eu.europa.ec.uilogic.component.content.BroadcastAction
import eu.europa.ec.uilogic.component.content.ContentScreen
import eu.europa.ec.uilogic.component.content.ContentTitle
import eu.europa.ec.uilogic.component.content.ScreenNavigateAction
import eu.europa.ec.uilogic.component.content.ToolbarActionUi
import eu.europa.ec.uilogic.component.content.ToolbarConfig
import eu.europa.ec.uilogic.component.preview.PreviewTheme
import eu.europa.ec.uilogic.component.preview.ThemeModePreviews
import eu.europa.ec.uilogic.component.rememberPlatformContextOrNull
import eu.europa.ec.uilogic.component.rememberPlatformScreenActions
import eu.europa.ec.uilogic.component.utils.LifecycleEffect
import eu.europa.ec.uilogic.component.utils.SPACING_LARGE
import eu.europa.ec.uilogic.component.utils.SPACING_MEDIUM
import eu.europa.ec.uilogic.component.utils.VSpacer
import eu.europa.ec.uilogic.component.wrap.ColorKey
import eu.europa.ec.uilogic.component.wrap.TextConfig
import eu.europa.ec.uilogic.component.wrap.TextStyleKey
import eu.europa.ec.uilogic.component.wrap.WrapListItem
import eu.europa.ec.uilogic.component.wrap.WrapModalBottomSheet
import eu.europa.ec.uilogic.component.wrap.WrapText
import eu.europa.ec.uilogic.extension.applyTestTag
import eu.europa.ec.uilogic.extension.paddingFrom
import eu.europa.ec.uilogic.navigation.helper.navigateReplacingCurrent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.issuance_add_document_no_options
import eu.europa.ec.shared.resources.issuance_add_document_subtitle
import eu.europa.ec.shared.resources.issuance_add_document_title

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDocumentScreen(
    navigator: AppNavigator,
    viewModel: AddDocumentViewModel,
    /**
     * The external deep link waiting to be handled, if any — read once per resume, since reading it
     * *consumes* it on Android (it clears the cached intent). Injected because Android's answer,
     * `Context.getPendingUri()`, lives in `:ui-logic`, which depends on this module.
     */
    pendingDeepLink: () -> String? = { null },
    /**
     * Hands an external deep link to whatever flow owns it, going to [AppRoute] when the link resolves
     * to one. Injected for the same reason, and additionally because Android's `handleDeepLinkAction`
     * reaches the Android-only RQES UI SDK. The iOS default does nothing; iOS deep links arrive through
     * the app delegate and are not wired yet.
     */
    onExternalDeepLink: (link: String, route: AppRoute?) -> Unit = { _, _ -> },
) {
    val state: State by viewModel.viewState.collectAsStateWithLifecycle()
    val platformActions = rememberPlatformScreenActions()
    // Null on iOS, where issuance does not run through wallet-core: tapping an option is inert there.
    val platformContext = rememberPlatformContextOrNull()

    val isBottomSheetOpen = state.isBottomSheetOpen
    val scope = rememberCoroutineScope()
    val bottomSheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    val toolbarConfig = ToolbarConfig(
        actions = if (state.error == null) {
            listOf(
                ToolbarActionUi(
                    icon = AppIcons.QrScanner,
                    enabled = !state.isLoading,
                    onClick = { viewModel.setEvent(Event.GoToQrScan) }
                )
            )
        } else emptyList(),
        maxVisibleActions = 1
    )

    ContentScreen(
        isLoading = state.isLoading,
        toolBarConfig = toolbarConfig,
        navigatableAction = state.navigatableAction,
        onBack = state.onBackAction,
        contentErrorConfig = state.error,
        broadcastAction = BroadcastAction(
            intentFilters = listOf(
                CoreActions.VCI_RESUME_ACTION,
                CoreActions.VCI_DYNAMIC_PRESENTATION
            ),
            callback = {
                when (it?.platformAction()) {
                    CoreActions.VCI_RESUME_ACTION -> it.platformStringExtra("uri")?.let { link ->
                        viewModel.setEvent(Event.OnResumeIssuance(link))
                    }

                    CoreActions.VCI_DYNAMIC_PRESENTATION -> it.platformStringExtra("uri")
                        ?.let { link ->
                            viewModel.setEvent(Event.OnDynamicPresentation(link))
                        }
                }
            }
        )
    ) { paddingValues ->
        Content(
            state = state,
            effectFlow = viewModel.effect,
            onEventSend = { viewModel.setEvent(it) },
            onNavigationRequested = { navigationEffect ->
                when (navigationEffect) {
                    is Effect.Navigation.Pop -> navigator.pop()
                    is Effect.Navigation.SwitchScreen -> {
                        navigator.navigateReplacingCurrent(
                            route = navigationEffect.route,
                            popUpToCurrent = navigationEffect.inclusive
                        )
                    }

                    is Effect.Navigation.Finish -> platformActions.finishApp()
                    is Effect.Navigation.OpenDeepLinkAction -> onExternalDeepLink(
                        navigationEffect.deepLinkUri,
                        navigationEffect.route,
                    )
                }
            },
            paddingValues = paddingValues,
            platformContext = platformContext,
            coroutineScope = scope,
            modalBottomSheetState = bottomSheetState,
        )

        if (isBottomSheetOpen) {
            WrapModalBottomSheet(
                onDismissRequest = {
                    viewModel.setEvent(
                        Event.BottomSheet.UpdateBottomSheetState(isOpen = false)
                    )
                },
                sheetState = bottomSheetState
            ) {
                IssuerNotTrustedSheetContent(
                    onClose = {
                        viewModel.setEvent(Event.BottomSheet.Close)
                    },
                )
            }
        }
    }

    LifecycleEffect(
        lifecycleOwner = LocalLifecycleOwner.current,
        lifecycleEvent = Lifecycle.Event.ON_PAUSE
    ) {
        viewModel.setEvent(Event.OnPause)
    }

    LifecycleEffect(
        lifecycleOwner = LocalLifecycleOwner.current,
        lifecycleEvent = Lifecycle.Event.ON_RESUME
    ) {
        viewModel.setEvent(Event.Init(pendingDeepLink()))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Content(
    state: State,
    effectFlow: Flow<Effect>,
    onEventSend: (Event) -> Unit,
    onNavigationRequested: (Effect.Navigation) -> Unit,
    paddingValues: PaddingValues,
    platformContext: PlatformContext?,
    coroutineScope: CoroutineScope,
    modalBottomSheetState: SheetState,
) {
    MainContent(
        modifier = Modifier
            .fillMaxSize()
            .paddingFrom(paddingValues, bottom = false),
        state = state,
        onEventSend = onEventSend,
        platformContext = platformContext,
    )

    LaunchedEffect(Unit) {
        effectFlow.onEach { effect ->
            when (effect) {
                is Effect.Navigation -> onNavigationRequested(effect)

                is Effect.ShowBottomSheet -> {
                    onEventSend(Event.BottomSheet.UpdateBottomSheetState(isOpen = true))
                }

                is Effect.CloseBottomSheet -> {
                    coroutineScope.launch {
                        modalBottomSheetState.hide()
                    }.invokeOnCompletion {
                        if (!modalBottomSheetState.isVisible) {
                            onEventSend(Event.BottomSheet.UpdateBottomSheetState(isOpen = false))
                        }
                    }
                }
            }
        }.collect()
    }
}

@Composable
private fun MainContent(
    modifier: Modifier = Modifier,
    state: State,
    onEventSend: (Event) -> Unit,
    platformContext: PlatformContext?,
) {
    Column(
        modifier = modifier
    ) {
        ContentTitle(
            modifier = Modifier.fillMaxWidth(),
            title = state.title.resolve(),
            subtitle = state.subtitle.resolve(),
            subtitleTestTag = TestTag.AddDocumentScreen.SUBTITLE,
        )

        if (state.noOptions) {
            ErrorInfo(
                modifier = Modifier.fillMaxSize(),
                informativeText = stringResource(Res.string.issuance_add_document_no_options)
            )
        } else {

            VSpacer.Medium()

            Options(
                options = state.options,
                modifier = Modifier.fillMaxSize(),
                onOptionClicked = { itemIds, issuerId ->
                    platformContext?.let {
                        onEventSend(
                            Event.IssueDocument(
                                issuanceMethod = IssuanceMethod.OPENID4VCI,
                                issuerId = issuerId,
                                configIds = itemIds,
                                context = it
                            )
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun Options(
    options: List<Pair<String, List<AddDocumentUi>>>,
    modifier: Modifier = Modifier,
    onOptionClicked: (itemIds: List<String>, issuerId: String) -> Unit,
) {

    val listState = remember(options) { LazyListState() }

    LazyColumn(
        state = listState,
        modifier = modifier
    ) {
        options.forEachIndexed { sectionIndex, (issuerId, items) ->

            stickyHeader(key = "hdr-$issuerId") {
                WrapText(
                    modifier = modifier
                        .background(MaterialTheme.colorScheme.background)
                        .padding(bottom = SPACING_MEDIUM.dp),
                    text = issuerId,
                    textConfig = TextConfig(
                        styleKey = TextStyleKey.LabelSmall,
                        colorKey = ColorKey.OnSurfaceVariant,
                    )
                )
            }

            itemsIndexed(
                items = items,
                key = { _, item -> "$issuerId-${item.configurationIds.joinToString(",")}" }
            ) { index, item ->
                val testTag = TestTag.AddDocumentScreen.optionItem(
                    issuerId = issuerId,
                    configIds = item.configurationIds
                )
                WrapListItem(
                    modifier = Modifier
                        .applyTestTag(testTag)
                        .fillMaxWidth(),
                    item = item.itemData,
                    mainContentVerticalPadding = SPACING_LARGE.dp,
                    mainContentTextStyle = MaterialTheme.typography.titleMedium,
                    onItemClick = {
                        onOptionClicked(item.configurationIds, issuerId)
                    }
                )
                if (index < items.lastIndex) {
                    Spacer(Modifier.height(SPACING_MEDIUM.dp))
                }
            }

            if (sectionIndex != options.lastIndex) {
                item(key = "sep-$issuerId") { Spacer(Modifier.height(SPACING_MEDIUM.dp)) }
            }
        }

        item(key = "footer-spacer") {
            Spacer(
                modifier = Modifier
                    .padding(bottom = SPACING_MEDIUM.dp)
                    .navigationBarsPadding()
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@ThemeModePreviews
@Composable
private fun IssuanceAddDocumentScreenPreview() {
    PreviewTheme {
        Content(
            state = State(
                issuanceConfig = IssuanceUiConfig(
                    flowType = IssuanceFlowType.NoDocument
                ),
                navigatableAction = ScreenNavigateAction.NONE,
                title = UiText.Resource(Res.string.issuance_add_document_title),
                subtitle = UiText.Resource(Res.string.issuance_add_document_subtitle),
                options = listOf(
                    Pair(
                        "issuer1",
                        listOf(
                            AddDocumentUi(
                                credentialIssuerId = "issuer1",
                                configurationIds = listOf("configId1"),
                                itemData = ListItemDataUi(
                                    itemId = "configId1",
                                    mainContentData = ListItemMainContentDataUi.Text(text = "National ID"),
                                    trailingContentData = ListItemTrailingContentDataUi.Icon(
                                        iconData = AppIcons.Add
                                    )
                                )
                            )
                        )
                    ),
                    Pair(
                        "issuer2",
                        listOf(
                            AddDocumentUi(
                                credentialIssuerId = "issuer2",
                                configurationIds = listOf("configId2"),
                                itemData = ListItemDataUi(
                                    itemId = "configId2",
                                    mainContentData = ListItemMainContentDataUi.Text(text = "Driving Licence"),
                                    trailingContentData = ListItemTrailingContentDataUi.Icon(
                                        iconData = AppIcons.Add
                                    )
                                )
                            )
                        )
                    )
                )
            ),
            effectFlow = Channel<Effect>().receiveAsFlow(),
            onEventSend = {},
            onNavigationRequested = {},
            paddingValues = PaddingValues(all = SPACING_LARGE.dp),
            platformContext = rememberPlatformContextOrNull(),
            coroutineScope = rememberCoroutineScope(),
            modalBottomSheetState = rememberModalBottomSheetState(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@ThemeModePreviews
@Composable
private fun DashboardAddDocumentScreenPreview() {
    PreviewTheme {
        Content(
            state = State(
                issuanceConfig = IssuanceUiConfig(
                    flowType = IssuanceFlowType.ExtraDocument(
                        formatType = null
                    )
                ),
                navigatableAction = ScreenNavigateAction.BACKABLE,
                title = UiText.Resource(Res.string.issuance_add_document_title),
                subtitle = UiText.Resource(Res.string.issuance_add_document_subtitle),
                options = listOf(
                    Pair(
                        "issuer1",
                        listOf(
                            AddDocumentUi(
                                credentialIssuerId = "issuer1",
                                configurationIds = listOf("configId1"),
                                itemData = ListItemDataUi(
                                    itemId = "configId1",
                                    mainContentData = ListItemMainContentDataUi.Text(text = "National ID"),
                                    trailingContentData = ListItemTrailingContentDataUi.Icon(
                                        iconData = AppIcons.Add
                                    )
                                )
                            )
                        )
                    ),
                    Pair(
                        "issuer2",
                        listOf(
                            AddDocumentUi(
                                credentialIssuerId = "issuer2",
                                configurationIds = listOf("configId2"),
                                itemData = ListItemDataUi(
                                    itemId = "configId2",
                                    mainContentData = ListItemMainContentDataUi.Text(text = "Driving Licence"),
                                    trailingContentData = ListItemTrailingContentDataUi.Icon(
                                        iconData = AppIcons.Add
                                    )
                                )
                            )
                        )
                    )
                )
            ),
            effectFlow = Channel<Effect>().receiveAsFlow(),
            onEventSend = {},
            onNavigationRequested = {},
            paddingValues = PaddingValues(all = SPACING_LARGE.dp),
            platformContext = rememberPlatformContextOrNull(),
            coroutineScope = rememberCoroutineScope(),
            modalBottomSheetState = rememberModalBottomSheetState(),
        )
    }
}