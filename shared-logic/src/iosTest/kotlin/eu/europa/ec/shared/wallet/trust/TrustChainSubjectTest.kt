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

package eu.europa.ec.shared.wallet.trust

import eu.europa.ec.shared.wallet.multipaz.testSignerCertificate
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The certificate a trust refusal names in its log line, read back from the chain exactly as the
 * validators receive it — DER inside `NSData`, which is the part that can go wrong.
 */
class TrustChainSubjectTest {

    @Test
    fun a_refusal_names_the_first_certificate_of_the_chain() = runTest {
        val chain = listOf(testSignerCertificate("CN=Leaf"), testSignerCertificate("CN=Root")).toTrustChain()

        assertEquals("CN=Leaf", chain.leafSubject())
    }

    @Test
    fun a_chain_that_will_not_parse_names_nothing() {
        assertNull(listOf(NSData()).leafSubject())
    }
}
