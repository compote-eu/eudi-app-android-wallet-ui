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
// Rebuilt by upstream 7a47e46a around the shared transaction-log domain: a summary card whose
// metadata (purpose, privacy policies, contacts, intermediary) expands, per-type sections — data
// requested and shared, credentials, the signed file, the parties of a deletion request or report —
// and deleting the record behind a confirmation sheet. Both platforms render it from the one shared
// TransactionDetailsInteractorImpl; links open through PlatformScreenActions.
package eu.europa.ec.dashboardfeature.ui.transactions.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.europa.ec.dashboardfeature.ui.transactions.detail.model.TransactionDetailsBodyUi
import eu.europa.ec.dashboardfeature.ui.transactions.detail.model.TransactionDetailsCardUi
import eu.europa.ec.dashboardfeature.ui.transactions.detail.model.TransactionDetailsFieldUi
import eu.europa.ec.dashboardfeature.ui.transactions.detail.model.TransactionDetailsGroupUi
import eu.europa.ec.dashboardfeature.ui.transactions.detail.model.TransactionDetailsItemUi
import eu.europa.ec.dashboardfeature.ui.transactions.detail.model.TransactionDetailsMetadataUi
import eu.europa.ec.dashboardfeature.ui.transactions.detail.model.TransactionDetailsSectionUi
import eu.europa.ec.dashboardfeature.ui.transactions.detail.model.TransactionDetailsUi
import eu.europa.ec.resourceslogic.theme.values.success
import eu.europa.ec.shared.navigation.AppNavigator
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.shared.resources.asUiText
import eu.europa.ec.shared.resources.generic_cancel
import eu.europa.ec.shared.resources.generic_hide_details
import eu.europa.ec.shared.resources.generic_view_details
import eu.europa.ec.shared.resources.resolve
import eu.europa.ec.shared.resources.transaction_details_bottom_sheet_delete_subtitle
import eu.europa.ec.shared.resources.transaction_details_bottom_sheet_delete_title
import eu.europa.ec.shared.resources.transaction_details_delete_button
import eu.europa.ec.shared.resources.transaction_details_screen_card_date_label
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.ListItemDataUi
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import eu.europa.ec.uilogic.component.ListItemSupportingContentDataUi
import eu.europa.ec.uilogic.component.ListItemTrailingContentDataUi
import eu.europa.ec.uilogic.component.PlatformScreenActions
import eu.europa.ec.uilogic.component.SectionTitle
import eu.europa.ec.uilogic.component.content.ContentScreen
import eu.europa.ec.uilogic.component.content.ContentTitle
import eu.europa.ec.uilogic.component.content.ScreenNavigateAction
import eu.europa.ec.uilogic.component.content.ToolbarActionUi
import eu.europa.ec.uilogic.component.content.ToolbarConfig
import eu.europa.ec.uilogic.component.preview.LargeTextPreviews
import eu.europa.ec.uilogic.component.preview.PreviewTheme
import eu.europa.ec.uilogic.component.preview.ThemeModePreviews
import eu.europa.ec.uilogic.component.rememberPlatformScreenActions
import eu.europa.ec.uilogic.component.utils.SIZE_SMALL
import eu.europa.ec.uilogic.component.utils.SPACING_LARGE
import eu.europa.ec.uilogic.component.utils.SPACING_MEDIUM
import eu.europa.ec.uilogic.component.utils.SPACING_SMALL
import eu.europa.ec.uilogic.component.wrap.BottomSheetTextDataUi
import eu.europa.ec.uilogic.component.wrap.DialogBottomSheet
import eu.europa.ec.uilogic.component.wrap.ExpandableListItemUi
import eu.europa.ec.uilogic.component.wrap.WrapCard
import eu.europa.ec.uilogic.component.wrap.WrapChip
import eu.europa.ec.uilogic.component.wrap.WrapExpandableCard
import eu.europa.ec.uilogic.component.wrap.WrapExpandableListItem
import eu.europa.ec.uilogic.component.wrap.WrapListItem
import eu.europa.ec.uilogic.component.wrap.WrapModalBottomSheet
import eu.europa.ec.uilogic.component.wrap.WrapTextButton
import eu.europa.ec.uilogic.extension.paddingFrom
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailsScreen(
    navigator: AppNavigator,
    viewModel: TransactionDetailsViewModel,
) {
    val state: State by viewModel.viewState.collectAsStateWithLifecycle()
    val platformActions = rememberPlatformScreenActions()
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val bottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val details = state.transactionDetailsUi

    ContentScreen(
        isLoading = state.isLoading,
        contentErrorConfig = state.error,
        toolBarConfig = getToolbarConfig(
            showDelete = state.error == null && details != null,
            enabled = !state.isLoading,
            onDeleteClick = { viewModel.setEvent(Event.DeletePressed) },
        ),
        navigatableAction = ScreenNavigateAction.BACKABLE,
        onBack = { viewModel.setEvent(Event.Pop) },
    ) { paddingValues ->
        Content(
            modifier = Modifier
                .paddingFrom(paddingValues, bottom = false)
                .verticalScroll(scrollState)
                .navigationBarsPadding(),
            state = state,
            onEventSend = { event -> viewModel.setEvent(event) },
        )

        if (state.isBottomSheetOpen) {
            WrapModalBottomSheet(
                onDismissRequest = {
                    viewModel.setEvent(Event.BottomSheet.UpdateBottomSheetState(isOpen = false))
                },
                sheetState = bottomSheetState,
            ) {
                SheetContent(
                    sheetContent = state.sheetContent,
                    onConfirm = { viewModel.setEvent(Event.BottomSheet.Delete.PrimaryButtonPressed) },
                    onCancel = { viewModel.setEvent(Event.BottomSheet.Delete.SecondaryButtonPressed) },
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is Effect.Navigation -> handleNavigationEffect(effect, navigator, platformActions)
                is Effect.ShowBottomSheet -> {
                    viewModel.setEvent(Event.BottomSheet.UpdateBottomSheetState(isOpen = true))
                }

                is Effect.CloseBottomSheet -> {
                    scope.launch {
                        bottomSheetState.hide()
                    }.invokeOnCompletion {
                        if (!bottomSheetState.isVisible) {
                            viewModel.setEvent(Event.BottomSheet.UpdateBottomSheetState(isOpen = false))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Content(
    modifier: Modifier,
    state: State,
    onEventSend: (Event) -> Unit,
) {
    val details = state.transactionDetailsUi

    Column(modifier = modifier) {
        // Screen title.
        ContentTitle(title = state.title.resolve())

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = SPACING_MEDIUM.dp),
            verticalArrangement = Arrangement.spacedBy(SPACING_MEDIUM.dp),
        ) {
            // Transaction summary and expandable metadata.
            details?.transactionDetailsCardUi?.let { summary ->
                TransactionDetailsCard(
                    modifier = Modifier.fillMaxWidth(),
                    item = summary,
                    isExpanded = state.isCardExpanded,
                    onExpandedChange = { onEventSend(Event.ExpandOrCollapseCard) },
                    onLinkClick = { url -> onEventSend(Event.OpenLink(url)) },
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(SPACING_LARGE.dp),
            ) {
                // Requested/shared data, credential identifiers or the signing filename.
                details?.body?.sections.orEmpty().forEach { section ->
                    DetailsSection(
                        section = section,
                        expandedGroupIds = state.expandedGroupIds,
                        onGroupExpandedChange = { id ->
                            onEventSend(Event.ExpandOrCollapseGroupItem(id))
                        },
                        onLinkClick = { url -> onEventSend(Event.OpenLink(url)) },
                    )
                }
            }
        }
    }
}

private fun getToolbarConfig(
    showDelete: Boolean,
    enabled: Boolean,
    onDeleteClick: () -> Unit,
): ToolbarConfig = ToolbarConfig(
    actions = if (showDelete) {
        listOf(
            ToolbarActionUi(
                icon = AppIcons.Delete,
                enabled = enabled,
                onClick = onDeleteClick,
            )
        )
    } else {
        emptyList()
    }
)

@Composable
private fun SheetContent(
    sheetContent: TransactionDetailsBottomSheetContent,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    when (sheetContent) {
        is TransactionDetailsBottomSheetContent.DeleteTransactionConfirmation -> DialogBottomSheet(
            textData = BottomSheetTextDataUi(
                title = UiText.Resource(Res.string.transaction_details_bottom_sheet_delete_title),
                message = UiText.Resource(Res.string.transaction_details_bottom_sheet_delete_subtitle),
                positiveButtonText = UiText.Resource(Res.string.transaction_details_delete_button),
                negativeButtonText = UiText.Resource(Res.string.generic_cancel),
                isPositiveButtonWarning = true,
            ),
            leadingIcon = AppIcons.DeleteFilled,
            leadingIconTint = MaterialTheme.colorScheme.error,
            onPositiveClick = onConfirm,
            onNegativeClick = onCancel,
        )
    }
}

private fun handleNavigationEffect(
    navigationEffect: Effect.Navigation,
    navigator: AppNavigator,
    platformActions: PlatformScreenActions,
) {
    when (navigationEffect) {
        is Effect.Navigation.Pop -> navigator.pop()
        is Effect.Navigation.OpenUrlExternally -> platformActions.openUrlExternally(navigationEffect.url)
    }
}

@Composable
private fun TransactionDetailsCard(
    modifier: Modifier,
    item: TransactionDetailsCardUi,
    isExpanded: Boolean,
    onExpandedChange: () -> Unit,
    onLinkClick: (String) -> Unit,
) {
    WrapExpandableCard(
        modifier = modifier,
        isExpanded = isExpanded && item.metadata.isNotEmpty(),
        onExpandedChange = null,
        cardCollapsedContent = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        top = SPACING_MEDIUM.dp,
                        start = SPACING_MEDIUM.dp,
                        end = SPACING_MEDIUM.dp,
                        bottom = if (item.metadata.isEmpty()) SPACING_SMALL.dp else 0.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(SPACING_SMALL.dp),
            ) {
                TransactionPartySummary(
                    transactionType = item.transactionTypeLabel,
                    partyName = item.partyName,
                    providerType = item.providerType,
                )
                TransactionDateAndStatus(
                    date = item.transactionDate,
                    status = item.transactionStatusLabel,
                    isCompleted = item.transactionIsCompleted,
                )
                item.nonCompletionReason?.let { reason ->
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item.metadata.isNotEmpty()) {
                    WrapTextButton(
                        modifier = Modifier.fillMaxWidth(),
                        text = stringResource(
                            if (isExpanded) Res.string.generic_hide_details
                            else Res.string.generic_view_details,
                        ),
                        enabled = true,
                        trailingIcon = null,
                        onClick = onExpandedChange,
                    )
                }
            }
        },
        cardExpandedContent = {
            TransactionMetadataPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = SPACING_MEDIUM.dp,
                        top = SPACING_SMALL.dp,
                        end = SPACING_MEDIUM.dp,
                        bottom = SPACING_MEDIUM.dp,
                    ),
                groups = item.metadata,
                onLinkClick = onLinkClick,
            )
        },
        shape = RoundedCornerShape(12.dp),
        colors = null,
        throttleClicks = true,
    )
}

@Composable
private fun TransactionPartySummary(
    transactionType: String,
    partyName: String?,
    providerType: String?,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SPACING_SMALL.dp),
    ) {
        Text(
            text = transactionType,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        partyName?.let { name ->
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        providerType?.let { type ->
            Text(
                text = type,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TransactionDateAndStatus(
    date: String,
    status: String,
    isCompleted: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SPACING_SMALL.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.transaction_details_screen_card_date_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = date,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TransactionStatusChip(
            label = status,
            isCompleted = isCompleted
        )
    }
}

@Composable
private fun TransactionStatusChip(
    modifier: Modifier = Modifier,
    label: String,
    isCompleted: Boolean
) {
    WrapChip(
        modifier = modifier,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge
            )
        },
        colors = InputChipDefaults.inputChipColors(
            containerColor = if (isCompleted) MaterialTheme.colorScheme.success
            else MaterialTheme.colorScheme.error,
            labelColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
        border = null,
    )
}

@Composable
private fun TransactionMetadataPanel(
    modifier: Modifier,
    groups: List<TransactionDetailsMetadataUi>,
    onLinkClick: (String) -> Unit,
) {
    WrapCard(
        modifier = modifier,
        shape = RoundedCornerShape(SIZE_SMALL.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceDim
        ),
    ) {
        groups.forEachIndexed { index, group ->
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = SPACING_MEDIUM.dp)
                )
            }
            TransactionMetadataGroup(
                group = group,
                onLinkClick = onLinkClick
            )
        }
    }
}

@Composable
private fun TransactionMetadataGroup(
    group: TransactionDetailsMetadataUi,
    onLinkClick: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        group.fields.forEach { field ->
            TransactionDetailField(
                item = field.item,
                onClick = field.url?.let { url ->
                    { onLinkClick(url) }
                },
            )
        }
    }
}

@Composable
private fun TransactionDetailField(
    item: ListItemDataUi,
    onClick: (() -> Unit)?,
) {
    WrapListItem(
        modifier = Modifier.fillMaxWidth(),
        item = item,
        onItemClick = onClick?.let { onFieldClick ->
            { onFieldClick() }
        },
        mainContentVerticalPadding = SPACING_MEDIUM.dp,
        mainContentTextStyle = MaterialTheme.typography.bodyMedium.copy(
            color = if (onClick != null) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        overlineTextStyle = {
            MaterialTheme.typography.labelSmall.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        shape = RectangleShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceDim
        ),
    )
}

@Composable
private fun DetailsSection(
    section: TransactionDetailsSectionUi,
    expandedGroupIds: Set<String>,
    onGroupExpandedChange: (String) -> Unit,
    onLinkClick: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SPACING_MEDIUM.dp),
    ) {
        SectionTitle(
            modifier = Modifier.fillMaxWidth(),
            text = section.title
        )

        section.emptyItem?.let { emptyItem ->
            WrapListItem(
                modifier = Modifier.fillMaxWidth(),
                item = emptyItem,
                onItemClick = null,
                mainContentVerticalPadding = SPACING_MEDIUM.dp,
                mainContentTextStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceDim,
                ),
            )
        }

        section.items.forEach { row ->
            WrapListItem(
                modifier = Modifier.fillMaxWidth(),
                item = row.item,
                onItemClick = row.url?.let { url ->
                    { onLinkClick(url) }
                },
                mainContentVerticalPadding = SPACING_MEDIUM.dp,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceDim
                ),
            )
        }

        section.groups.forEach { group ->
            CredentialGroup(
                group = group,
                isExpanded = group.header.itemId in expandedGroupIds,
                onExpandedChange = { onGroupExpandedChange(group.header.itemId) },
            )
        }
    }
}

@Composable
private fun CredentialGroup(
    group: TransactionDetailsGroupUi,
    isExpanded: Boolean,
    onExpandedChange: () -> Unit,
) {
    WrapExpandableListItem(
        modifier = Modifier.fillMaxWidth(),
        header = group.header,
        data = group.items,
        onItemClick = null,
        onExpandedChange = { onExpandedChange() },
        isExpanded = isExpanded,
        collapsedMainContentVerticalPadding = SPACING_MEDIUM.dp,
        expandedMainContentVerticalPadding = SPACING_MEDIUM.dp,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceDim),
    )
}

@ThemeModePreviews
@Composable
private fun DeleteTransactionConfirmationPreview() {
    PreviewTheme {
        SheetContent(
            sheetContent = TransactionDetailsBottomSheetContent.DeleteTransactionConfirmation,
            onConfirm = {},
            onCancel = {},
        )
    }
}

@ThemeModePreviews
@Composable
private fun TransactionDetailsPreview(
    @PreviewParameter(TransactionDetailsPreviewProvider::class) state: State,
) {
    PreviewTheme {
        Content(
            modifier = Modifier
                .padding(SPACING_MEDIUM.dp)
                .verticalScroll(rememberScrollState()),
            state = state,
            onEventSend = {},
        )
    }
}

@LargeTextPreviews
@Composable
private fun TransactionDetailsCardLargeTextPreview() {
    PreviewTheme {
        TransactionDetailsCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SPACING_MEDIUM.dp),
            item = TransactionDetailsCardUi(
                transactionTypeLabel = "Presentation",
                transactionStatusLabel = "Not completed",
                transactionIsCompleted = false,
                transactionDate = "16 Feb 2024 01:18 PM",
                partyName = "Example relying party",
                providerType = "ServiceProvider",
                nonCompletionReason = "The connection was interrupted.",
                metadata = emptyList(),
            ),
            isExpanded = false,
            onExpandedChange = {},
            onLinkClick = {},
        )
    }
}

private class TransactionDetailsPreviewProvider : PreviewParameterProvider<State> {
    override val values: Sequence<State>
        get() {
            val contact = "https://example.com/support"
            val presentation = TransactionDetailsBodyUi.Presentation(
                requested = previewClaims("requested", "DATA REQUESTED", isExpanded = false),
                shared = previewClaims("shared", "DATA SHARED", isExpanded = false),
            )
            val contacts = TransactionDetailsMetadataUi(
                fields = listOf(
                    TransactionDetailsFieldUi("party:contact:0", "Contact", "EU", null),
                    TransactionDetailsFieldUi(
                        "party:contact:1",
                        "Contact",
                        contact,
                        contact
                    ),
                    TransactionDetailsFieldUi(
                        "party:contact:2",
                        "Contact",
                        contact,
                        contact
                    ),
                ),
            )
            val card = TransactionDetailsCardUi(
                transactionTypeLabel = "Presentation",
                transactionStatusLabel = "Completed",
                transactionIsCompleted = true,
                transactionDate = "16 Feb 2024 01:18 PM",
                partyName = "TravelBook",
                providerType = "ServiceProvider",
                nonCompletionReason = null,
                metadata = listOf(
                    TransactionDetailsMetadataUi(
                        listOf(
                            TransactionDetailsFieldUi(
                                "purpose", "Intended use",
                                "We will use your identity and age to verify you for a new current account. Your data will be used once to complete onboarding and to meet anti-money laundering requirements.",
                                null,
                            ),
                        ),
                    ),
                    TransactionDetailsMetadataUi(
                        listOf(
                            TransactionDetailsFieldUi(
                                "privacy:0", "Privacy policy",
                                "https://data.europa.eu/eudi/policy/trust-service-practice-statement",
                                "https://data.europa.eu/eudi/policy/trust-service-practice-statement",
                            ),
                        ),
                    ),
                    contacts,
                    TransactionDetailsMetadataUi(
                        listOf(
                            TransactionDetailsFieldUi(
                                "intermediary:name",
                                "Intermediary name",
                                "Verifier Signer dev",
                                null
                            )
                        ),
                    ),
                ),
            )
            val base = State(
                title = "Transaction information".asUiText(),
                transactionDetailsUi = TransactionDetailsUi("presentation", card, presentation),
            )
            val empty = presentation.copy(
                requested = TransactionDetailsSectionUi(
                    title = "DATA REQUESTED",
                    items = emptyList(),
                    groups = emptyList(),
                    emptyItem = ListItemDataUi(
                        itemId = "requested:empty",
                        mainContentData = ListItemMainContentDataUi.Text("No data requested"),
                    ),
                ),
                shared = TransactionDetailsSectionUi(
                    title = "DATA SHARED",
                    items = emptyList(),
                    groups = emptyList(),
                    emptyItem = ListItemDataUi(
                        itemId = "shared:empty",
                        mainContentData = ListItemMainContentDataUi.Text("No data shared"),
                    ),
                ),
            )
            val states = listOf(
                base,
                base.copy(
                    transactionDetailsUi = TransactionDetailsUi(
                        "presentation",
                        card,
                        presentation.copy(
                            requested = previewClaims(
                                "requested",
                                "DATA REQUESTED",
                                isExpanded = true
                            ),
                        ),
                    ),
                    isCardExpanded = true,
                    expandedGroupIds = setOf("requested:0"),
                ),
                base.copy(
                    transactionDetailsUi = TransactionDetailsUi(
                        "empty",
                        card.copy(partyName = null, providerType = null, metadata = emptyList()),
                        empty,
                    ),
                ),
                base.copy(
                    transactionDetailsUi = TransactionDetailsUi(
                        "presentation",
                        card,
                        presentation.copy(requested = empty.requested),
                    ),
                ),
                base.copy(
                    transactionDetailsUi = TransactionDetailsUi(
                        "presentation",
                        card,
                        presentation.copy(shared = empty.shared),
                    ),
                ),
                base.copy(
                    transactionDetailsUi = TransactionDetailsUi(
                        "failed",
                        card.copy(
                            partyName = "An organisation with a very long recorded name that must wrap without truncating",
                            transactionStatusLabel = "Not completed",
                            transactionIsCompleted = false,
                            nonCompletionReason = "The requested data could not be shared because the connection was interrupted.",
                        ),
                        presentation,
                    ),
                ),
            )
            val credentials =
                previewFields("CREDENTIALS", null, "urn:eu.europa.ec.eudi:learning:credential:1")
            val issuedCredentials = credentials.copy(title = "CREDENTIALS ISSUED")
            val issuanceCounts = TransactionDetailsMetadataUi(
                fields = listOf(
                    TransactionDetailsFieldUi(
                        "requested-count",
                        "Credentials requested",
                        "1",
                        null
                    ),
                    TransactionDetailsFieldUi("issued-count", "Credentials issued", "1", null),
                ),
            )
            val otherTypes = listOf(
                Triple(
                    "Issuance",
                    TransactionDetailsBodyUi.Issuance(issuedCredentials),
                    listOf(issuanceCounts, contacts)
                ),
                Triple(
                    "Re-issuance",
                    TransactionDetailsBodyUi.Reissuance(issuedCredentials),
                    listOf(
                        issuanceCounts,
                        TransactionDetailsMetadataUi(
                            listOf(
                                TransactionDetailsFieldUi(
                                    "trigger",
                                    "Initiated by",
                                    "The Wallet",
                                    null
                                )
                            )
                        ),
                        contacts,
                    ),
                ),
                Triple("Deletion", TransactionDetailsBodyUi.Deletion(credentials), emptyList()),
                Triple(
                    "Signing",
                    TransactionDetailsBodyUi.Signing(
                        previewFields(
                            "DATA SIGNED",
                            "File name",
                            "fileXX.pdf"
                        )
                    ),
                    emptyList(),
                ),
                Triple(
                    "Signing",
                    TransactionDetailsBodyUi.Signing(null),
                    listOf(
                        TransactionDetailsMetadataUi(
                            listOf(
                                TransactionDetailsFieldUi(
                                    "signing-transaction",
                                    "Signing transaction identifier",
                                    "signing-123",
                                    null
                                )
                            )
                        )
                    ),
                ),
            ).map { (type, body, metadata) ->
                base.copy(
                    transactionDetailsUi = TransactionDetailsUi(
                        type,
                        card.copy(
                            transactionTypeLabel = type,
                            partyName = "Example organisation",
                            providerType = when (type) {
                                "Deletion" -> null
                                "Signing" -> "ESigESealCreationProvider"
                                else -> "PIDProvider"
                            },
                            metadata = metadata,
                        ),
                        body,
                    ),
                    isCardExpanded = true,
                )
            }
            return (states + otherTypes + base.copy(isLoading = true)).asSequence()
        }

    private fun previewFields(
        title: String,
        label: String?,
        value: String
    ): TransactionDetailsSectionUi {
        return TransactionDetailsSectionUi(
            title = title,
            items = listOf(
                TransactionDetailsItemUi(
                    item = ListItemDataUi(
                        itemId = title,
                        overlineText = label,
                        mainContentData = ListItemMainContentDataUi.Text(value),
                    ),
                    url = null,
                ),
            ),
            groups = emptyList(),
            emptyItem = null,
        )
    }

    private fun previewClaims(
        prefix: String,
        title: String,
        isExpanded: Boolean,
    ): TransactionDetailsSectionUi {
        return TransactionDetailsSectionUi(
            title = title,
            items = emptyList(),
            groups = listOf(
                TransactionDetailsGroupUi(
                    header = ListItemDataUi(
                        itemId = "$prefix:0",
                        mainContentData = ListItemMainContentDataUi.Text("eu.europa.ec.eudi.pid.1"),
                        supportingContentData = ListItemSupportingContentDataUi.Text("View details"),
                        trailingContentData = ListItemTrailingContentDataUi.Icon(
                            if (isExpanded) AppIcons.KeyboardArrowUp else AppIcons.KeyboardArrowDown,
                        ),
                    ),
                    items = listOf("family_name", "given_name").mapIndexed { index, claim ->
                        ExpandableListItemUi.SingleListItem(
                            header = ListItemDataUi(
                                itemId = "$prefix:0:$index",
                                mainContentData = ListItemMainContentDataUi.Text("[\"eu.europa.ec.eudi.pid.1\"][\"$claim\"]"),
                            ),
                        )
                    },
                ),
            ),
            emptyItem = null,
        )
    }
}
