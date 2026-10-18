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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** The one-per-process holder behind `MultipazWalletStore.open()`. */
class ProcessSharedTest {

    @Test
    fun every_caller_gets_the_same_value() = runTest {
        var made = 0
        val shared = ProcessShared { made++; Any() }

        val first = shared.get()
        val second = shared.get()

        assertSame(first, second)
        assertEquals(1, made)
    }

    @Test
    fun callers_arriving_while_it_is_being_made_wait_for_it_rather_than_make_their_own() = runTest {
        var made = 0
        val gate = CompletableDeferred<Unit>()
        val shared = ProcessShared { made++; gate.await(); Any() }

        val first = async { shared.get() }
        val second = async { shared.get() }
        runCurrent()
        gate.complete(Unit)

        assertSame(first.await(), second.await())
        assertEquals(1, made)
    }

    @Test
    fun a_failure_is_not_kept_so_the_next_caller_tries_again() = runTest {
        var attempts = 0
        val shared = ProcessShared {
            attempts++
            if (attempts == 1) error("the device is locked") else "opened"
        }

        assertFailsWith<IllegalStateException> { shared.get() }

        assertEquals("opened", shared.get())
        assertEquals(2, attempts)
    }
}
