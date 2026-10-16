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

package eu.europa.ec.shared.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DebugFlowHookTest {

    @AfterTest
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun withoutAModuleThereIsNoHook() {
        startKoin { modules(module { }) }
        assertNull(debugFlowHookOrNull())
    }

    @Test
    fun withAModuleTheHookIsFound() {
        startKoin {
            modules(
                module {
                    single<DebugFlowHook> {
                        object : DebugFlowHook {
                            override fun EntryProviderScope<NavKey>.entries(navigator: AppNavigator) = Unit

                            override fun redirect(route: NavKey): NavKey? = null
                        }
                    }
                },
            )
        }
        assertNotNull(debugFlowHookOrNull())
    }
}
