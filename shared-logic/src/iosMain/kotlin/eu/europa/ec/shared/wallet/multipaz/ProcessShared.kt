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

package eu.europa.ec.shared.wallet.multipaz

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One value per process: made by the first caller, then handed to every caller after it.
 *
 * Callers that arrive while it is being made wait for it rather than making their own. A failure is not
 * kept — the exception goes to the caller that hit it, and the next caller tries again, which is what a
 * store that could not be opened while the device was locked needs.
 */
internal class ProcessShared<T : Any>(private val make: suspend () -> T) {

    private val lock = Mutex()
    private var value: T? = null

    suspend fun get(): T = lock.withLock { value ?: make().also { value = it } }
}
