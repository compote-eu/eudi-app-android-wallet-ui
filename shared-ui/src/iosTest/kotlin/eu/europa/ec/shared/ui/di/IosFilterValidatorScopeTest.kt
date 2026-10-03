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

// One filter validator per list, as Android's `@Factory` gives each interactor. A validator holds one list,
// one set of filter groups and one result stream, so the Documents and History tabs sharing one meant each
// tab's reload reached the other: History mapped the documents to nothing and said "No transactions found"
// until its own next load (seen on the simulator 2026-10-03, while deferred PIDs were being collected).
package eu.europa.ec.shared.ui.di

import eu.europa.ec.businesslogic.validator.FilterValidator
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertNotSame
import eu.europa.ec.shared.ui.di.module as sharedUiDefinitions

class IosFilterValidatorScopeTest {

    @Test
    fun every_list_gets_a_filter_validator_of_its_own() {
        val koin = koinApplication { modules(SharedUiModule().sharedUiDefinitions()) }.koin

        assertNotSame(koin.get<FilterValidator>(), koin.get<FilterValidator>())
    }
}
