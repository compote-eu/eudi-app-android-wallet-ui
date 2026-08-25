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

package eu.europa.ec.shared.ui.di

import eu.europa.ec.commonfeature.extension.toExpandableListItems
import eu.europa.ec.commonfeature.ui.request.model.DocumentFormatDomain
import eu.europa.ec.commonfeature.ui.request.model.DocumentPayloadDomain
import eu.europa.ec.corelogic.model.ClaimItemId
import eu.europa.ec.dashboardfeature.interactor.TransactionDetailsInteractor
import eu.europa.ec.dashboardfeature.interactor.TransactionDetailsInteractorPartialState
import eu.europa.ec.dashboardfeature.interactor.TransactionDetailsInteractorReportSuspiciousTransactionPartialState
import eu.europa.ec.dashboardfeature.interactor.TransactionDetailsInteractorRequestDataDeletionPartialState
import eu.europa.ec.dashboardfeature.ui.transactions.detail.model.TransactionDetailsCardUi
import eu.europa.ec.dashboardfeature.ui.transactions.detail.model.TransactionDetailsDataSharedHolderUi
import eu.europa.ec.dashboardfeature.ui.transactions.detail.model.TransactionDetailsUi
import eu.europa.ec.dashboardfeature.ui.transactions.model.TransactionStatusUi
import eu.europa.ec.dashboardfeature.ui.transactions.model.TransactionTypeUi
import eu.europa.ec.dashboardfeature.ui.transactions.model.toUiText
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.StringCatalog
import eu.europa.ec.shared.resources.generic_error_message
import eu.europa.ec.shared.resources.transaction_details_collapsed_supporting_text
import eu.europa.ec.shared.wallet.multipaz.IosSharedDocument
import eu.europa.ec.shared.wallet.multipaz.IosTransaction
import eu.europa.ec.shared.wallet.multipaz.IosTransactionKind
import eu.europa.ec.shared.wallet.multipaz.IosWalletEngine
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.ListItemDataUi
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import eu.europa.ec.uilogic.component.ListItemSupportingContentDataUi
import eu.europa.ec.uilogic.component.ListItemTrailingContentDataUi
import eu.europa.ec.uilogic.component.wrap.ExpandableListItemUi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.LocalDateTime

/**
 * What one recorded transaction shows, read back out of multipaz's event log.
 *
 * An iOS implementation of the shared contract rather than a bridge behind the Android one, and
 * deliberately so: `TransactionDetailsInteractorImpl` is built on wallet-core's `TransactionLog` and
 * `PresentedDocument` throughout, so a bridge narrow enough to serve both would have meant reshaping
 * that class first. The parts worth sharing already are — the claim rows come from commonMain's
 * `toExpandableListItems`, and the labels from the string catalog.
 *
 * The claims themselves are the ones the *consent screen* showed, because the event stored multipaz's
 * own requested-claim map and this reads it back through the same translation. So the details screen
 * says what the user agreed to, in the words they agreed to it in.
 */
internal class IosTransactionDetailsInteractor(
    private val engine: IosWalletEngine,
    private val strings: StringCatalog,
) : TransactionDetailsInteractor {

    override fun getTransactionDetails(
        transactionId: String
    ): Flow<TransactionDetailsInteractorPartialState> = flow {
        val transaction = runCatching { engine.getTransactions() }
            .getOrNull()
            ?.firstOrNull { it.id == transactionId }

        emit(
            if (transaction == null) {
                // Not only a bad link: entries age out of the log, so a transaction can genuinely
                // stop existing while a route pointing at it is still on the back stack.
                TransactionDetailsInteractorPartialState.Failure(
                    error = strings[Res.string.generic_error_message]
                )
            } else {
                TransactionDetailsInteractorPartialState.Success(
                    transactionDetailsUi = transaction.toDetailsUi()
                )
            }
        )
    }

    /**
     * Both are accepted without doing anything, exactly as on Android, where the two implementations
     * are `flowOf(Success)` with a TODO. Reporting a failure instead would be a worse lie: the button
     * is not wired to a backend on either platform.
     */
    override fun requestDataDeletion(
        transactionId: String
    ): Flow<TransactionDetailsInteractorRequestDataDeletionPartialState> = flow {
        emit(TransactionDetailsInteractorRequestDataDeletionPartialState.Success)
    }

    override fun reportSuspiciousTransaction(
        transactionId: String
    ): Flow<TransactionDetailsInteractorReportSuspiciousTransactionPartialState> = flow {
        emit(TransactionDetailsInteractorReportSuspiciousTransactionPartialState.Success)
    }

    private fun IosTransaction.toDetailsUi() = TransactionDetailsUi(
        transactionId = id,
        transactionDetailsCardUi = TransactionDetailsCardUi(
            transactionTypeLabel = when (kind) {
                IosTransactionKind.Presentation -> TransactionTypeUi.PRESENTATION
                IosTransactionKind.Issuance -> TransactionTypeUi.ISSUANCE
            }.toUiText(strings),
            // Always completed, because multipaz records an event only once the work has succeeded.
            transactionStatusLabel = TransactionStatusUi.Completed.toUiText(strings),
            transactionIsCompleted = true,
            transactionDate = createdAt.toDisplayableDateTime(),
            relyingPartyName = relyingPartyName,
            // Null rather than false: the card renders a verified badge for true and nothing at all
            // for null, and iOS resolves no trust, so it has no verdict to give either way.
            relyingPartyIsVerified = null,
        ),
        transactionDetailsDataShared = TransactionDetailsDataSharedHolderUi(
            dataSharedItems = sharedDocuments.map { it.toNestedListItem() },
        ),
        // Null because this wallet does not sign yet, not because iOS cannot — see `IosTransactionKind`.
        // Populate this when the RQES bridge lands.
        transactionDetailsDataSigned = null,
    )

    private fun IosSharedDocument.toNestedListItem(): ExpandableListItemUi.NestedListItem {
        val payload = DocumentPayloadDomain(
            docName = documentName,
            docId = documentId,
            // The claims carry their own format in their paths; the card only uses this to decide
            // whether to expect nesting, and an mdoc's flat rows are the safe reading of either.
            docFormatDomain = DocumentFormatDomain.MsoMdoc,
            docClaimsDomain = claims,
            queryId = null,
        )

        return ExpandableListItemUi.NestedListItem(
            header = ListItemDataUi(
                itemId = ClaimItemId.DocumentHeader(docId = documentId, queryId = null).encode(),
                mainContentData = ListItemMainContentDataUi.Text(text = documentName),
                supportingContentData = ListItemSupportingContentDataUi.Text(
                    text = strings[Res.string.transaction_details_collapsed_supporting_text],
                ),
                trailingContentData = ListItemTrailingContentDataUi.Icon(
                    iconData = AppIcons.KeyboardArrowDown,
                ),
            ),
            // The read-only sibling of the consent screen's builder: the sharing already happened, so
            // these rows carry no checkboxes.
            nestedItems = payload.toExpandableListItems(),
            isExpanded = false,
        )
    }
}

/**
 * The date as the details card shows it.
 *
 * Android formats with `java.time` and a `FULL_DATETIME_PATTERN`; there is no shared formatter yet, so
 * this builds the same shape by hand rather than pulling java.time-shaped code into iosMain.
 */
private fun LocalDateTime.toDisplayableDateTime(): String =
    "${dayOfMonth.padded()}/${monthNumber.padded()}/$year ${hour.padded()}:${minute.padded()}"

private fun Int.padded(): String = toString().padStart(2, '0')
