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

package eu.europa.ec.businesslogic.extension

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Runs on both Android (JVM) and iOS (Kotlin/Native) from the same source. */
@OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher
class FlowExtensionsTest {

    @Test
    fun safeAsync_emits_the_fallback_value_when_the_flow_throws() = runTest {
        // Inject the test scheduler's dispatcher instead of safeAsync's default `ioDispatcher`
        // (Dispatchers.IO on Android): flowOn(Dispatchers.IO) would run the upstream on real
        // threads outside runTest's virtual scheduler, making [1, -1] assembly racy/flaky.
        val result = flow {
            emit(1)
            throw IllegalStateException("boom")
        }.safeAsync(dispatcher = UnconfinedTestDispatcher(testScheduler)) { -1 }.toList()

        assertEquals(listOf(1, -1), result)
    }
}
