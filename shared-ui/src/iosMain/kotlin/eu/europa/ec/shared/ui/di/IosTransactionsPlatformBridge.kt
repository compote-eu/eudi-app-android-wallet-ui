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

import eu.europa.ec.dashboardfeature.interactor.PlatformTransactionLog
import eu.europa.ec.dashboardfeature.interactor.TransactionsPlatformBridge
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.languageCode

/**
 * iOS's [TransactionsPlatformBridge], reporting an empty log.
 *
 * Not a placeholder for missing plumbing so much as a statement of fact: a transaction is written when
 * the wallet issues, presents or signs, and iOS can do none of those yet. An empty list is exactly what
 * a wallet that has never transacted should return, so the shared interactor renders its real
 * empty state rather than a special case.
 *
 * When iOS gains presentation, this is where a multipaz-backed log reader goes; the shared side needs no
 * change, since [PlatformTransactionLog] is already the contract.
 */
internal class IosTransactionsPlatformBridge : TransactionsPlatformBridge {

    override fun localeTag(): String = NSLocale.currentLocale.languageCode

    override suspend fun getTransactionLogs(): List<PlatformTransactionLog> = emptyList()
}
