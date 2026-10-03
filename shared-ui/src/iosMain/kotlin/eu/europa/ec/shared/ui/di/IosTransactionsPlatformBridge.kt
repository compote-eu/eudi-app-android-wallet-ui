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
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Instant

/**
 * iOS's [TransactionsPlatformBridge], reading multipaz's event log.
 *
 * The counterpart of `AndroidTransactionsPlatformBridge`: wallet-core keeps its own log on Android,
 * while here multipaz writes one as a side effect of presenting and issuing, and
 * `IosTransactionLogDomain.kt` in :shared-logic maps those events into the shared domain.
 *
 * ⚠️ **No privacy actions yet.** A data-deletion request or a transaction report needs the relying
 * party's contacts and its data protection authority, which Android reads from the registration
 * certificate wallet-core keeps with each presentation. multipaz keeps neither with its events, so an
 * iOS presentation offers no contact, both actions stay disabled, and nothing can reach the two
 * `record…` calls below. They refuse rather than pretend, until the events carry those details.
 */
internal class IosTransactionsPlatformBridge(
    private val engine: IosWalletEngine,
    private val strings: StringCatalog,
) : TransactionsPlatformBridge {

    override suspend fun getTransactionLogs(): List<TransactionLogDomain> = engine.getTransactionLogs()

    override suspend fun getTransactionLog(id: String): TransactionLogDomain? = engine.getTransactionLog(id)

    override suspend fun deleteTransactionLog(id: String) = engine.deleteTransactionLog(id)

    /** None are recorded on iOS yet — see the class note. */
    override fun observePresentationActions(
        presentationId: String,
    ): Flow<List<TransactionLogDomain.PresentationAction>> = flowOf(emptyList())

    override suspend fun recordDataDeletionRequest(
        id: String,
        time: Instant,
        presentation: TransactionLogDomain.Presentation,
        communicationMethod: CommunicationMethodDomain,
    ): RecordTransactionPartialState = notRecorded()

    override suspend fun recordDpaReport(
        id: String,
        time: Instant,
        parentPresentationId: String,
        authority: DpaContactDomain,
        communicationMethod: CommunicationMethodDomain,
    ): RecordTransactionPartialState = notRecorded()

    private fun notRecorded() = RecordTransactionPartialState.Failure(strings[Res.string.generic_error_message])
}
