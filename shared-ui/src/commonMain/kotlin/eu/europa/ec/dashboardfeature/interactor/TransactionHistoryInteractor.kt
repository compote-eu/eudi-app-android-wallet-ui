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

// Upstream 23b98be0's history interactor, in shared code: it reads nothing but the shared transaction log,
// through [TransactionsPlatformBridge]. Strings through [StringCatalog]. Package unchanged.
package eu.europa.ec.dashboardfeature.interactor

import eu.europa.ec.businesslogic.extension.safeAsync
import eu.europa.ec.businesslogic.util.FULL_DATETIME_PATTERN_24H_SEPARATED_BY_DASH
import eu.europa.ec.businesslogic.util.formatLocalDateTime
import eu.europa.ec.corelogic.model.CommunicationMethodDomain
import eu.europa.ec.corelogic.model.TransactionLogDomain
import eu.europa.ec.dashboardfeature.ui.transactions.detail.model.TransactionDataProtectionAction
import eu.europa.ec.dashboardfeature.ui.transactions.history.model.TransactionHistoryUi
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.StringCatalog
import eu.europa.ec.shared.resources.generic_error_message
import eu.europa.ec.shared.resources.privacy_history_deletion_disclaimer
import eu.europa.ec.shared.resources.privacy_history_deletion_empty
import eu.europa.ec.shared.resources.privacy_history_deletion_intro
import eu.europa.ec.shared.resources.privacy_history_deletion_title
import eu.europa.ec.shared.resources.privacy_history_method_email
import eu.europa.ec.shared.resources.privacy_history_method_phone
import eu.europa.ec.shared.resources.privacy_history_method_web_form
import eu.europa.ec.shared.resources.privacy_history_method_website
import eu.europa.ec.shared.resources.privacy_history_relying_party_default_name
import eu.europa.ec.shared.resources.privacy_history_report_disclaimer
import eu.europa.ec.shared.resources.privacy_history_report_empty
import eu.europa.ec.shared.resources.privacy_history_report_intro
import eu.europa.ec.shared.resources.privacy_history_report_title
import eu.europa.ec.uilogic.component.ListItemDataUi
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import eu.europa.ec.uilogic.component.ListItemSupportingContentDataUi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

sealed class TransactionHistoryInteractorPartialState {
    data object ParentNotFound : TransactionHistoryInteractorPartialState()
    data object Loading : TransactionHistoryInteractorPartialState()

    data class Success(
        val history: TransactionHistoryUi
    ) : TransactionHistoryInteractorPartialState()

    data class Failure(
        val errorMessage: String
    ) : TransactionHistoryInteractorPartialState()
}

interface TransactionHistoryInteractor {
    /** Preserves recorded-instant order; local display dates must not be re-sorted. */
    fun observeHistory(
        presentationId: String,
        action: TransactionDataProtectionAction,
    ): Flow<TransactionHistoryInteractorPartialState>
}

class TransactionHistoryInteractorImpl(
    private val platform: TransactionsPlatformBridge,
    private val strings: StringCatalog,
) : TransactionHistoryInteractor {

    override fun observeHistory(
        presentationId: String,
        action: TransactionDataProtectionAction,
    ): Flow<TransactionHistoryInteractorPartialState> = flow {
        emit(TransactionHistoryInteractorPartialState.Loading)
        platform.observePresentationActions(presentationId = presentationId)
            .collect { attempts ->
                val presentation =
                    platform.getTransactionLog(id = presentationId)
                            as? TransactionLogDomain.Presentation
                if (presentation == null) {
                    emit(TransactionHistoryInteractorPartialState.ParentNotFound)
                    return@collect
                }
                emit(
                    TransactionHistoryInteractorPartialState.Success(
                        history = presentation.toHistoryUi(
                            attempts = attempts,
                            action = action,
                        )
                    )
                )
            }
    }.safeAsync {
        TransactionHistoryInteractorPartialState.Failure(
            errorMessage = it.message ?: strings[Res.string.generic_error_message],
        )
    }

    private fun TransactionLogDomain.Presentation.toHistoryUi(
        attempts: List<TransactionLogDomain.PresentationAction>,
        action: TransactionDataProtectionAction,
    ): TransactionHistoryUi {
        val matchingAttempts = attempts.filter { attempt ->
            attempt.parentPresentationId == id && when (action) {
                TransactionDataProtectionAction.RequestDataDeletion ->
                    attempt is TransactionLogDomain.DataDeletionRequest

                TransactionDataProtectionAction.ReportSuspiciousTransaction ->
                    attempt is TransactionLogDomain.DpaReport
            }
        }
        val authorityName = if (
            action == TransactionDataProtectionAction.ReportSuspiciousTransaction && matchingAttempts.isNotEmpty()
        ) {
            registration?.dpa?.name?.text?.takeIf { name -> name.isNotBlank() }
        } else {
            null
        }
        val (titleRes, disclaimerRes, introductionRes) = when (action) {
            TransactionDataProtectionAction.RequestDataDeletion -> Triple(
                Res.string.privacy_history_deletion_title,
                Res.string.privacy_history_deletion_disclaimer,
                if (matchingAttempts.isEmpty()) Res.string.privacy_history_deletion_empty
                else Res.string.privacy_history_deletion_intro,
            )

            TransactionDataProtectionAction.ReportSuspiciousTransaction -> Triple(
                Res.string.privacy_history_report_title,
                Res.string.privacy_history_report_disclaimer,
                if (matchingAttempts.isEmpty()) Res.string.privacy_history_report_empty
                else Res.string.privacy_history_report_intro,
            )
        }
        val relyingPartyName = party.name?.text?.takeIf { name -> name.isNotBlank() }
            ?: strings[Res.string.privacy_history_relying_party_default_name]

        return TransactionHistoryUi(
            title = strings.get(titleRes, relyingPartyName),
            disclaimer = strings[disclaimerRes],
            authority = authorityName,
            introduction = strings[introductionRes],
            items = matchingAttempts.map { attempt ->
                attempt.toHistoryListItem(action = action)
            },
        )
    }

    private fun TransactionLogDomain.PresentationAction.toHistoryListItem(
        action: TransactionDataProtectionAction,
    ): ListItemDataUi {
        val methodRes = when (communicationMethod) {
            CommunicationMethodDomain.Website -> if (action == TransactionDataProtectionAction.RequestDataDeletion) {
                Res.string.privacy_history_method_web_form
            } else {
                Res.string.privacy_history_method_website
            }

            CommunicationMethodDomain.Email -> Res.string.privacy_history_method_email
            CommunicationMethodDomain.Phone -> Res.string.privacy_history_method_phone
        }
        return ListItemDataUi(
            itemId = id,
            mainContentData = ListItemMainContentDataUi.Text(strings[methodRes]),
            supportingContentData = ListItemSupportingContentDataUi.Text(
                text = time.formatLocalDateTime(
                    pattern = FULL_DATETIME_PATTERN_24H_SEPARATED_BY_DASH,
                ),
                maxLines = Int.MAX_VALUE,
            ),
        )
    }
}
