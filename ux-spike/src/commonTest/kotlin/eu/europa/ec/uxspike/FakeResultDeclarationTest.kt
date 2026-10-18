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

package eu.europa.ec.uxspike

import eu.europa.ec.ux.contract.pres.PresCrossDeviceResultDeclaration
import eu.europa.ec.ux.contract.pres.PresCrossDeviceServiceResults
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M6c, DEC-081 / V33: the services adapter conformance check the export generates. The fake engine declares every
 * result of every service (M9r: from the generated table), so no sealed result of the contract is silently dead behind it.
 */
class FakeResultDeclarationTest {

    private val fake = FakePresCrossDeviceServices(FakeScriptPlayer(FakeScript.parse("""{"script": "t", "flow_id": "F", "calls": {}}""")) { })

    @Test
    fun theFakeDeclaresEveryResultOfTheContract() {
        assertEquals(emptyMap(), PresCrossDeviceServiceResults.undeclared(fake))
    }

    @Test
    fun aDeclarationThatOmitsAResultIsReported() {
        // M9r (rule T1): the omitted result is the first one the contract lists for the service, read from the table.
        val dropped = fake.fetchRequestResults.first()
        val name = PresCrossDeviceServiceResults.results.getValue("fetchRequest").entries.first { it.value == dropped }.key
        val omits = object : PresCrossDeviceResultDeclaration by fake {
            override val fetchRequestResults = fake.fetchRequestResults - dropped
        }
        assertEquals(mapOf("fetchRequest" to listOf(name)), PresCrossDeviceServiceResults.undeclared(omits))
    }
}
