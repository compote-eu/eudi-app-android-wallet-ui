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

import eu.europa.ec.corelogic.controller.RecordTransactionPartialState
import eu.europa.ec.corelogic.model.CommunicationMethodDomain
import eu.europa.ec.corelogic.model.DpaContactDomain
import eu.europa.ec.corelogic.model.TransactionLogDomain
import eu.europa.ec.dashboardfeature.interactor.TransactionsPlatformBridge
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.StringCatalog
import eu.europa.ec.shared.resources.generic_error_message
import eu.europa.ec.shared.wallet.multipaz.IosWalletEngine
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * iOS's [TransactionsPlatformBridge], reading multipaz's event log.
 *
 * The counterpart of `AndroidTransactionsPlatformBridge`: wallet-core keeps its own log on Android,
 * while here multipaz writes one as a side effect of presenting and issuing, and
 * `IosTransactionLogDomain.kt` in :shared-logic maps those events into the shared domain.
 *
 * The privacy actions' data is this wallet's own, as on Android, where it sits beside wallet-core's log:
 * the relying party's contacts and authority are kept with each presentation's event
 * (`IosPresentationParty.kt`), and the deletion requests and reports in a table of their own
 * (`IosPresentationActions.kt`).
 */
internal class IosTransactionsPlatformBridge(
    private val engine: IosWalletEngine,
    private val strings: StringCatalog,
) : TransactionsPlatformBridge {

    override suspend fun getTransactionLogs(): List<TransactionLogDomain> = engine.getTransactionLogs()

    override suspend fun getTransactionLog(id: String): TransactionLogDomain? = engine.getTransactionLog(id)

    override suspend fun deleteTransactionLog(id: String) = engine.deleteTransactionLog(id)

    override fun observePresentationActions(
        presentationId: String,
    ): Flow<List<TransactionLogDomain.PresentationAction>> = engine.observePresentationActions(presentationId)

    override suspend fun recordDataDeletionRequest(
        id: String,
        time: Instant,
        presentation: TransactionLogDomain.Presentation,
        communicationMethod: CommunicationMethodDomain,
    ): RecordTransactionPartialState = outcome(
        engine.recordDataDeletionRequest(
            id = id,
            time = time,
            presentation = presentation,
            communicationMethod = communicationMethod,
        )
    )

    override suspend fun recordDpaReport(
        id: String,
        time: Instant,
        parentPresentationId: String,
        authority: DpaContactDomain,
        communicationMethod: CommunicationMethodDomain,
    ): RecordTransactionPartialState = outcome(
        engine.recordDpaReport(
            id = id,
            time = time,
            parentPresentationId = parentPresentationId,
            authority = authority,
            communicationMethod = communicationMethod,
        )
    )

    /** Android's recording controller answers a refused save with the generic error; so does this. */
    private fun outcome(recorded: Boolean): RecordTransactionPartialState =
        if (recorded) {
            RecordTransactionPartialState.Success
        } else {
            RecordTransactionPartialState.Failure(strings[Res.string.generic_error_message])
        }
}
