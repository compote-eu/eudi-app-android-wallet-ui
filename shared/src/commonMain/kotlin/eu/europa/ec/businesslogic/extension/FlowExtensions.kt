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

// Phase 1: moved from :business-logic to the shared KMP module (commonMain), package
// unchanged so the ~16 call sites across 6 modules keep compiling. The Dispatchers.IO
// default is supplied via the expect/actual `ioDispatcher` (see PlatformDispatchers.kt),
// because Dispatchers.IO is not part of the coroutines commonMain API.
package eu.europa.ec.businesslogic.extension

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn

fun <T> Flow<T>.safeAsync(
    dispatcher: CoroutineDispatcher = ioDispatcher,
    with: (Throwable) -> (T)
): Flow<T> {
    return this.flowOn(dispatcher).catch { emit(with(it)) }
}
