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

package eu.europa.ec.dashboardfeature.ui.documents.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import eu.europa.ec.shared.resources.UiText
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.europa.ec.commonfeature.ui.issuance.IssuerNotTrustedSheetContent
import eu.europa.ec.corelogic.model.DocumentIdentifier
import eu.europa.ec.corelogic.util.CoreActions
import eu.europa.ec.dashboardfeature.ui.documents.detail.model.DocumentDetailsUi
import eu.europa.ec.dashboardfeature.ui.documents.model.DocumentCredentialsInfoUi
import eu.europa.ec.dashboardfeature.util.TestTag
import eu.europa.ec.resourceslogic.theme.values.success
import eu.europa.ec.resourceslogic.theme.values.warning
import eu.europa.ec.shared.navigation.AppNavigator
import eu.europa.ec.shared.navigation.AppRoute
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.shared.platform.platformAction
import eu.europa.ec.shared.platform.platformStringExtra
import eu.europa.ec.shared.platform.platformStringListExtra
import eu.europa.ec.uilogic.component.rememberPlatformContextOrNull
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.IssuerDetailsCard
import eu.europa.ec.uilogic.component.IssuerDetailsCardDataUi
import eu.europa.ec.uilogic.component.ListItemDataUi
import eu.europa.ec.uilogic.component.ListItemLeadingContentDataUi
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import eu.europa.ec.uilogic.component.SectionTitle
import eu.europa.ec.uilogic.component.content.BroadcastAction
import eu.europa.ec.uilogic.component.content.ContentScreen
import eu.europa.ec.uilogic.component.content.ContentTitle
import eu.europa.ec.uilogic.component.content.ScreenNavigateAction
import eu.europa.ec.uilogic.component.content.ToolbarActionUi
import eu.europa.ec.uilogic.component.content.ToolbarConfig
import eu.europa.ec.uilogic.component.preview.PreviewTheme
import eu.europa.ec.uilogic.component.preview.ThemeModePreviews
import eu.europa.ec.uilogic.component.utils.LifecycleEffect
import eu.europa.ec.uilogic.component.utils.SPACING_EXTRA_LARGE
import eu.europa.ec.uilogic.component.utils.SPACING_LARGE
import eu.europa.ec.uilogic.component.utils.SPACING_MEDIUM
import eu.europa.ec.uilogic.component.wrap.BottomSheetTextDataUi
import eu.europa.ec.uilogic.component.wrap.ButtonConfig
import eu.europa.ec.uilogic.component.wrap.ButtonType
import eu.europa.ec.uilogic.component.wrap.DialogBottomSheet
import eu.europa.ec.uilogic.component.wrap.ExpandableListItemUi
import eu.europa.ec.uilogic.component.wrap.SimpleBottomSheet
import eu.europa.ec.uilogic.component.wrap.WrapButton
import eu.europa.ec.uilogic.component.wrap.WrapListItems
import eu.europa.ec.uilogic.component.wrap.WrapModalBottomSheet
import eu.europa.ec.uilogic.extension.applyTestTag
import eu.europa.ec.uilogic.extension.paddingFrom
import eu.europa.ec.uilogic.navigation.helper.navigateToRoute
import eu.europa.ec.uilogic.navigation.helper.popBackStackTo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.document_details_bottom_sheet_delete_primary_button_text
import eu.europa.ec.shared.resources.document_details_bottom_sheet_delete_secondary_button_text
import eu.europa.ec.shared.resources.document_details_bottom_sheet_delete_subtitle
import eu.europa.ec.shared.resources.document_details_bottom_sheet_delete_title
import eu.europa.ec.shared.resources.document_details_document_credentials_info_text
import eu.europa.ec.shared.resources.document_details_issuer_section_text
import eu.europa.ec.shared.resources.document_details_main_section_text
import eu.europa.ec.shared.resources.document_details_secondary_button_text
import eu.europa.ec.shared.resources.document_details_toolbar_action_reissue
import eu.europa.ec.shared.resources.document_details_toolbar_action_remove

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentDetailsScreen(
    navigator: AppNavigator,
    viewModel: DocumentDetailsViewModel,
    /**
     * The external deep link waiting to be handled, if any. Injected rather than read through a seam
     * because reading it *consumes* it on Android (it clears the cached intent), so it must be a call
     * the screen makes once per resume rather than a remembered value.
     */
    pendingDeepLink: () -> String? = { null },
    /**
     * Hands an external deep link to whatever flow owns it, popping back to [AppRoute] first when one
     * is given.
     *
     * Injected by the host instead of hidden behind an expect/actual, because the Android
     * implementation is `handleDeepLinkAction`, which lives in `:ui-logic` and reaches the RQES UI SDK
     * and `EudiComponentActivity` — and `:ui-logic` depends on `:shared-ui`, so an actual in this
     * module could not call it without inverting the dependency. The iOS default does nothing; iOS deep
     * links arrive through the app delegate and are not wired yet.
     */
    onExternalDeepLink: (link: String, routeToPop: AppRoute?) -> Unit = { _, _ -> },
) {
    val state: State by viewModel.viewState.collectAsStateWithLifecycle()

    val isBottomSheetOpen = state.isBottomSheetOpen
    val scope = rememberCoroutineScope()
    val bottomSheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
    val platformContext = rememberPlatformContextOrNull()

    val toolbarConfig = getToolbarConfig(
        platformContext = platformContext,
        state = state,
        onEventSend = { viewModel.setEvent(it) }
    )

    ContentScreen(
        isLoading = state.isLoading,
        contentErrorConfig = state.error,
        navigatableAction = ScreenNavigateAction.BACKABLE,
        onBack = { viewModel.setEvent(Event.Pop) },
        toolBarConfig = toolbarConfig,
        broadcastAction = BroadcastAction(
            intentFilters = listOf(
                CoreActions.REVOCATION_WORK_REFRESH_DETAILS_ACTION,
                CoreActions.RE_ISSUANCE_WORK_REFRESH_DETAILS_ACTION,
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

                    CoreActions.REVOCATION_IDS_DETAILS_EXTRA -> {
                        val ids =
                            it.platformStringListExtra(CoreActions.REVOCATION_IDS_DETAILS_EXTRA)

                        viewModel.setEvent(Event.OnRevocationStatusChanged(ids))
                    }

                    CoreActions.RE_ISSUANCE_WORK_REFRESH_DETAILS_ACTION -> {
                        val ids =
                            it.platformStringListExtra(CoreActions.RE_ISSUANCE_IDS_DETAILS_EXTRA)

                        viewModel.setEvent(Event.OnReIssuanceTriggered(ids))
                    }
                }
            }
        )
    ) { paddingValues ->
        Content(
            platformContext = platformContext,
            state = state,
            effectFlow = viewModel.effect,
            onEventSend = { viewModel.setEvent(it) },
            onNavigationRequested = { navigationEffect ->
                handleNavigationEffect(
                    navigationEffect = navigationEffect,
                    navigator = navigator,
                    onExternalDeepLink = onExternalDeepLink,
                )
            },
            paddingValues = paddingValues,
            coroutineScope = scope,
            modalBottomSheetState = bottomSheetState,
        )

        if (isBottomSheetOpen) {
            WrapModalBottomSheet(
                onDismissRequest = {
                    viewModel.setEvent(
                        Event.BottomSheet.UpdateBottomSheetState(
                            isOpen = false
                        )
                    )
                },
                sheetState = bottomSheetState
            ) {
                SheetContent(
                    sheetContent = state.sheetContent,
                    onEventSent = {
                        viewModel.setEvent(it)
                    }
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

@Composable
private fun getToolbarConfig(
    platformContext: PlatformContext?,
    state: State,
    onEventSend: (Event) -> Unit
): ToolbarConfig {
    return ToolbarConfig(
        actions = if (state.error == null) {
            listOf(
                ToolbarActionUi(
                    icon = if (state.isDocumentBookmarked) AppIcons.BookmarkFilled else AppIcons.Bookmark,
                    onClick = { onEventSend(Event.BookmarkPressed) },
                    enabled = !state.isLoading,
                    throttleClicks = true,
                ),
                ToolbarActionUi(
                    text = stringResource(Res.string.document_details_toolbar_action_reissue),
                    icon = null,
                    // Re-issuance needs the Android platform handle; there is nothing to re-issue
                    // through on iOS, where this is null.
                    onClick = {
                        platformContext?.let {
                            onEventSend(Event.IssuerDetails.OnActionButtonClicked(it))
                        }
                    },
                    enabled = !state.isLoading && state.issuerDetails?.documentState != IssuerDetailsCardDataUi.DocumentState.Revoked,
                    throttleClicks = true,
                ),
                ToolbarActionUi(
                    text = stringResource(Res.string.document_details_toolbar_action_remove),
                    icon = null,
                    onClick = { onEventSend(Event.SecondaryButtonPressed) },
                    enabled = !state.isLoading,
                    throttleClicks = true,
                )
            )
        } else {
            emptyList()
        },
        maxVisibleActions = 1
    )
}

private fun handleNavigationEffect(
    navigationEffect: Effect.Navigation,
    navigator: AppNavigator,
    onExternalDeepLink: (link: String, routeToPop: AppRoute?) -> Unit,
) {
    when (navigationEffect) {
        is Effect.Navigation.SwitchScreen -> {
            navigator.navigateToRoute(
                route = navigationEffect.route,
                popUpTo = navigationEffect.popUpTo,
                popUpToInclusive = navigationEffect.inclusive == true,
            )
        }

        is Effect.Navigation.DeepLink -> onExternalDeepLink(
            navigationEffect.link,
            navigationEffect.routeToPop,
        )

        is Effect.Navigation.Pop -> navigator.pop()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Content(
    platformContext: PlatformContext?,
    state: State,
    effectFlow: Flow<Effect>,
    onEventSend: (Event) -> Unit,
    onNavigationRequested: (Effect.Navigation) -> Unit,
    paddingValues: PaddingValues,
    coroutineScope: CoroutineScope,
    modalBottomSheetState: SheetState,
) {
    state.documentDetailsUi?.let { safeDocumentDetailsUi ->
        Column(
            modifier = Modifier
                .paddingFrom(paddingValues, bottom = false)
        ) {

            // Screen title
            state.title?.let { safeTitle ->
                ContentTitle(
                    title = safeTitle,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(SPACING_EXTRA_LARGE.dp)
            ) {
                state.issuerDetails?.let { safeIssuerDetails ->
                    IssuerDetails(
                        modifier = Modifier.fillMaxWidth(),
                        data = safeIssuerDetails,
                        onExpandedStateChanged = {
                            onEventSend(Event.IssuerDetails.OnExpandedStateChanged)
                        },
                        onActionButtonClick = {
                            platformContext?.let { onEventSend(Event.IssuerDetails.OnActionButtonClicked(it)) }
                        }
                    )
                }

                DocumentDetails(
                    modifier = Modifier.fillMaxWidth(),
                    onEventSend = onEventSend,
                    documentDetailsUi = safeDocumentDetailsUi,
                    hideSensitiveContent = state.hideSensitiveContent,
                    isLoading = state.isLoading
                )

                BottomSection(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding(),
                    credentialsInfoUi = state.documentCredentialsInfoUi,
                    onEventSend = onEventSend
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        effectFlow.onEach { effect ->
            when (effect) {
                is Effect.Navigation -> onNavigationRequested(effect)

                is Effect.CloseBottomSheet -> {
                    coroutineScope.launch {
                        modalBottomSheetState.hide()
                    }.invokeOnCompletion {
                        if (!modalBottomSheetState.isVisible) {
                            onEventSend(Event.BottomSheet.UpdateBottomSheetState(isOpen = false))
                        }
                    }
                }

                is Effect.ShowBottomSheet -> {
                    onEventSend(Event.BottomSheet.UpdateBottomSheetState(isOpen = true))
                }

                is Effect.BookmarkStored -> {
                    onEventSend(Event.OnBookmarkStored)
                }

                is Effect.BookmarkRemoved -> {
                    onEventSend(Event.OnBookmarkRemoved)
                }
            }
        }.collect()
    }
}

@Composable
private fun SheetContent(
    sheetContent: DocumentDetailsBottomSheetContent,
    onEventSent: (event: Event) -> Unit,
) {
    when (sheetContent) {
        is DocumentDetailsBottomSheetContent.DeleteDocumentConfirmation ->
            DialogBottomSheet(
                textData = BottomSheetTextDataUi(
                    title = UiText.Resource(
                        Res.string.document_details_bottom_sheet_delete_title
                    ),
                    message = UiText.Resource(
                        Res.string.document_details_bottom_sheet_delete_subtitle
                    ),
                    positiveButtonText = UiText.Resource(Res.string.document_details_bottom_sheet_delete_primary_button_text),
                    negativeButtonText = UiText.Resource(Res.string.document_details_bottom_sheet_delete_secondary_button_text),
                    isPositiveButtonWarning = true,
                ),
                leadingIcon = AppIcons.Delete,
                leadingIconTint = MaterialTheme.colorScheme.error,
                onPositiveClick = { onEventSent(Event.BottomSheet.Delete.PrimaryButtonPressed) },
                positiveButtonTestTag = TestTag.DocumentDetailsScreen.BOTTOM_SHEET_DELETE_DOCUMENT_POSITIVE_BUTTON,
                onNegativeClick = { onEventSent(Event.BottomSheet.Delete.SecondaryButtonPressed) },
                negativeButtonTestTag = TestTag.DocumentDetailsScreen.BOTTOM_SHEET_DELETE_DOCUMENT_NEGATIVE_BUTTON,
            )

        is DocumentDetailsBottomSheetContent.BookmarkStoredInfo -> {
            SimpleBottomSheet(
                textData = sheetContent.bottomSheetTextData,
                leadingIcon = AppIcons.BookmarkFilled,
                leadingIconTint = MaterialTheme.colorScheme.warning,
            )
        }

        is DocumentDetailsBottomSheetContent.BookmarkRemovedInfo -> {
            SimpleBottomSheet(
                textData = sheetContent.bottomSheetTextData,
                leadingIcon = AppIcons.BookmarkFilled,
                leadingIconTint = MaterialTheme.colorScheme.error,
            )
        }

        is DocumentDetailsBottomSheetContent.TrustedRelyingPartyInfo -> {
            SimpleBottomSheet(
                textData = sheetContent.bottomSheetTextData,
                leadingIcon = AppIcons.Verified,
                leadingIconTint = MaterialTheme.colorScheme.success,
            )
        }

        is DocumentDetailsBottomSheetContent.IssuerNotTrusted -> {
            IssuerNotTrustedSheetContent(
                onClose = {
                    onEventSent(Event.BottomSheet.IssuerNotTrusted.CloseButtonPressed)
                },
            )
        }
    }
}

@Composable
private fun IssuerDetails(
    modifier: Modifier = Modifier,
    data: IssuerDetailsCardDataUi,
    onExpandedStateChanged: () -> Unit,
    onActionButtonClick: () -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(SPACING_MEDIUM.dp)
    ) {
        SectionTitle(
            modifier = Modifier.fillMaxWidth(),
            text = stringResource(Res.string.document_details_issuer_section_text),
        )
        IssuerDetailsCard(
            modifier = Modifier.fillMaxWidth(),
            data = data,
            onExpandedChange = onExpandedStateChanged,
            onActionButtonClick = onActionButtonClick,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceDim,
            ),
        )
    }
}

@Composable
private fun DocumentDetails(
    modifier: Modifier = Modifier,
    onEventSend: (Event) -> Unit,
    documentDetailsUi: DocumentDetailsUi,
    hideSensitiveContent: Boolean,
    isLoading: Boolean,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(SPACING_MEDIUM.dp)
    ) {
        SectionTitle(
            modifier = Modifier.fillMaxWidth(),
            text = stringResource(Res.string.document_details_main_section_text),
            icon = if (hideSensitiveContent) AppIcons.Visibility else AppIcons.VisibilityOff,
            onIconClick = { onEventSend(Event.ChangeContentVisibility) },
            iconEnabled = !isLoading,
            throttleIconClicks = false,
        )

        WrapListItems(
            modifier = Modifier.fillMaxWidth(),
            items = documentDetailsUi.documentClaims,
            hideSensitiveContent = hideSensitiveContent,
            onExpandedChange = { item ->
                onEventSend(Event.ClaimClicked(itemId = item.itemId))
            },
            onItemClick = null,
            throttleClicks = false,
        )
    }
}

@Composable
private fun BottomSection(
    modifier: Modifier = Modifier,
    credentialsInfoUi: DocumentCredentialsInfoUi?,
    onEventSend: (Event) -> Unit
) {
    Column(modifier = modifier) {
        WrapButton(
            modifier = Modifier
                .applyTestTag(TestTag.DocumentDetailsScreen.DELETE_BUTTON)
                .fillMaxWidth()
                .padding(bottom = SPACING_LARGE.dp),
            buttonConfig = ButtonConfig(
                type = ButtonType.SECONDARY,
                onClick = { onEventSend(Event.SecondaryButtonPressed) },
                isWarning = true,
            )
        ) {
            Text(
                text = stringResource(Res.string.document_details_secondary_button_text),
                style = MaterialTheme.typography.labelLarge
            )
        }

        credentialsInfoUi?.let { safeCredentialsInfoUi ->
            Text(
                text = safeCredentialsInfoUi.title,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@ThemeModePreviews
@Composable
private fun DocumentDetailsScreenPreview() {
    PreviewTheme {
        val availableCredentials = 3
        val totalCredentials = 15
        val state = State(
            issuerDetails = IssuerDetailsCardDataUi(
                issuerName = "Issuer Name",
                issuerLogo = null,
                documentState = IssuerDetailsCardDataUi.DocumentState.Issued(
                    issuanceDate = "16 February 2024 - 13:18",
                    expirationDate = "22 March 2030"
                ),
                isExpanded = true,
            ),
            documentCredentialsInfoUi = DocumentCredentialsInfoUi(
                availableCredentials = availableCredentials,
                totalCredentials = totalCredentials,
                title = stringResource(
                    Res.string.document_details_document_credentials_info_text,
                    availableCredentials,
                    totalCredentials
                ),
            ),
            documentDetailsUi = DocumentDetailsUi(
                documentId = "1",
                issuerId = "Id",
                documentConfigId = "Id",
                documentName = "Mobile Driving License",
                documentIdentifier = DocumentIdentifier.OTHER(formatType = "org.iso.18013.5.1.mDL"),
                documentClaims = listOf(
                    ExpandableListItemUi.SingleListItem(
                        header = ListItemDataUi(
                            itemId = "1",
                            mainContentData = ListItemMainContentDataUi.Text(text = ""),
                            overlineText = "A reproduction of the mDL holder’s portrait.",
                            leadingContentData = ListItemLeadingContentDataUi.UserImage(
                                userBase64Image = ""
                            ),
                        )
                    ),
                    ExpandableListItemUi.SingleListItem(
                        header = ListItemDataUi(
                            itemId = "2",
                            mainContentData = ListItemMainContentDataUi.Text(text = "GR"),
                            overlineText = "Alpha-2 country code, as defined in ISO 3166-1 of the issuing authority’s country or territory.",
                        )
                    ),
                    ExpandableListItemUi.SingleListItem(
                        header = ListItemDataUi(
                            itemId = "3",
                            mainContentData = ListItemMainContentDataUi.Text(text = "12345678900"),
                            overlineText = "An audit control number assigned by the issuing authority.",
                        )
                    ),
                    ExpandableListItemUi.SingleListItem(
                        header = ListItemDataUi(
                            itemId = "4",
                            mainContentData = ListItemMainContentDataUi.Text(text = "31 Dec 2040"),
                            overlineText = "Date when mDL expires.",
                        )
                    )
                ),
            ),
            hideSensitiveContent = false,
            sheetContent = DocumentDetailsBottomSheetContent.DeleteDocumentConfirmation
        )

        Content(
            platformContext = rememberPlatformContextOrNull(),
            state = state,
            effectFlow = Channel<Effect>().receiveAsFlow(),
            onEventSend = {},
            onNavigationRequested = {},
            paddingValues = PaddingValues(SPACING_MEDIUM.dp),
            coroutineScope = rememberCoroutineScope(),
            modalBottomSheetState = rememberModalBottomSheetState(),
        )
    }
}